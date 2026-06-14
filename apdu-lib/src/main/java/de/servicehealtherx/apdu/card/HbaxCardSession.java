package de.servicehealtherx.apdu.card;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HBAx card session (data-model §CardSession_HBAx). Identity key:
 * {@code cardHandle + csid + userId} (Constraint C2; userId mandatory per FR-025 / C16).
 *
 * <p>{@code signMode} defaults to {@code "PIN"} and switches to {@code "Comfort"} per session
 * (FR-017); comfort-signature state is tracked by {@code countRemaining}/{@code timeRemaining}.
 */
public final class HbaxCardSession extends CardSessionContext {

    public static final String SIGN_MODE_PIN = "PIN";
    public static final String SIGN_MODE_COMFORT = "Comfort";

    private String signMode = SIGN_MODE_PIN;
    private final AtomicInteger countRemaining = new AtomicInteger(0);
    private ScheduledFuture<?> timeRemaining; // comfort-signature timer; null when signMode = PIN

    public HbaxCardSession(String mandantId, String csid, String userId) {
        super(mandantId, csid, Objects.requireNonNull(userId, "userId is mandatory for HBAx (FR-025)"));
    }

    public String signMode() {
        return signMode;
    }

    public void setSignMode(String signMode) {
        this.signMode = Objects.requireNonNull(signMode, "signMode");
    }

    public AtomicInteger countRemaining() {
        return countRemaining;
    }

    public ScheduledFuture<?> timeRemaining() {
        return timeRemaining;
    }

    public void setTimeRemaining(ScheduledFuture<?> timeRemaining) {
        this.timeRemaining = timeRemaining;
    }
}
