package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

public final class TucKon006WriteAudit {

    // EF.Logging SFI for eGK audit log
    private static final byte SFI_EF_LOGGING = 0x1E;

    private final TucKon214AppendRecord appendRecord = new TucKon214AppendRecord();

    public TucGenerationResult generateWriteAudit(CardSession cardSession, byte[] auditEntry) {
        if (cardSession.cardType() != CardType.EGK) {
            throw TucException.internalError("TUC_KON_006",
                    "Write audit is only supported for eGK cards, got: " + cardSession.cardType());
        }
        return appendRecord.generateAppendRecord(cardSession, SFI_EF_LOGGING, auditEntry);
    }
}
