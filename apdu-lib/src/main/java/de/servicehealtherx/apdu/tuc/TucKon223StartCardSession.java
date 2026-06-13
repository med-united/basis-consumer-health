package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TucKon223StartCardSession {

    public TucGenerationResult generateStartCardSession(CardSession cardSession) {
        // MSE SET for session establishment
        var mseSet = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_MANAGE_SECURITY_ENV,
                GematikISO7816.MSE_SET_COMPUTE, 0xA4);
        var step = new GeneratedApduStep(mseSet, ExpectedStatusSet.successOnly(), "MSE SET SESSION");
        var sessionId = UUID.randomUUID();
        return TucGenerationResult.of(List.of(step), Map.of("sessionId", sessionId));
    }
}
