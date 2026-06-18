package de.servicehealtherx.crypto.pcsc;

import java.util.List;
import java.util.Map;

import de.servicehealtherx.apdu.card.CardCertificateReadService;
import de.servicehealtherx.apdu.card.CardLifecycleListener;
import de.servicehealtherx.apdu.card.CardListProvider;
import de.servicehealtherx.apdu.card.CardObjectFactory;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.SourceType;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CryptoProvider backed by directly connected PC/SC readers. Owns its <strong>own</strong>
 * {@link CmCardList} instance (FR-062 — not shared with the SICCT provider) and drives a
 * {@link PcscReaderRegistry} that maintains it from reader insert/remove events.
 *
 * <p>The card service aggregates this list with the SICCT provider's list for the unified GetCards
 * view (FR-064, implemented in US3). Reader discovery is guarded so the application starts even
 * with no PC/SC subsystem present.
 */
@ApplicationScoped
public class PcscCryptoProvider implements CryptoProvider, CardListProvider {

    /** Default PC/SC poll cadence (ms) — within the ≤2 s handle-creation budget (FR-001). */
    private static final long POLL_PERIOD_MS = 500L;

    private final CmCardList cmCardList = new CmCardList();
    private final PcscReaderRegistry readerRegistry;

    /** Card-backed certificate read (gemSpec_Kon ReadCardCertificate / TUC_KON_216). */
    private final CardCertificateReadService certReadService;

    private java.util.concurrent.ScheduledExecutorService scheduler;

    public PcscCryptoProvider() {
        this.readerRegistry = new PcscReaderRegistry(
                cmCardList,
                new CardObjectFactory(),
                CardLifecycleListener.NO_OP, // runtime layer (US3/Phase 6) wires CDI eventing
                PcscReaderRegistry.defaultTypeResolver(),
                PcscReaderRegistry.defaultTerminalSource());
        this.certReadService = new CardCertificateReadService(cmCardList, readerRegistry::portFor);
    }

    /** This provider's own CM_CARD_LIST (FR-062); aggregated by the card service for GetCards. */
    public CmCardList cmCardList() {
        return cmCardList;
    }

    PcscReaderRegistry readerRegistry() {
        return readerRegistry;
    }

    @PostConstruct
    void startReaderDiscovery() {
        try {
            readerRegistry.refreshTerminals();
            scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pcsc-reader-poll");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(() -> {
                readerRegistry.refreshTerminals();
                readerRegistry.pollAll();
            }, POLL_PERIOD_MS, POLL_PERIOD_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // No PC/SC subsystem available — provider stays up with an empty card list.
        }
    }

    @PreDestroy
    void stopReaderDiscovery() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public CryptoOperationResult sign(CryptoOperationRequest request) {
        throw new UnsupportedOperationException("PcscCryptoProvider.sign not yet implemented");
    }

    @Override
    public boolean verify(CryptoOperationRequest request, byte[] signature) {
        throw new UnsupportedOperationException("PcscCryptoProvider.verify not yet implemented");
    }

    @Override
    public CryptoOperationResult encrypt(CryptoOperationRequest request) {
        throw new UnsupportedOperationException("PcscCryptoProvider.encrypt not yet implemented");
    }

    @Override
    public CryptoOperationResult decrypt(CryptoOperationRequest request) {
        throw new UnsupportedOperationException("PcscCryptoProvider.decrypt not yet implemented");
    }

    @Override
    public java.security.cert.X509Certificate readCertificate(KeyAlias alias, String certRef, String crypt) {
        return certReadService.readCertificate(cardHandle(alias), certRef, crypt);
    }

    @Override
    public List<KeyStoreDescriptor> listKeyStores() {
        return List.of();
    }

    @Override
    public KeyStoreAvailability getAvailability(KeyAlias alias) {
        if (alias.sourceType() != SourceType.PCSC) {
            return KeyStoreAvailability.UNAVAILABLE;
        }
        return certReadService.hasCard(cardHandle(alias))
                ? KeyStoreAvailability.AVAILABLE
                : KeyStoreAvailability.UNAVAILABLE;
    }

    /**
     * The {@code cardHandle} carried in a PC/SC alias: everything after the {@code pcsc/} prefix
     * (see the SOAP layer's {@code toKeyAlias}). UUID card handles survive the alias sanitization
     * intact, so this round-trips the handle stored in CM_CARD_LIST.
     */
    private static String cardHandle(KeyAlias alias) {
        String value = alias.value();
        int slash = value.indexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    @Override
    public Map<String, KeyStoreAvailability> getAvailabilities() {
        return Map.of();
    }
}
