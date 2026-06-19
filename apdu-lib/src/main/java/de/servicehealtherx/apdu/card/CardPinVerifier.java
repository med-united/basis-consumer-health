package de.servicehealtherx.apdu.card;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * VERIFY a gematik card PIN (gemSpec_COS §14.6.2). Transport-neutral — it only transmits ISO 7816
 * APDUs through a {@link CardReaderPort}, so it serves both the PC/SC and SICCT providers, mirroring
 * {@link EsignSigner} and {@link CardAttributeReader}.
 *
 * <p>The {@code VERIFY} command ({@code 00 20 00 <pinRef>}) carries the secret as an ISO 9564
 * format-2 PIN block (shared with {@link EsignSigner#format2PinBlock(String)}). PIN.SMC on an SMC-B
 * (and PIN.CH on an HBA) is the global password reference {@code 0x01}; verifying it releases the
 * objects it protects (e.g. the C.AUT key in DF.ESIGN). No application SELECT is required for a
 * global PIN — after card reset the MF is the implicitly selected DF.
 */
public final class CardPinVerifier {

    /** The card's response to a VERIFY, classified by status word. */
    public enum Outcome {
        /** SW 9000 — PIN verified, protected objects usable. */
        VERIFIED,
        /** SW 63Cx — wrong PIN, {@link Result#triesRemaining()} attempts left before it blocks. */
        WRONG,
        /** SW 6983 — retry counter exhausted, the PIN is blocked. */
        BLOCKED,
        /** SW 6984 — still a transport PIN, must be changed first. */
        TRANSPORT_PIN,
        /** Any other status word. */
        ERROR
    }

    /**
     * @param outcome        the classified VERIFY result
     * @param triesRemaining retries left after a wrong attempt ({@link Outcome#WRONG}); {@code -1} otherwise
     * @param sw             the raw status word the card returned
     */
    public record Result(Outcome outcome, int triesRemaining, int sw) {
    }

    private final CardReaderPort port;
    private final int slotNo;

    public CardPinVerifier(CardReaderPort port, int slotNo) {
        this.port = port;
        this.slotNo = slotNo;
    }

    /**
     * VERIFY {@code pin} against the password reference {@code pinRef}.
     *
     * @return the classified outcome; never throws on a non-success status word — the caller maps it
     * @throws CardTransportException if the APDU could not be transmitted at all
     */
    public Result verify(int pinRef, String pin) throws CardTransportException {
        CommandAPDU verify = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_VERIFY,
                0x00, pinRef & 0xFF, EsignSigner.format2PinBlock(pin));
        ResponseAPDU resp = port.transmit(slotNo, verify);
        int sw = resp.getSW();
        if (sw == GematikISO7816.SW_SUCCESS) {
            return new Result(Outcome.VERIFIED, -1, sw);
        }
        if (GematikISO7816.isPinWrongTriesRemaining(sw)) {
            return new Result(Outcome.WRONG, GematikISO7816.pinTriesRemaining(sw), sw);
        }
        if (sw == GematikISO7816.SW_AUTH_METHOD_BLOCKED) {
            return new Result(Outcome.BLOCKED, -1, sw);
        }
        if (sw == GematikISO7816.SW_PIN_TRANSPORT) {
            return new Result(Outcome.TRANSPORT_PIN, -1, sw);
        }
        return new Result(Outcome.ERROR, -1, sw);
    }
}
