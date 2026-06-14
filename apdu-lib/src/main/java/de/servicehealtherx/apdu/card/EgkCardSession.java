package de.servicehealtherx.apdu.card;

import java.util.concurrent.ScheduledFuture;

/**
 * eGK card session (data-model §CardSession_eGK). Identity key: {@code cardHandle}
 * (one per eGK card, FR-022 / Constraint C1).
 *
 * <p>{@code sessionID} and {@code cardSessionTimer} are assigned at StartCardSession (TUC_KON_223,
 * FR-027) and cleared at StopCardSession (TUC_KON_224); both are {@code null} until started.
 */
public final class EgkCardSession extends CardSessionContext {

    private String sessionId;                 // UUID per RFC 4122; null until started
    private ScheduledFuture<?> cardSessionTimer; // CARD_SESSION_TIMEOUT timer; null until started
    private String authBy;                    // cardHandle of the session that unlocked this eGK (FR-016)

    public EgkCardSession(String mandantId, String csid, String userId) {
        super(mandantId, csid, userId);
    }

    public String sessionId() {
        return sessionId;
    }

    public void assignSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public ScheduledFuture<?> cardSessionTimer() {
        return cardSessionTimer;
    }

    public void setCardSessionTimer(ScheduledFuture<?> timer) {
        this.cardSessionTimer = timer;
    }

    public String authBy() {
        return authBy;
    }

    public void setAuthBy(String authBy) {
        this.authBy = authBy;
    }
}
