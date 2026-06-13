package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TucKon026ProvideCardSession {

    public TucGenerationResult generateProvideCardSession(CardSession cardSession) {
        var selectMf = new CommandAPDU(
                GematikISO7816.CLA_ISO,
                GematikISO7816.INS_SELECT,
                0x00, 0x0C);
        var step = new GeneratedApduStep(selectMf, ExpectedStatusSet.successOnly(), "SELECT MF");
        var sessionId = UUID.randomUUID();
        return TucGenerationResult.of(List.of(step), Map.of("sessionId", sessionId));
    }
}
