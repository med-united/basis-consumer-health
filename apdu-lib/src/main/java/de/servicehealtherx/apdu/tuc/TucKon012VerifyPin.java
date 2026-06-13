package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TucKon012VerifyPin {

    public TucGenerationResult generateVerifyPin(CardSession cardSession, PinRef pinRef, String callerIdentity) {
        if (cardSession.isLockedByOther(callerIdentity)) {
            throw TucException.cardReservedByOther("TUC_KON_012");
        }
        var pinStatus = cardSession.authState().getPinStatus(pinRef);
        if (pinStatus == PinStatus.BLOCKED) {
            throw TucException.pinBlocked("TUC_KON_012");
        }
        if (pinStatus == PinStatus.TRANSPORT_PIN) {
            throw TucException.transportPin("TUC_KON_012");
        }
        var cmd = new CommandAPDU(
                GematikISO7816.CLA_ISO,
                GematikISO7816.INS_VERIFY,
                0x00, pinRef.reference() & 0xFF);
        var ess = new ExpectedStatusSet(
                Set.of(GematikISO7816.SW_SUCCESS, GematikISO7816.SW_PIN_WRONG_TRIES_REMAINING_BASE,
                        GematikISO7816.SW_AUTH_METHOD_BLOCKED, GematikISO7816.SW_PIN_TRANSPORT), true);
        var step = new GeneratedApduStep(cmd, ess, "VERIFY " + pinRef.cosName());
        return TucGenerationResult.of(List.of(step),
                Map.of("pinRef", pinRef, "onSuccess", PinStatus.VERIFIED));
    }
}
