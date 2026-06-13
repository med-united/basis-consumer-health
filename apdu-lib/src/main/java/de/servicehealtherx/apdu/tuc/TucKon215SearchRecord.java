package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon215SearchRecord {

    public TucGenerationResult generateSearchRecord(CardSession cardSession, byte sfi, byte[] searchPattern) {
        int p2 = ((sfi & 0x1F) << 3) | 0x04;
        var cmd = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SEARCH_RECORD,
                0x00, p2, searchPattern);
        var step = new GeneratedApduStep(cmd, ExpectedStatusSet.successOnly(), "SEARCH RECORD");
        return TucGenerationResult.of(List.of(step));
    }
}
