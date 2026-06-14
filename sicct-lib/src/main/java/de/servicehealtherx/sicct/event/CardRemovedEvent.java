package de.servicehealtherx.sicct.event;

/**
 * CDI event fired when a Card Handle is invalidated by ejection or terminal/reader disconnect
 * (gemSpec_Kon topic {@code CARD/REMOVED}, TUC_KON_256, severity Info; FR-047).
 */
public record CardRemovedEvent(
        String cardHandle,
        String cardType,
        String iccsn,
        String ctId,
        int slotId,
        String cardHolderName,
        String kvnr) {
}
