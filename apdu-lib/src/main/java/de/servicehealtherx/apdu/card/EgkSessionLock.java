package de.servicehealtherx.apdu.card;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the "at most one active session per eGK" rule (FR-022 / Constraint C1; research D8).
 * Keyed by {@code cardHandle}. Acquisition is atomic via {@code putIfAbsent}; the lock and its
 * {@code sessionID} live together so cleanup is straightforward on explicit stop (FR-028) or
 * timeout (FR-027).
 *
 * <p>Transport-neutral and owned per provider alongside its {@link CmCardList}.
 */
public final class EgkSessionLock {

    private record Lock(String sessionId, String holder) {}

    private final ConcurrentHashMap<String, Lock> locksByHandle = new ConcurrentHashMap<>();

    /**
     * Acquire the exclusive eGK lock for {@code cardHandle} on behalf of {@code holder}.
     *
     * @return the assigned {@code sessionID} (UUID, RFC 4122) if acquired; empty if the card is
     *         already locked (caller maps empty → error 4093, FR-022)
     */
    public Optional<String> tryAcquire(String cardHandle, String holder) {
        Objects.requireNonNull(cardHandle, "cardHandle");
        Objects.requireNonNull(holder, "holder");
        String sessionId = UUID.randomUUID().toString();
        Lock previous = locksByHandle.putIfAbsent(cardHandle, new Lock(sessionId, holder));
        return previous == null ? Optional.of(sessionId) : Optional.empty();
    }

    /**
     * Release a lock by its {@code sessionID} (TUC_KON_224).
     *
     * @return {@code true} if a lock with that sessionID existed and was released; {@code false}
     *         if the sessionID is unknown (caller maps false → error 4288, FR-028)
     */
    public boolean release(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return locksByHandle.values().removeIf(l -> l.sessionId().equals(sessionId));
    }

    /** Whether the given card currently holds an active eGK session lock. */
    public boolean isLocked(String cardHandle) {
        return locksByHandle.containsKey(cardHandle);
    }

    /** The holder identity currently owning the lock for a card, if any. */
    public Optional<String> lockHolder(String cardHandle) {
        Lock lock = locksByHandle.get(cardHandle);
        return Optional.ofNullable(lock).map(Lock::holder);
    }

    /** Drop any lock for a card without sessionID (used on physical ejection). */
    public void forceRelease(String cardHandle) {
        locksByHandle.remove(cardHandle);
    }
}
