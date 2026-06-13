package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon019ChangePin {

    public TucGenerationResult generateChangePin(CardSession cardSession, PinRef pinRef) {
        var cmd = new CommandAPDU(
                GematikISO7816.CLA_ISO,
                GematikISO7816.INS_CHANGE_REFERENCE_DATA,
                0x00, pinRef.reference() & 0xFF);
        var step = new GeneratedApduStep(cmd,
                ExpectedStatusSet.successOnly(),
                "CHANGE REFERENCE DATA " + pinRef.cosName());
        return TucGenerationResult.of(List.of(step));
    }
}
