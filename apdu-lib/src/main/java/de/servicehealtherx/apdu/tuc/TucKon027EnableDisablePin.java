package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon027EnableDisablePin {

    public TucGenerationResult generateEnableDisablePin(CardSession cardSession, PinRef pinRef, boolean enable) {
        int ins = enable
                ? GematikISO7816.INS_ENABLE_VERIFICATION_REQUIREMENT
                : GematikISO7816.INS_DISABLE_VERIFICATION_REQUIREMENT;
        String label = (enable ? "ENABLE" : "DISABLE") + " VERIFICATION REQUIREMENT " + pinRef.cosName();
        var cmd = new CommandAPDU(GematikISO7816.CLA_ISO, ins, 0x00, pinRef.reference() & 0xFF);
        var step = new GeneratedApduStep(cmd, ExpectedStatusSet.successOnly(), label);
        return TucGenerationResult.of(List.of(step));
    }
}
