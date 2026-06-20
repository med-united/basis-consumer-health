package de.servicehealtherx.apdu.card;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * Query the status of a gematik card PIN without consuming a retry (gematik GET PIN STATUS,
 * gemSpec_COS §14.6.4). Transport-neutral — it only transmits ISO 7816 APDUs through a
 * {@link CardReaderPort}, so it serves both the PC/SC and SICCT providers, mirroring
 * {@link CardPinVerifier} (which actually verifies a secret and thus decrements the counter).
 *
 * <p>The proprietary {@code GET PIN STATUS} command ({@code 80 20 00 <pinRef>}) does <em>not</em>
 * present a secret; it reports the current state of the referenced password object in the status
 * word. A retry counter of {@code x} is conveyed as {@code 63Cx}; {@code 9000} means the secret is
 * already verified in the current security context (gemSpec_Kon GetPinStatus / TUC_KON_011).
 */
public final class CardPinStatusReader {

    /** GET PIN STATUS is a gematik-proprietary command — class byte {@code 0x80}. */
    private static final int CLA_PROPRIETARY = 0x80;

    /** The status of a password object, classified by the GET PIN STATUS status word. */
    public enum State {
        /** SW 9000 — the secret is already verified in the current security context. */
        VERIFIED,
        /** SW 63Cx (x &gt; 0) — set and verifiable, {@link Result#triesRemaining()} attempts left. */
        VERIFIABLE,
        /** SW 6984 — still a transport PIN, must be changed before it can be verified. */
        TRANSPORT_PIN,
        /** SW 6985 / warning empty — no secret is set yet (leerPIN). */
        EMPTY_PIN,
        /** SW 6983 / 63C0 — retry counter exhausted, the PIN is blocked. */
        BLOCKED,
        /** SW 6984-class disabled — the verification requirement is switched off. */
        DISABLED,
        /** Any other status word. */
        ERROR
    }

    /**
     * @param state          the classified password state
     * @param triesRemaining retries left when {@link State#VERIFIABLE}; {@code -1} otherwise
     * @param sw             the raw status word the card returned
     */
    public record Result(State state, int triesRemaining, int sw) {
    }

    private final CardReaderPort port;
    private final int slotNo;

    public CardPinStatusReader(CardReaderPort port, int slotNo) {
        this.port = port;
        this.slotNo = slotNo;
    }

    /**
     * Read the status of the password referenced by {@code pinRef}.
     *
     * @return the classified state; never throws on a non-success status word — the caller maps it
     * @throws CardTransportException if the APDU could not be transmitted at all
     */
    public Result read(int pinRef) throws CardTransportException {
        CommandAPDU getStatus = new CommandAPDU(CLA_PROPRIETARY, GematikISO7816.INS_VERIFY, 0x00, pinRef & 0xFF);
        ResponseAPDU resp = port.transmit(slotNo, getStatus);
        int sw = resp.getSW();
        if (sw == GematikISO7816.SW_SUCCESS) {
            return new Result(State.VERIFIED, -1, sw);
        }
        if (GematikISO7816.isPinWrongTriesRemaining(sw)) {
            int tries = GematikISO7816.pinTriesRemaining(sw);
            // 63C0 reported by GET PIN STATUS is a counter of zero — the object is blocked.
            return tries == 0
                    ? new Result(State.BLOCKED, -1, sw)
                    : new Result(State.VERIFIABLE, tries, sw);
        }
        if (sw == GematikISO7816.SW_AUTH_METHOD_BLOCKED) {
            return new Result(State.BLOCKED, -1, sw);
        }
        if (sw == GematikISO7816.SW_PIN_TRANSPORT) {
            return new Result(State.TRANSPORT_PIN, -1, sw);
        }
        if (sw == GematikISO7816.SW_CONDITION_NOT_SATISFIED) {
            // The password object exists but its use is currently switched off (DISABLE VERIFICATION
            // REQUIREMENT) — gemSpec_Kon maps this to DISABLED.
            return new Result(State.DISABLED, -1, sw);
        }
        if (sw == GematikISO7816.SW_REFERENCED_DATA_NOT_FOUND) {
            // No secret has been set for this reference yet (leerPIN).
            return new Result(State.EMPTY_PIN, -1, sw);
        }
        return new Result(State.ERROR, -1, sw);
    }
}
