package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon210WriteRecord {

    public TucGenerationResult generateWriteRecord(CardSession cardSession, byte sfi, int recordNumber, byte[] content) {
        if (cardSession.cardType() == CardType.KVK) {
            throw TucException.kvkWriteRejected("TUC_KON_210");
        }
        int p2 = ((sfi & 0x1F) << 3) | 0x04;
        var cmd = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_UPDATE_RECORD,
                recordNumber, p2, content);
        var step = new GeneratedApduStep(cmd, ExpectedStatusSet.successOnly(),
                "UPDATE RECORD #" + recordNumber);
        return TucGenerationResult.of(List.of(step));
    }
}
