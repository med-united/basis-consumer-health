package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon200SendApdu {

    public TucGenerationResult generateSendApdu(CardSession cardSession, byte[] commandApduBytes) {
        var cmd = new CommandAPDU(commandApduBytes);
        var step = new GeneratedApduStep(cmd, ExpectedStatusSet.successOnly(), "SEND APDU");
        return TucGenerationResult.of(List.of(step));
    }
}
