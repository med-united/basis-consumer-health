package de.servicehealtherx.apdu.card;

import java.util.Objects;
import java.util.Optional;

import de.servicehealtherx.apdu.model.CardType;

/**
 * Manages {@link CardSessionContext}s on a {@link CardObject} (TUC_KON_026) and the eGK start/stop
 * lock lifecycle (TUC_KON_223/224). Transport-neutral — placed in {@code apdu-lib} so the SOAP
 * layer and both providers can reuse it.
 *
 * <p>Session identity keys (FR-021–FR-025):
 * <ul>
 *   <li>eGK — {@code cardHandle} (at most one, Constraint C1)</li>
 *   <li>SM-B — {@code cardHandle + mandantId} (C3)</li>
 *   <li>HBAx — {@code cardHandle + csid + userId} (C2; userId mandatory, C16)</li>
 * </ul>
 */
public final class CardSessionService {

    private final EgkSessionLock egkLock;

    public CardSessionService(EgkSessionLock egkLock) {
        this.egkLock = Objects.requireNonNull(egkLock, "egkLock");
    }

    /**
     * Return the existing session for the given identity key, or create and attach a new one
     * (TUC_KON_026, FR-021–FR-025).
     */
    public CardSessionContext getOrCreateSession(CardObject card, CardType type,
                                                 String mandantId, String csid, String userId) {
        Objects.requireNonNull(card, "card");
        return switch (type) {
            case EGK -> findOrAdd(card, existing -> existing instanceof EgkCardSession,
                    () -> new EgkCardSession(mandantId, csid, userId));
            case SMC_B -> findOrAdd(card,
                    existing -> existing instanceof SmbCardSession s && s.mandantId().equals(mandantId),
                    () -> new SmbCardSession(mandantId, csid, userId));
            case HBA, HBAX -> findOrAdd(card,
                    existing -> existing instanceof HbaxCardSession h
                            && h.csid().equals(csid) && Objects.equals(h.userId(), userId),
                    () -> new HbaxCardSession(mandantId, csid, userId));
            default -> throw new IllegalArgumentException("card type has no session lifecycle: " + type);
        };
    }

    /**
     * Start an eGK card session (TUC_KON_223): acquire the exclusive lock and assign a sessionID.
     *
     * @return the sessionID if the lock was acquired; empty if the card is already locked
     *         (caller maps empty → error 4093, FR-022)
     */
    public Optional<String> startEgkSession(CardObject card, String holder) {
        Objects.requireNonNull(card, "card");
        Optional<String> sessionId = egkLock.tryAcquire(card.cardHandle(), holder);
        sessionId.ifPresent(id -> {
            EgkCardSession session = (EgkCardSession) getOrCreateSession(
                    card, CardType.EGK, holder, holder, null);
            session.assignSessionId(id);
        });
        return sessionId;
    }

    /**
     * Stop an eGK card session by its sessionID (TUC_KON_224).
     *
     * @return {@code true} if released; {@code false} if the sessionID is unknown
     *         (caller maps false → error 4288, FR-028)
     */
    public boolean stopEgkSession(String sessionId) {
        return egkLock.release(sessionId);
    }

    private CardSessionContext findOrAdd(CardObject card,
                                         java.util.function.Predicate<CardSessionContext> match,
                                         java.util.function.Supplier<CardSessionContext> factory) {
        return card.cardSessionList().stream()
                .filter(match)
                .findFirst()
                .orElseGet(() -> {
                    CardSessionContext created = factory.get();
                    card.cardSessionList().add(created);
                    return created;
                });
    }
}
