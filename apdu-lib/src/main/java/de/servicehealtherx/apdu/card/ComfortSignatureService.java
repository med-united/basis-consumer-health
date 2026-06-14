package de.servicehealtherx.apdu.card;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comfort-signature mode management for HBAx sessions (TUC_KON_171–173; FR-029–FR-032).
 * Transport-neutral. Tracks the number of concurrently active comfort-signature sessions so the
 * configured system cap can be enforced (error 4278).
 */
public final class ComfortSignatureService {

    /** Absolute hardware cap per HBA logical channel (gemSpec_HBA_ObjSys; FR-032). */
    public static final int HBA_COMFORT_HARD_CAP = 250;

    /** PIN reference verified to enable comfort signature. */
    public static final String PIN_QES = "PIN.QES";

    private final int maxConcurrentSessions;
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    public ComfortSignatureService(int maxConcurrentSessions) {
        this.maxConcurrentSessions = maxConcurrentSessions;
    }

    /**
     * Activate comfort-signature mode for a session (FR-029). Requires PIN.QES verified.
     *
     * @param featureEnabled       system parameter SAK_COMFORT_SIGNATURE (false → cannot activate)
     * @param maxSignatureCount    configured SAK_COMFORT_SIGNATURE_MAX (capped at {@value #HBA_COMFORT_HARD_CAP})
     * @throws IllegalStateException if the feature is disabled or PIN.QES is not verified
     * @throws CardServiceException 4278 if the maximum concurrent comfort sessions is already reached
     */
    public void activate(HbaxCardSession session, boolean featureEnabled, int maxSignatureCount) {
        Objects.requireNonNull(session, "session");
        if (!featureEnabled) {
            throw new IllegalStateException("comfort signature feature is disabled (SAK_COMFORT_SIGNATURE)");
        }
        if (!isPinQesVerified(session)) {
            throw new IllegalStateException("PIN.QES must be verified before activating comfort signature");
        }
        if (HbaxCardSession.SIGN_MODE_COMFORT.equals(session.signMode())) {
            return; // already active — idempotent
        }
        if (activeSessions.get() >= maxConcurrentSessions) {
            throw new CardServiceException(4278, "maximum number of comfort signature sessions reached");
        }

        int bounded = Math.min(maxSignatureCount, HBA_COMFORT_HARD_CAP); // FR-032
        session.setSignMode(HbaxCardSession.SIGN_MODE_COMFORT);
        session.countRemaining().set(bounded);
        activeSessions.incrementAndGet();
    }

    /**
     * Deactivate comfort-signature mode (FR-030): set signMode back to PIN, remove PIN.QES from
     * the session's authState, stop the timer, and reset the remaining count.
     */
    public void deactivate(HbaxCardSession session) {
        Objects.requireNonNull(session, "session");
        if (!HbaxCardSession.SIGN_MODE_COMFORT.equals(session.signMode())) {
            return;
        }
        session.authState().removeIf(e -> e.kind() == AuthEntry.Kind.CHV && PIN_QES.equals(e.pinRef()));
        session.setSignMode(HbaxCardSession.SIGN_MODE_PIN);
        session.countRemaining().set(0);
        if (session.timeRemaining() != null) {
            session.timeRemaining().cancel(false);
            session.setTimeRemaining(null);
        }
        activeSessions.decrementAndGet();
    }

    /**
     * Account for one comfort signature (FR-031): decrement {@code countRemaining}; when it reaches
     * zero the session is automatically deactivated (FR-030).
     *
     * @return the remaining count after this signature
     */
    public int recordSignature(HbaxCardSession session) {
        if (!HbaxCardSession.SIGN_MODE_COMFORT.equals(session.signMode())) {
            throw new IllegalStateException("session is not in comfort signature mode");
        }
        int remaining = session.countRemaining().decrementAndGet();
        if (remaining <= 0) {
            deactivate(session);
            return 0;
        }
        return remaining;
    }

    /** Number of currently active comfort-signature sessions (FR-029 cap accounting). */
    public int activeSessionCount() {
        return activeSessions.get();
    }

    private static boolean isPinQesVerified(HbaxCardSession session) {
        return session.authState().stream()
                .anyMatch(e -> e.kind() == AuthEntry.Kind.CHV && PIN_QES.equals(e.pinRef()));
    }
}
