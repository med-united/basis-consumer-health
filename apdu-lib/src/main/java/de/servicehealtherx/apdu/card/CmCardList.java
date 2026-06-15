package de.servicehealtherx.apdu.card;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * CM_CARD_LIST — the Kartendienst card-management list (gemSpec_Kon §4.1.5),
 * holding one
 * {@link CardObject} per card known to its owning provider.
 *
 * <p>
 * <strong>Per-provider, not shared</strong> (FR-062): the type is defined here
 * in
 * {@code apdu-lib}, but each CryptoProvider instantiates and owns its own
 * instance. The unified
 * card view is the aggregation of all providers' instances; {@code cardHandle}
 * is unique
 * system-wide across that union (FR-067). This class is transport-neutral — it
 * references no
 * PC/SC or SICCT type.
 *
 * <p>
 * Thread-safe: insertions/removals may arrive concurrently from reader/terminal
 * events.
 */
public class CmCardList {

    /**
     * An invalidated cardHandle held back from reuse until {@code expiry} (FR-003,
     * 48 h).
     */
    private record BlacklistedEntry(String cardHandle, Instant expiry) {
    }

    /** 48-hour no-reuse window (FR-003). */
    static final Duration NO_REUSE_WINDOW = Duration.ofHours(48);

    /** Upper bound on blacklist size (plan.md constraints). */
    static final int MAX_BLACKLIST = 10_000;

    private final ConcurrentHashMap<String, CardObject> activeCards = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<CardObject>> cardsByCtid = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<BlacklistedEntry> recentlyInvalidated = new ConcurrentLinkedDeque<>();

    /**
     * Add a CardObject (TUC_KON_001). Rejects a duplicate {@code cardHandle} or one
     * still inside
     * the 48-hour no-reuse blacklist.
     *
     * @throws IllegalStateException if the cardHandle is already active or
     *                               blacklisted
     */
    public void add(CardObject card) {
        Objects.requireNonNull(card, "card");
        pruneBlacklist();
        if (isBlacklisted(card.cardHandle())) {
            throw new IllegalStateException("cardHandle is blacklisted for reuse: " + card.cardHandle());
        }
        CardObject previous = activeCards.putIfAbsent(card.cardHandle(), card);
        if (previous != null) {
            throw new IllegalStateException("duplicate cardHandle: " + card.cardHandle());
        }
        cardsByCtid.computeIfAbsent(card.ctid(), k -> new CopyOnWriteArrayList<>()).add(card);
    }

    /**
     * Remove a card by handle and add it to the 48-hour blacklist. Returns the
     * removed card.
     */
    public Optional<CardObject> removeByHandle(String cardHandle) {
        CardObject removed = activeCards.remove(cardHandle);
        if (removed != null) {
            removeFromCtidIndex(removed);
            blacklist(cardHandle);
        }
        return Optional.ofNullable(removed);
    }

    /** Remove the card in a given slot (card removal on either transport). */
    public Optional<CardObject> removeBySlot(UUID ctid, int slotNo) {
        return findBySlot(ctid, slotNo).flatMap(c -> removeByHandle(c.cardHandle()));
    }

    /**
     * Bulk invalidation on disconnect/unplug of a reader or terminal (FR-044,
     * FR-069).
     */
    public List<CardObject> removeAllForTerminal(UUID ctid) {
        List<CardObject> cards = cardsByCtid.getOrDefault(ctid, List.of());
        List<CardObject> removed = new ArrayList<>();
        for (CardObject card : new ArrayList<>(cards)) {
            removeByHandle(card.cardHandle()).ifPresent(removed::add);
        }
        cardsByCtid.remove(ctid);
        return removed;
    }

    /**
     * Generate a fresh CardHandle for TUC_KON_001 step 2a: guaranteed unique within this
     * CM_CARD_LIST and not currently held back by the 48-hour no-reuse window (FR-003). The
     * returned handle is not yet registered — pass it to a {@link CardObject} before {@link #add}.
     */
    public String generateCardHandle() {
        pruneBlacklist();
        String handle;
        do {
            handle = UUID.randomUUID().toString();
        } while (activeCards.containsKey(handle) || isBlacklisted(handle));
        return handle;
    }

    /** O(1) resolution used by every card-addressing TUC. */
    public Optional<CardObject> findByHandle(String cardHandle) {
        return Optional.ofNullable(activeCards.get(cardHandle));
    }

    /** Slot addressing (RequestCard/EjectCard, startup reconstruction). */
    public Optional<CardObject> findBySlot(UUID ctid, int slotNo) {
        return cardsByCtid.getOrDefault(ctid, List.of()).stream()
                .filter(c -> c.slotNo() == slotNo)
                .findFirst();
    }

    /**
     * All cards in this instance (the card service concatenates per-provider
     * results, FR-064).
     */
    public List<CardObject> findAll() {
        return List.copyOf(activeCards.values());
    }

    /** Filtered view of this instance's cards. */
    public List<CardObject> findAll(Predicate<CardObject> filter) {
        Objects.requireNonNull(filter, "filter");
        return activeCards.values().stream().filter(filter).toList();
    }

    /** Number of active cards currently held. */
    public int size() {
        return activeCards.size();
    }

    /**
     * Whether a handle is currently held back from reuse (test/verification
     * support).
     */
    public boolean isBlacklisted(String cardHandle) {
        Instant now = Instant.now();
        return recentlyInvalidated.stream()
                .anyMatch(e -> e.cardHandle().equals(cardHandle) && e.expiry().isAfter(now));
    }

    private void removeFromCtidIndex(CardObject card) {
        List<CardObject> list = cardsByCtid.get(card.ctid());
        if (list != null) {
            list.remove(card);
            if (list.isEmpty()) {
                cardsByCtid.remove(card.ctid(), list);
            }
        }
    }

    private void blacklist(String cardHandle) {
        recentlyInvalidated.addLast(new BlacklistedEntry(cardHandle, Instant.now().plus(NO_REUSE_WINDOW)));
        while (recentlyInvalidated.size() > MAX_BLACKLIST) {
            recentlyInvalidated.pollFirst();
        }
    }

    private void pruneBlacklist() {
        Instant now = Instant.now();
        recentlyInvalidated.removeIf(e -> e.expiry().isBefore(now));
    }
}
