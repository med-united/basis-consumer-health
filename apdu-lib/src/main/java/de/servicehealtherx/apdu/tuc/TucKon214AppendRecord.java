package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon214AppendRecord {

    public TucGenerationResult generateAppendRecord(CardSession cardSession, byte sfi, byte[] content) {
        if (cardSession.cardType() == CardType.KVK) {
            throw TucException.kvkWriteRejected("TUC_KON_214");
        }
        int p2 = (sfi & 0x1F) << 3;
        var cmd = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_APPEND_RECORD,
                0x00, p2, content);
        var step = new GeneratedApduStep(cmd, ExpectedStatusSet.successOnly(), "APPEND RECORD");
        return TucGenerationResult.of(List.of(step));
    }
}
