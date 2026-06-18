package de.servicehealtherx.apdu.card;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * Performs an on-card ECDSA signature with a gematik G2.1 ESIGN/QES private key (PSO: COMPUTE
 * DIGITAL SIGNATURE, gemSpec_COS §14.8.2). Transport-neutral — it only transmits ISO 7816 APDUs
 * through a {@link CardReaderPort}, so it serves both the PC/SC and SICCT providers, mirroring
 * {@link CardAttributeReader}.
 *
 * <p>The exact sequence and parameters were verified against gematik G2.1 test cards (HBA + SMC-B)
 * and match gematik's reference {@code OpenHealthCardKit} (selectSigning: P1=0x41/P2=0xB6 DST,
 * key DO {@code 84}, algorithm DO {@code 80}; PsoDSA.sign: {@code 2A 9E 9A}):
 * <ol>
 *   <li>SELECT the application DF by AID (DF.ESIGN for C.AUT, DF.QES for the qualified key).</li>
 *   <li>{@code VERIFY} the protecting PIN (ISO format-2 PIN block) — PIN.CH (ref 0x01 in DF.ESIGN)
 *       for the authentication key, PIN.QES (ref 0x01 in DF.QES) for the qualified key.</li>
 *   <li>{@code MSE:SET} the Digital Signature Template (P1=0x41, P2=0xB6): key reference DO {@code 84}
 *       (the dfSpecific key reference, e.g. {@code 0x86} = Key 6 | 0x80) and algorithm DO {@code 80}
 *       = {@code 0x00} (signECDSA).</li>
 *   <li>{@code PSO:COMPUTE DIGITAL SIGNATURE} ({@code 00 2A 9E 9A}) over the 32-byte hash; the card
 *       returns the raw ECDSA signature (R||S, 64 bytes for brainpoolP256r1).</li>
 * </ol>
 */
public final class EsignSigner {

    /** P2 of MSE:SET selecting the Digital Signature Template (gemSpec_COS §15.9). */
    private static final int MSE_DST = 0xB6;

    /** PSO:COMPUTE DIGITAL SIGNATURE — P1=return signature, P2=input is data to be signed. */
    private static final int PSO_CDS_P1 = 0x9E;
    private static final int PSO_CDS_P2 = 0x9A;

    private final CardReaderPort port;
    private final int slotNo;

    public EsignSigner(CardReaderPort port, int slotNo) {
        this.port = port;
        this.slotNo = slotNo;
    }

    /**
     * Compute an ECDSA signature over {@code hash} with the key {@code keyReference} of the
     * application identified by {@code applicationAid}, after verifying {@code pin} against
     * {@code pinRef} (pass {@code pinRef < 0} to skip PIN verification).
     *
     * @return the raw card signature (R||S for ECDSA)
     * @throws CardTransportException if any step returns a non-success status word; the message
     *     carries the failing step and SW so callers can branch on it (e.g. 6982 → QES inactive).
     */
    public byte[] signEcdsa(byte[] applicationAid, int pinRef, String pin,
                            int keyReference, int algorithmId, byte[] hash)
            throws CardTransportException {
        selectApplication(applicationAid);
        if (pinRef >= 0) {
            verifyPin(pinRef, pin);
        }
        manageSecurityEnvironment(keyReference, algorithmId);
        return performSignature(hash);
    }

    private void selectApplication(byte[] aid) throws CardTransportException {
        CommandAPDU select = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_DF_NAME, 0x0C, aid);
        expectSuccess(port.transmit(slotNo, select), "SELECT application");
    }

    /** VERIFY a PIN encoded as an ISO format-2 PIN block (gemSpec_COS): {@code 2L d…d F…F}. */
    private void verifyPin(int pinRef, String pin) throws CardTransportException {
        CommandAPDU verify = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_VERIFY, 0x00, pinRef, format2PinBlock(pin));
        ResponseAPDU resp = port.transmit(slotNo, verify);
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            if (GematikISO7816.isPinWrongTriesRemaining(resp.getSW())) {
                throw new CardTransportException("VERIFY PIN failed: wrong PIN, "
                        + GematikISO7816.pinTriesRemaining(resp.getSW()) + " tries remaining");
            }
            throw new CardTransportException("VERIFY PIN failed: SW="
                    + String.format("%04X", resp.getSW()));
        }
    }

    private void manageSecurityEnvironment(int keyReference, int algorithmId) throws CardTransportException {
        byte[] data = {
                (byte) GematikISO7816.TAG_KEY_REF, 0x01, (byte) keyReference,
                (byte) GematikISO7816.TAG_ALGORITHM_ID, 0x01, (byte) algorithmId
        };
        CommandAPDU mse = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_MANAGE_SECURITY_ENV,
                GematikISO7816.MSE_SET_COMPUTE, MSE_DST, data);
        expectSuccess(port.transmit(slotNo, mse), "MSE:SET DST");
    }

    private byte[] performSignature(byte[] hash) throws CardTransportException {
        CommandAPDU pso = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_PERFORM_SECURITY_OPERATION,
                PSO_CDS_P1, PSO_CDS_P2, hash, 256);
        ResponseAPDU resp = port.transmit(slotNo, pso);
        expectSuccess(resp, "PSO:COMPUTE DIGITAL SIGNATURE");
        return resp.getData();
    }

    private static void expectSuccess(ResponseAPDU resp, String step) throws CardTransportException {
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardTransportException(step + " failed: SW=" + String.format("%04X", resp.getSW()));
        }
    }

    /**
     * Encode a numeric PIN as an ISO 9564 format-2 PIN block (8 bytes): high nibble {@code 0x2}
     * (format), low nibble = PIN length, the PIN digits in BCD, then {@code 0xF} padding.
     */
    static byte[] format2PinBlock(String pin) {
        byte[] block = new byte[]{0x20, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        block[0] = (byte) (0x20 | (pin.length() & 0x0F));
        int nibbleIndex = 0;
        for (int i = 0; i < pin.length(); i++) {
            int digit = pin.charAt(i) - '0';
            int byteIndex = 1 + (nibbleIndex / 2);
            if (nibbleIndex % 2 == 0) {
                block[byteIndex] = (byte) ((digit << 4) | 0x0F);
            } else {
                block[byteIndex] = (byte) ((block[byteIndex] & 0xF0) | digit);
            }
            nibbleIndex++;
        }
        return block;
    }
}
