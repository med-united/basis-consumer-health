package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon021UnblockPin {

    public TucGenerationResult generateUnblockPin(CardSession cardSession, PinRef pinRef) {
        // RESET RETRY COUNTER: P1=0x03 (set with new reference data), P2=pinRef
        var cmd = new CommandAPDU(
                GematikISO7816.CLA_ISO,
                GematikISO7816.INS_RESET_RETRY_COUNTER,
                0x03, pinRef.reference() & 0xFF);
        var step = new GeneratedApduStep(cmd,
                ExpectedStatusSet.successOnly(),
                "RESET RETRY COUNTER " + pinRef.cosName());
        return TucGenerationResult.of(List.of(step));
    }
}
