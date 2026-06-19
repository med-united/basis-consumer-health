package de.servicehealtherx.crypto.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import de.servicehealtherx.apdu.card.CardListProvider;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.crypto.CryptoProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Produces the unified {@link CardReaderPortResolver} that spans every registered
 * {@link CryptoProvider}: a {@code ReadVSD} (or any card-handle-addressed read) gets the live
 * {@link CardReaderPort} for a card's {@code ctid} regardless of which transport (PC/SC, SICCT, …)
 * owns the terminal — the transport-side counterpart of {@link CardListAggregatorProducer}.
 *
 * <p>Without this bean {@code VsdServiceProducer} falls back to {@link CardReaderPortResolver#NONE}
 * and every read reports the card as unavailable (eGK reader not available), because no single
 * provider's resolver knows the terminals owned by the others.
 *
 * <p>The card-owning providers are captured once (they are {@code @ApplicationScoped}); each
 * resolution then delegates to their <em>live</em> {@link CardListProvider#portResolver()} so a
 * late-bound transport resolver (e.g. the SICCT runtime) is picked up. The lookup tries each
 * provider in turn and returns the first port found.
 */
@ApplicationScoped
public class CardReaderPortResolverProducer {

    @Inject
    Instance<CryptoProvider> cryptoProviderInstances;

    @Produces
    @ApplicationScoped
    public CardReaderPortResolver cardReaderPortResolver() {
        List<CardListProvider> providers = new ArrayList<>();
        for (CryptoProvider provider : cryptoProviderInstances) {
            if (provider instanceof CardListProvider clp) {
                providers.add(clp);
            }
        }
        return ctid -> {
            for (CardListProvider provider : providers) {
                Optional<CardReaderPort> port = provider.portResolver().portFor(ctid);
                if (port.isPresent()) {
                    return port;
                }
            }
            return Optional.empty();
        };
    }
}
