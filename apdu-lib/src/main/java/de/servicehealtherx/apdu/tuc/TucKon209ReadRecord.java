package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon209ReadRecord {

    public TucGenerationResult generateReadRecord(CardSession cardSession, byte sfi, int recordNumber, int expectedLength) {
        int p2 = ((sfi & 0x1F) << 3) | 0x04;
        var cmd = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_RECORD,
                recordNumber, p2, expectedLength);
        var step = new GeneratedApduStep(cmd, ExpectedStatusSet.successOnly(),
                "READ RECORD #" + recordNumber);
        return TucGenerationResult.of(List.of(step));
    }
}
