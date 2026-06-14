package de.servicehealtherx.crypto.pcsc;

import java.util.Objects;

import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;

/**
 * EjectCard handling for a directly PC/SC-connected reader (US2 task T027; FR-070, FR-071, FR-063).
 *
 * <p>A typical PC/SC reader has no mechanical throwout, so eject is performed <em>logically</em>:
 * the CardObject is removed from CM_CARD_LIST and {@code OK} is returned <strong>without</strong>
 * error 4203 (which only applies to readers that physically eject but whose card the user does not
 * remove in time). The display prompt is skipped when the reader has no display.
 */
public final class PcscEjectHandler {

    /** gemSpec_Kon error: supplied CardHandle is invalid or already invalidated. */
    public static final int ERR_INVALID_HANDLE = 4101;

    public record EjectResult(boolean success, Integer errorCode, boolean logical) {
        public static EjectResult okLogical() {
            return new EjectResult(true, null, true);
        }

        public static EjectResult okMechanical() {
            return new EjectResult(true, null, false);
        }

        public static EjectResult error(int code) {
            return new EjectResult(false, code, false);
        }
    }

    /**
     * Eject the card addressed by {@code cardHandle} from {@code port}'s reader.
     *
     * @return OK (logical for non-ejecting readers, FR-070); error 4101 if the handle is unknown
     */
    public EjectResult ejectByHandle(CmCardList cardList, CardReaderPort port, String cardHandle) {
        Objects.requireNonNull(cardList, "cardList");
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(cardHandle, "cardHandle");

        if (cardList.findByHandle(cardHandle).isEmpty()) {
            return EjectResult.error(ERR_INVALID_HANDLE);
        }

        // No display prompt attempted when the reader has no display (FR-071) — nothing to send.
        if (!port.capabilities().hasMechanicalEject()) {
            cardList.removeByHandle(cardHandle); // logical eject (FR-070)
            return EjectResult.okLogical();
        }

        // Reader supports mechanical throwout: the actual SICCT/eject command is the SICCT
        // provider's responsibility; for a PC/SC reader that reports the capability we still
        // invalidate the handle and report success.
        cardList.removeByHandle(cardHandle);
        return EjectResult.okMechanical();
    }
}
