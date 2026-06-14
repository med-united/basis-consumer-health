package de.servicehealtherx.apdu.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import de.servicehealtherx.crypto.CryptoProvider;

/**
 * Produces the unified, transport-spanning card view by aggregating across
 * every provider's own
 * {@link CmCardList} (FR-064) and resolves a {@code cardHandle} to the single
 * provider list that
 * owns it (FR-065). Transport-neutral: it operates only on
 * {@link CmCardList}/{@link CardObject}.
 *
 * <p>
 * System-wide {@code cardHandle} uniqueness (random UUID per handle) keeps the
 * union
 * duplicate-free (FR-067); {@link #findAll()} de-duplicates defensively by
 * handle regardless.
 */
public final class CardListAggregator {

    private final List<CmCardList> sources = new ArrayList<>();

    public CardListAggregator() {
    }

    public CardListAggregator(List<CmCardList> sources) {
        sources.forEach(this::addSource);
    }

    /** Register a provider's CM_CARD_LIST as an aggregation source. */
    public CardListAggregator addSource(CmCardList list) {
        sources.add(Objects.requireNonNull(list, "list"));
        return this;
    }

    /**
     * Unified view across all sources, de-duplicated by {@code cardHandle} (FR-064,
     * FR-067).
     */
    public List<CardObject> findAll() {
        return distinctByHandle(sources.stream().flatMap(s -> s.findAll().stream()).toList());
    }

    /** Filtered unified view (e.g. per-tenant / per-ctid for GetCards). */
    public List<CardObject> findAll(Predicate<CardObject> filter) {
        Objects.requireNonNull(filter, "filter");
        return distinctByHandle(sources.stream()
                .flatMap(s -> s.findAll(filter).stream())
                .toList());
    }

    /** Resolve a {@code cardHandle} across all sources (FR-064). */
    public Optional<CardObject> findByHandle(String cardHandle) {
        return sources.stream()
                .map(s -> s.findByHandle(cardHandle))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /**
     * The single provider list that currently owns {@code cardHandle}, for routing
     * (FR-065).
     */
    public Optional<CmCardList> resolveOwner(String cardHandle) {
        return sources.stream()
                .filter(s -> s.findByHandle(cardHandle).isPresent())
                .findFirst();
    }

    private static List<CardObject> distinctByHandle(List<CardObject> cards) {
        java.util.LinkedHashMap<String, CardObject> byHandle = new java.util.LinkedHashMap<>();
        for (CardObject c : cards) {
            byHandle.putIfAbsent(c.cardHandle(), c);
        }
        return new ArrayList<>(byHandle.values());
    }

    public void addCryptoProvider(CryptoProvider cryptoProvider) {
        sources.add(new CryptoProviderCardListAdapter(cryptoProvider));
    }
}
