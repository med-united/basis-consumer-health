package de.servicehealtherx.sicct.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * CDI event fired after a Card Handle is created (gemSpec_Kon topic {@code CARD/INSERTED},
 * TUC_KON_256, eventType {@code Op}, severity {@code Info}; FR-046). Carries the full set of
 * gemSpec event parameters (TUC_KON_001 step 3) as primitives so this module needs no dependency
 * on apdu-lib: {@code CardHandle, CardType, CardVersion, ICCSN, CtID, SlotID, InsertTime,
 * CardHolderName, KVNR, CertExpirationDate}.
 *
 * <p>{@code cardVersion} is the pre-rendered COSVERSION/OBJECTSYSTEMVERSION (and, for eGK,
 * DATASTRUCTUREVERSION) string; nullable fields are {@code null} when the card did not supply them.
 */
public record CardInsertedEvent(
        String cardHandle,
        String cardType,
        String cardVersion,
        String iccsn,
        String ctId,
        int slotId,
        Instant insertTime,
        String cardHolderName,
        String kvnr,
        LocalDate certExpirationDate) {
}
