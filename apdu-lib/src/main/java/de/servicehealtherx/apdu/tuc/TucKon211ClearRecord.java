package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon211ClearRecord {

    public TucGenerationResult generateClearRecord(CardSession cardSession, byte sfi, int recordNumber) {
        if (cardSession.cardType() == CardType.KVK) {
            throw TucException.kvkWriteRejected("TUC_KON_211");
        }
        int p2 = ((sfi & 0x1F) << 3) | 0x04;
        var cmd = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_ERASE_RECORD,
                recordNumber, p2);
        var step = new GeneratedApduStep(cmd, ExpectedStatusSet.successOnly(),
                "ERASE RECORD #" + recordNumber);
        return TucGenerationResult.of(List.of(step));
    }
}
