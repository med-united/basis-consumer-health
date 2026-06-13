package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;
import java.util.Map;

public final class TucKon022ProvidePinStatus {

    public TucGenerationResult generatePinStatus(CardSession cardSession, PinRef pinRef) {
        var getData = new CommandAPDU(
                GematikISO7816.CLA_ISO,
                GematikISO7816.INS_GET_DATA,
                0x00, pinRef.reference() & 0xFF);
        var step = new GeneratedApduStep(getData,
                ExpectedStatusSet.successOnly(),
                "GET DATA " + pinRef.cosName());
        return TucGenerationResult.of(List.of(step), Map.of("pinRef", pinRef));
    }
}
