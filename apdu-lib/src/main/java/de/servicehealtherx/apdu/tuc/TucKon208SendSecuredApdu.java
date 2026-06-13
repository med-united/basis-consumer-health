package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;
import java.util.Map;

public final class TucKon208SendSecuredApdu {

    public TucGenerationResult generateSendSecuredApdu(CardSession cardSession, byte[] signedScenario, int expectedSequenceNumber) {
        if (expectedSequenceNumber <= 0) {
            throw TucException.internalError("TUC_KON_208", "sequence number must be > 0, got: " + expectedSequenceNumber);
        }
        var cmd = new CommandAPDU(signedScenario);
        var step = new GeneratedApduStep(cmd, ExpectedStatusSet.successOnly(), "GENERAL AUTHENTICATE (secured)");
        return TucGenerationResult.of(List.of(step),
                Map.of("nextSequenceNumber", expectedSequenceNumber + 1));
    }
}
