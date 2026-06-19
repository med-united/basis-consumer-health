package de.servicehealtherx.crypto.services;

import de.servicehealtherx.apdu.card.CardListAggregator;
import de.servicehealtherx.crypto.CryptoProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Produces a request-scoped {@link CardListAggregator} that spans every currently registered
 * {@link CryptoProvider}. Each request gets a fresh, unified card view: {@code findAll()} returns the
 * {@link de.servicehealtherx.apdu.card.CardObject}s of all providers (PC/SC, SICCT, …), de-duplicated
 * by {@code cardHandle} (feature 002-card-handle, FR-064/FR-067).
 *
 * <p>Request scope keeps the snapshot of providers' card lists stable for the duration of a single
 * SOAP request while staying current across requests as cards come and go. Consumers (e.g.
 * {@code KonnektorEventService}, {@code ReadVsdService}) inject this aggregator instead of iterating
 * the {@code CryptoProvider} instances themselves.
 */
@ApplicationScoped
public class CardListAggregatorProducer {

    @Inject
    Instance<CryptoProvider> cryptoProviderInstances;

    @Produces
    @RequestScoped
    public CardListAggregator cardListAggregator() {
        return CardListAggregator.from(cryptoProviderInstances);
    }
}
