package de.servicehealtherx.sicct.event;

import java.time.Instant;

/**
 * CDI event fired after a Card Handle is created (gemSpec_Kon topic {@code CARD/INSERTED},
 * TUC_KON_256, severity Info; FR-046). Carries the gemSpec event parameters as primitives so this
 * module needs no dependency on apdu-lib.
 */
public record CardInsertedEvent(
        String cardHandle,
        String cardType,
        String iccsn,
        String ctId,
        int slotId,
        Instant insertTime,
        String cardHolderName,
        String kvnr) {
}
