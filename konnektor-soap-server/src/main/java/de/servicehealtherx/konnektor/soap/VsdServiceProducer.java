package de.servicehealtherx.konnektor.soap;

import de.servicehealtherx.apdu.c2c.CardToCardAuthenticator;
import de.servicehealtherx.apdu.c2c.ElcCardToCardAuthenticator;
import de.servicehealtherx.apdu.card.CardListAggregator;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.konnektor.vsdm.ReadVsdService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces the {@link ReadVsdService} bean from the card-management infrastructure. The
 * {@code CardReaderPortResolver} is owned by the transport runtime
 * ({@code quarkus-sicct-extension}) and injected via {@link Instance} so the bean is always
 * producible even before a terminal is bound (a NONE resolver simply means no card is reachable yet).
 *
 * <p>The card view is the request-scoped {@link CardListAggregator}, which spans every registered
 * crypto provider; it is injected as a normal-scoped proxy and handed to the service as a supplier so
 * each {@code ReadVSD} resolves the current unified card list (FR-064).
 *
 * <p>The card-to-card authenticator is wired to the {@link ElcCardToCardAuthenticator} (one-sided ELC
 * role authentication, TUC_KON_005 {@code einseitig}) when {@code vsdm.c2c.enabled=true}, which raises
 * the eGK's {@code flagTI.30} state so EF.GVD is read in plaintext. With the default
 * {@link CardToCardAuthenticator#NONE} the service returns PD+VD+VSD_Status and omits GVD (FR-021).
 */
@ApplicationScoped
public class VsdServiceProducer {

    @Inject
    CardListAggregator cardListAggregator;

    @Inject
    Instance<CardReaderPortResolver> portResolver;

    @ConfigProperty(name = "vsdm.read.timeout-ms", defaultValue = "30000")
    long timeoutMillis;

    /**
     * Enables the card-to-card authentication for the protected data (EF.GVD). Default {@code false}:
     * the read returns PD+VD+VSD_Status and omits GVD (FR-021). When {@code true} the one-sided ELC
     * role authentication raises the eGK's {@code flagTI.30} state and EF.GVD is read in plaintext.
     * The partner card's PIN (PIN.SMC / PIN.CH) must be verified beforehand.
     */
    @ConfigProperty(name = "vsdm.c2c.enabled", defaultValue = "false")
    boolean cardToCardEnabled;

    @Produces
    @ApplicationScoped
    public ReadVsdService readVsdService() {
        CardReaderPortResolver resolver = portResolver.isResolvable() ? portResolver.get() : CardReaderPortResolver.NONE;
        CardToCardAuthenticator authenticator = cardToCardEnabled
                ? new ElcCardToCardAuthenticator()
                : CardToCardAuthenticator.NONE;
        // Supplier defers to the request-scoped aggregator proxy; ReadVsdService snapshots it per call.
        return new ReadVsdService(() -> cardListAggregator, resolver, authenticator, timeoutMillis);
    }
}
