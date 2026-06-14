package de.servicehealtherx.sicct.event;

/**
 * CDI event fired when the CARD_SESSION_TIMEOUT timer expires for an eGK card session, after the
 * session has been stopped (gemSpec_Kon topic {@code CARD/SESSION/TIMEOUT}, TUC_KON_256,
 * severity Info; FR-049).
 */
public record CardSessionTimeoutEvent(
        String cardType,
        String sessionId,
        long timerMs) {
}
