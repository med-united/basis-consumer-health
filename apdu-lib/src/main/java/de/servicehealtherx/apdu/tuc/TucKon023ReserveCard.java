package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import java.util.List;

public final class TucKon023ReserveCard {

    public TucGenerationResult generateLock(CardSession cardSession, String callerIdentity) {
        if (cardSession.isLockedByOther(callerIdentity)) {
            throw TucException.cardReservedByOther("TUC_KON_023");
        }
        cardSession.setLockOwner(callerIdentity);
        return TucGenerationResult.of(List.of());
    }

    public TucGenerationResult generateUnlock(CardSession cardSession, String callerIdentity) {
        cardSession.setLockOwner(null);
        return TucGenerationResult.of(List.of());
    }
}
