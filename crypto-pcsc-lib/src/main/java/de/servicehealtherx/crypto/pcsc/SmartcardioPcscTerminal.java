package de.servicehealtherx.crypto.pcsc;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardTransportException;

/**
 * Real {@link PcscTerminal} backed by a JDK {@code javax.smartcardio.CardTerminal} (research D11).
 *
 * <p>The card is connected <strong>once</strong>, on the first transmit after insertion, and that
 * single connection is reused for every subsequent APDU for as long as the card stays in the reader.
 * This keeps the card's selection and security state alive across calls — a SELECT, VERIFY and PSO
 * issued as separate {@code transmit} calls run against the same card session, exactly as a multi-step
 * card flow (e.g. select DF.QES → VERIFY PIN.QES → sign) requires.
 *
 * <p>The connection is dropped when the card leaves the reader (observed by {@link #isCardPresent()},
 * which the registry polls), when the reader is unplugged ({@link #disconnect()}), or on a transport
 * error — after which the next call transparently reconnects. Access to the held connection is
 * serialized so the poll thread and request threads cannot corrupt the session.
 */
public final class SmartcardioPcscTerminal implements PcscTerminal {

    private final CardTerminal terminal;

    /** The live connection to the card currently in the reader; {@code null} when none is held. */
    private Card card;
    /** Guards {@link #card} so connect/transmit/release are serialized across threads. */
    private final Object lock = new Object();

    public SmartcardioPcscTerminal(CardTerminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return terminal.getName();
    }

    @Override
    public boolean isCardPresent() {
        boolean present;
        try {
            present = terminal.isCardPresent();
        } catch (CardException e) {
            present = false;
        }
        if (!present) {
            // Card pulled (or reader error): drop the held connection so the next insertion gets a
            // fresh session. The card's selection/security state is gone with the card anyway.
            disconnect();
        }
        return present;
    }

    @Override
    public ResponseAPDU transmit(CommandAPDU command) throws CardTransportException {
        return withCard("PC/SC transmit", c -> c.getBasicChannel().transmit(command));
    }

    @Override
    public void disconnect() {
        synchronized (lock) {
            dropLocked();
        }
    }

    /**
     * Run {@code action} against the persistent card connection, establishing it on first use and
     * reusing it thereafter. A {@link CardException} drops the connection so the next call reconnects;
     * a {@link CardTransportException} thrown by the action itself is propagated as-is (the session
     * stays valid).
     */
    private <T> T withCard(String op, CardAction<T> action) throws CardTransportException {
        synchronized (lock) {
            try {
                if (card == null) {
                    card = terminal.connect("*");
                }
                return action.run(card);
            } catch (CardTransportException e) {
                throw e;
            } catch (CardException e) {
                dropLocked();
                throw new CardTransportException(op + " failed on reader " + name(), e);
            }
        }
    }

    /** Release the held connection; the caller must hold {@link #lock}. */
    private void dropLocked() {
        if (card != null) {
            try {
                card.disconnect(false);
            } catch (CardException ignored) {
                // best effort — the card may already be gone
            }
            card = null;
        }
    }

    @FunctionalInterface
    private interface CardAction<T> {
        T run(Card card) throws CardException, CardTransportException;
    }

    /** {@code GET_FEATURE_REQUEST} tag for {@code FEATURE_VERIFY_PIN_DIRECT} (PC/SC part 10). */
    private static final byte FEATURE_VERIFY_PIN_DIRECT = 0x06;

    @Override
    public int verifyPinOnPad(CommandAPDU selectApdu, int pinRef) throws CardTransportException {
        // SELECT, feature query and PIN_VERIFY share the persistent connection, so the application
        // context selected for a dfSpecific PIN survives into the VERIFY. The PIN_VERIFY structure
        // mirrors the gematik reference flow in ehba-cades-qes-sign/EHBACard.java.
        return withCard("PIN-pad VERIFY", card -> {
            if (selectApdu != null) {
                ResponseAPDU sel = card.getBasicChannel().transmit(selectApdu);
                if (sel.getSW() != 0x9000) {
                    throw new CardTransportException("SELECT before PIN-pad VERIFY failed on reader "
                            + name() + ": SW=" + String.format("%04X", sel.getSW()));
                }
            }
            int controlCode = getFeatureControlCode(card, FEATURE_VERIFY_PIN_DIRECT);
            byte[] structure = pinVerifyStructure((byte) (pinRef & 0xFF));
            byte[] response = card.transmitControlCommand(controlCode, structure);
            if (response.length < 2) {
                throw new CardTransportException("PIN-pad VERIFY returned no status word on reader " + name());
            }
            return ((response[response.length - 2] & 0xFF) << 8) | (response[response.length - 1] & 0xFF);
        });
    }

    /** Resolve a PC/SC part-10 feature control code via {@code CM_IOCTL_GET_FEATURE_REQUEST}. */
    private int getFeatureControlCode(Card card, byte featureTag) throws CardException, CardTransportException {
        int getFeatureRequest = scardCtlCode(3400);
        byte[] features = card.transmitControlCommand(getFeatureRequest, new byte[0]);
        // Response: repeated {Tag(1) | Len(1) | ControlCode(4, big-endian)} entries.
        for (int i = 0; i + 6 <= features.length; i += 6) {
            if (features[i] == featureTag) {
                return ((features[i + 2] & 0xFF) << 24)
                        | ((features[i + 3] & 0xFF) << 16)
                        | ((features[i + 4] & 0xFF) << 8)
                        | (features[i + 5] & 0xFF);
            }
        }
        throw new CardTransportException("Reader " + name()
                + " does not expose FEATURE_VERIFY_PIN_DIRECT (no secure PIN pad)");
    }

    /** {@code SCARD_CTL_CODE} — platform-dependent (Windows vs. PCSC-lite on Linux/macOS). */
    private static int scardCtlCode(int code) {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? (0x00310000 | (code << 2)) : (0x42000000 + code);
    }

    /**
     * Build the {@code PIN_VERIFY} control structure (PC/SC part 10) wrapping a gematik VERIFY
     * ({@code 00 20 00 <pinRef>}) with an ISO format-2 PIN block the reader fills from the PIN pad.
     */
    private static byte[] pinVerifyStructure(byte pinId) {
        return new byte[]{
                (byte) 0x00,  // bTimeOut
                (byte) 0x00,  // bTimeOut2
                (byte) 0x89,  // bmFormatString: BCD, system units = bytes, left-justified
                (byte) 0x47,  // bmPINBlockString: 4-byte block, fill nibble 0xF
                (byte) 0x04,  // bmPINLengthFormat
                (byte) 0x08,  // wPINMaxExtraDigit Lo: max 8
                (byte) 0x04,  // wPINMaxExtraDigit Hi: min 4
                (byte) 0x02,  // bEntryValidationCondition: Enter
                (byte) 0x00,  // bNumberMessage
                (byte) 0x00,  // wLangId Lo
                (byte) 0x00,  // wLangId Hi
                (byte) 0x00,  // bMsgIndex
                (byte) 0x00,  // bTeoPrologue[0]
                (byte) 0x00,  // bTeoPrologue[1]
                (byte) 0x00,  // bTeoPrologue[2]
                (byte) 0x0D,  // ulDataLength Lo = 13-byte APDU
                (byte) 0x00,
                (byte) 0x00,
                (byte) 0x00,
                // Wrapped VERIFY APDU (13 bytes):
                (byte) 0x00,  // CLA
                (byte) 0x20,  // INS: VERIFY
                (byte) 0x00,  // P1
                pinId,        // P2: PIN reference
                (byte) 0x08,  // Lc: 8-byte PIN block
                (byte) 0x20,  // PIN block byte 0: format-2 marker, length filled by reader
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
        };
    }
}
