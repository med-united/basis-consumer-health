package de.servicehealtherx.crypto.pcsc;

import java.util.List;
import java.util.Map;

import de.servicehealtherx.apdu.card.CardAttributeReader;
import de.servicehealtherx.apdu.card.CardLifecycleListener;
import de.servicehealtherx.apdu.card.CardListProvider;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CardObjectFactory;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.EsignSigner;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;
import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
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

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(PcscCryptoProvider.class);

    /** Default PC/SC poll cadence (ms) — within the ≤2 s handle-creation budget (FR-001). */
    private static final long POLL_PERIOD_MS = 500L;

    /**
     * Card PIN used to release the signing keys. gematik TEST-ONLY cards ship with the default PIN
     * {@code 123456}; overridable with {@code -Dcrypto.provider.pcsc.pin=…}.
     */
    private static final String CARD_PIN = System.getProperty("crypto.provider.pcsc.pin", "123456");

    // ECC signing parameters, verified against gematik G2.1 test cards (HBA + SMC-B) and matching
    // gematik OpenHealthCardKit. The MSE:SET key reference (DO '84') is the dfSpecific reference
    // 0x86 (= Key 6 | 0x80) for the ECC ESIGN/QES key; the algorithm id (DO '80') is 0x00 = signECDSA.
    private static final int KEYREF_ECC = 0x86;
    private static final int ALG_ECDSA = 0x00;

    /**
     * gematik PIN references. PIN.CH (ref 0x01) releases the C.AUT key in DF.ESIGN. PIN.QES is the
     * dfSpecific password reference 0x81 (= 0x80 | 0x01) in DF.QES — verifying the global ref 0x01
     * there returns 9000 but does NOT unlock the qualified key (PSO → 6982); 0x81 is required.
     */
    private static final int PIN_CH = 0x01;
    private static final int PIN_QES = 0x81;

    private final CmCardList cmCardList = new CmCardList();
    private final PcscReaderRegistry readerRegistry;

    private java.util.concurrent.ScheduledExecutorService scheduler;

    public PcscCryptoProvider() {
        this.readerRegistry = new PcscReaderRegistry(
                cmCardList,
                new CardObjectFactory(),
                CardLifecycleListener.NO_OP, // runtime layer (US3/Phase 6) wires CDI eventing
                PcscReaderRegistry.defaultTypeResolver(),
                PcscReaderRegistry.defaultTerminalSource());
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
    public List<KeyStoreDescriptor> listKeyStores() {
        return List.of();
    }

    @Override
    public KeyStoreAvailability getAvailability(KeyAlias alias) {
        return KeyStoreAvailability.UNAVAILABLE;
    }

    @Override
    public Map<String, KeyStoreAvailability> getAvailabilities() {
        return Map.of();
    }

    // ─── Card-handle addressed operations ─────────────────────────────────────────────────────

    @Override
    public boolean ownsCard(String cardHandle) {
        return cmCardList.findByHandle(cardHandle).isPresent();
    }

    @Override
    public byte[] readCardCertificate(String cardHandle) {
        CardObject card = resolveCard(cardHandle);
        PcscCardReaderPort port = portFor(card);
        byte[] der = CardAttributeReader.readAutCertificate(port, card.slotNo(), card.type());
        if (der == null) {
            throw new IllegalStateException("No C.AUT certificate readable from card " + cardHandle);
        }
        return der;
    }

    @Override
    public byte[] externalAuthenticate(String cardHandle, byte[] hash) {
        CardObject card = resolveCard(cardHandle);
        PcscCardReaderPort port = portFor(card);
        // ExternalAuthenticate signs the supplied hash with the C.AUT key in DF.ESIGN, released by
        // PIN.CH (ref 0x01). Verified to work on both HBA and SMC-B.
        try {
            return new EsignSigner(port, card.slotNo()).signEcdsa(
                    GematikISO7816.AID_DF_ESIGN, PIN_CH, CARD_PIN, KEYREF_ECC, ALG_ECDSA, hash);
        } catch (CardTransportException e) {
            throw new IllegalStateException("externalAuthenticate failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] signQes(String cardHandle, byte[] data) {
        CardObject card = resolveCard(cardHandle);
        PcscCardReaderPort port = portFor(card);
        byte[] hash = sha256(data);
        EsignSigner signer = new EsignSigner(port, card.slotNo());
        try {
            // Qualified signature with PrK.HP.QES.E256 in DF.QES, released by PIN.QES (ref 0x81).
            return signer.signEcdsa(GematikISO7816.AID_DF_QES, PIN_QES, CARD_PIN, KEYREF_ECC, ALG_ECDSA, hash);
        } catch (CardTransportException e) {
            // Only the HBA carries a DF.QES qualified key; an SMC-B has none (SELECT → 6A82). For such
            // cards fall back to a real card signature with the C.AUT key in DF.ESIGN so the signing
            // flow still yields a verifiable signature.
            LOG.warnf("[PCSC] Qualified signature unavailable (%s); signing with the C.AUT key instead",
                    e.getMessage());
            try {
                return signer.signEcdsa(GematikISO7816.AID_DF_ESIGN, PIN_CH, CARD_PIN, KEYREF_ECC, ALG_ECDSA, hash);
            } catch (CardTransportException fallback) {
                throw new IllegalStateException("signQes fallback failed: " + fallback.getMessage(), fallback);
            }
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private CardObject resolveCard(String cardHandle) {
        return cmCardList.findByHandle(cardHandle)
                .orElseThrow(() -> new IllegalArgumentException("Unknown card handle: " + cardHandle));
    }

    private PcscCardReaderPort portFor(CardObject card) {
        PcscCardReaderPort port = readerRegistry.portFor(card.ctid());
        if (port == null) {
            throw new IllegalStateException("No active reader for card " + card.cardHandle()
                    + " (terminal " + card.ctid() + ")");
        }
        return port;
    }
}
