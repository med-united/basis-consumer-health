package de.servicehealtherx.konnektor.soap;

import de.servicehealtherx.apdu.c2c.CardToCardAuthenticator;
import de.servicehealtherx.apdu.c2c.ElcCardToCardAuthenticator;
import de.servicehealtherx.apdu.c2c.SessionKeyDerivation;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.vsdm.ReadVsdService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces the {@link ReadVsdService} bean from the card-management infrastructure. {@code CmCardList}
 * and {@code CardReaderPortResolver} are owned by the transport runtime
 * ({@code quarkus-sicct-extension}); they are injected via {@link Instance} so the bean is always
 * producible even before a terminal is bound (an empty card list / NONE resolver simply means no
 * card is reachable yet).
 *
 * <p>The card-to-card authenticator is wired to {@link CardToCardAuthenticator#NONE} for now: the
 * on-card ELC Trusted-Channel handshake that unlocks EF.GVD is hardware-bound and not yet validated
 * (see research.md C2C risk note). With NONE the service returns PD+VD+VSD_Status and omits GVD
 * (FR-021) — the AlwaysRead MVP — without faulting.
 */
@ApplicationScoped
public class VsdServiceProducer {

    @Inject
    Instance<CmCardList> cardList;

    @Inject
    Instance<CardReaderPortResolver> portResolver;

    @ConfigProperty(name = "vsdm.read.timeout-ms", defaultValue = "30000")
    long timeoutMillis;

    /**
     * Enables the on-card card-to-card authentication for the protected data (EF.GVD). Default
     * {@code false}: the read returns PD+VD+VSD_Status and omits GVD (FR-021). The ELC handshake
     * needs gSMC-K/SMC-B-mediated session keys (hardware-bound, see research.md), so it is opt-in
     * and must be validated against real cards before being enabled.
     */
    @ConfigProperty(name = "vsdm.c2c.enabled", defaultValue = "false")
    boolean cardToCardEnabled;

    @Produces
    @ApplicationScoped
    public ReadVsdService readVsdService() {
        CmCardList list = cardList.isResolvable() ? cardList.get() : new CmCardList();
        CardReaderPortResolver resolver = portResolver.isResolvable() ? portResolver.get() : CardReaderPortResolver.NONE;
        CardToCardAuthenticator authenticator = cardToCardEnabled
                ? new ElcCardToCardAuthenticator(SessionKeyDerivation.ON_CARD)
                : CardToCardAuthenticator.NONE;
        return new ReadVsdService(list, resolver, authenticator, timeoutMillis);
    }
}
