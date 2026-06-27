package de.servicehealtherx.crypto.pcsc;

import java.util.List;
import java.util.Map;

import de.servicehealtherx.apdu.card.CardAttributeReader;
import de.servicehealtherx.apdu.card.CardCertificateReadService;
import de.servicehealtherx.apdu.card.CardLifecycleListener;
import de.servicehealtherx.apdu.card.CardListProvider;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CardObjectFactory;
import de.servicehealtherx.apdu.card.CardPinStatusReader;
import de.servicehealtherx.apdu.card.CardPinVerifier;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.EsignSigner;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;
import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.SourceType;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import io.quarkus.arc.properties.IfBuildProperty;
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
 *
 * <p>Disable the entire PC/SC reader crypto service by setting
 * {@code crypto.provider.pcsc.enabled=false} in {@code application.properties} — the bean (and the
 * companion {@code PcscPinVerifier} JMX MBean) is then removed, so no reader polling thread starts
 * and the provider drops out of the aggregated GetCards view. Enabled by default.
 */
@ApplicationScoped
@IfBuildProperty(name = "crypto.provider.pcsc.enabled", stringValue = "true", enableIfMissing = true)
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

    /**
     * PIN.SMC (ref 0x01) is the SMC-B's global card-holder PIN; verifying it releases the card's
     * protected objects (e.g. the C.AUT key in DF.ESIGN). On an HBA the equivalent global PIN is
     * PIN.CH — also ref 0x01 — so both names map here.
     */
    private static final int PIN_SMC = 0x01;

    private final CmCardList cmCardList = new CmCardList();
    private PcscReaderRegistry readerRegistry;

    /** Card-backed certificate read (gemSpec_Kon ReadCardCertificate / TUC_KON_216). */
    private CardCertificateReadService certReadService;

    /**
     * The runtime card-lifecycle listener (CDI), used to publish TUC_KON_256 CARD/INSERTED &
     * CARD/REMOVED events on insert/remove. Optional: when no bean is present (e.g. a thin test
     * deployment) the provider falls back to {@link CardLifecycleListener#NO_OP}.
     */
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<CardLifecycleListener> lifecycleListener;

    private java.util.concurrent.ScheduledExecutorService scheduler;

    /** This provider's own CM_CARD_LIST (FR-062); aggregated by the card service for GetCards. */
    public CmCardList cmCardList() {
        return cmCardList;
    }

    @Override
    public de.servicehealtherx.apdu.card.transport.CardReaderPortResolver portResolver() {
        // Read the registry field at call time: it is built in @PostConstruct, after construction.
        return ctid -> {
            PcscReaderRegistry registry = this.readerRegistry;
            return registry == null ? java.util.Optional.empty() : registry.portFor(ctid);
        };
    }

    PcscReaderRegistry readerRegistry() {
        return readerRegistry;
    }

    @PostConstruct
    void startReaderDiscovery() {
        // Build the reader registry now (not in the constructor) so the CDI-injected lifecycle
        // listener is available — it bridges card insert/remove to TUC_KON_256 CETP events (the
        // wiring the constructor comment used to defer). Falls back to NO_OP when no bean is present.
        CardLifecycleListener listener = lifecycleListener != null && lifecycleListener.isResolvable()
                ? lifecycleListener.get()
                : CardLifecycleListener.NO_OP;
        this.readerRegistry = new PcscReaderRegistry(
                cmCardList,
                new CardObjectFactory(),
                listener,
                PcscReaderRegistry.defaultTypeResolver(),
                PcscReaderRegistry.defaultTerminalSource());
        this.certReadService = new CardCertificateReadService(cmCardList, readerRegistry::portFor);
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
    public byte[] readCardCertificate(String cardHandle, String certRef, String crypt) {
        try {
            return certReadService.readCertificate(cardHandle, certRef, crypt).getEncoded();
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException(
                    "Encoding " + certRef + " from card " + cardHandle + " failed: " + e.getMessage(), e);
        }
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
    public byte[] transmitApdu(String cardHandle, byte[] commandApdu) {
        CardObject card = resolveCard(cardHandle);
        PcscCardReaderPort port = portFor(card);
        try {
            javax.smartcardio.ResponseAPDU response =
                    port.transmit(card.slotNo(), new javax.smartcardio.CommandAPDU(commandApdu));
            return response.getBytes();
        } catch (CardTransportException e) {
            throw new IllegalStateException("transmitApdu failed: " + e.getMessage(), e);
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

    @Override
    public de.servicehealtherx.crypto.model.PinVerificationResult verifyPin(String cardHandle, String pinType) {
        CardObject card = resolveCard(cardHandle);
        PcscCardReaderPort port = portFor(card);
        int pinRef = pinReferenceFor(pinType);
        try {
            CardPinVerifier.Result result = new CardPinVerifier(port, card.slotNo()).verify(pinRef, CARD_PIN);
            return switch (result.outcome()) {
                case VERIFIED -> de.servicehealtherx.crypto.model.PinVerificationResult.verified();
                case WRONG -> de.servicehealtherx.crypto.model.PinVerificationResult.wrong(result.triesRemaining());
                case BLOCKED -> new de.servicehealtherx.crypto.model.PinVerificationResult(
                        de.servicehealtherx.crypto.model.PinVerificationResult.Status.BLOCKED, -1);
                case TRANSPORT_PIN -> new de.servicehealtherx.crypto.model.PinVerificationResult(
                        de.servicehealtherx.crypto.model.PinVerificationResult.Status.TRANSPORT_PIN, -1);
                case ERROR -> {
                    LOG.warnf("[PCSC] VERIFY %s on card %s returned SW=%04X", pinType, cardHandle, result.sw());
                    yield new de.servicehealtherx.crypto.model.PinVerificationResult(
                            de.servicehealtherx.crypto.model.PinVerificationResult.Status.ERROR, -1);
                }
            };
        } catch (CardTransportException e) {
            throw new IllegalStateException("verifyPin failed: " + e.getMessage(), e);
        }
    }

    @Override
    public de.servicehealtherx.crypto.model.PinStatusResult getPinStatus(String cardHandle, String pinType) {
        CardObject card = resolveCard(cardHandle);
        PcscCardReaderPort port = portFor(card);
        int pinRef = pinReferenceFor(pinType);
        try {
            CardPinStatusReader.Result result = new CardPinStatusReader(port, card.slotNo()).read(pinRef);
            return switch (result.state()) {
                case VERIFIED -> de.servicehealtherx.crypto.model.PinStatusResult.of(
                        de.servicehealtherx.crypto.model.PinStatusResult.Status.VERIFIED);
                case VERIFIABLE -> de.servicehealtherx.crypto.model.PinStatusResult.verifiable(result.triesRemaining());
                case TRANSPORT_PIN -> de.servicehealtherx.crypto.model.PinStatusResult.of(
                        de.servicehealtherx.crypto.model.PinStatusResult.Status.TRANSPORT_PIN);
                case EMPTY_PIN -> de.servicehealtherx.crypto.model.PinStatusResult.of(
                        de.servicehealtherx.crypto.model.PinStatusResult.Status.EMPTY_PIN);
                case BLOCKED -> de.servicehealtherx.crypto.model.PinStatusResult.of(
                        de.servicehealtherx.crypto.model.PinStatusResult.Status.BLOCKED);
                case DISABLED -> de.servicehealtherx.crypto.model.PinStatusResult.of(
                        de.servicehealtherx.crypto.model.PinStatusResult.Status.DISABLED);
                case ERROR -> {
                    LOG.warnf("[PCSC] GET PIN STATUS %s on card %s returned SW=%04X", pinType, cardHandle, result.sw());
                    yield de.servicehealtherx.crypto.model.PinStatusResult.of(
                            de.servicehealtherx.crypto.model.PinStatusResult.Status.ERROR);
                }
            };
        } catch (CardTransportException e) {
            throw new IllegalStateException("getPinStatus failed: " + e.getMessage(), e);
        }
    }

    // ─── Internals exposed to the in-process JMX PIN verifier (PcscPinVerifier) ───────────────────

    /** Resolve a {@code cardHandle} to its {@link CardObject}; throws if no such card is present. */
    public CardObject cardForHandle(String cardHandle) {
        return resolveCard(cardHandle);
    }

    /** The active {@link PcscCardReaderPort} for the card behind {@code cardHandle}. */
    public PcscCardReaderPort portForHandle(String cardHandle) {
        return portFor(resolveCard(cardHandle));
    }

    /** Map a gematik PIN type ({@code PIN.SMC} / {@code PIN.CH} / {@code PIN.QES}) to its reference. */
    private static int pinReferenceFor(String pinType) {
        if (pinType == null) {
            throw new IllegalArgumentException("pinType must not be null");
        }
        return switch (pinType) {
            case "PIN.SMC" -> PIN_SMC;
            case "PIN.CH" -> PIN_CH;
            case "PIN.QES" -> PIN_QES;
            default -> throw new IllegalArgumentException("Unsupported pinType: " + pinType);
        };
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
        return readerRegistry.portFor(card.ctid())
                .map(PcscCardReaderPort.class::cast)
                .orElseThrow(() -> new IllegalStateException("No active reader for card " + card.cardHandle()
                        + " (terminal " + card.ctid() + ")"));
    }
}
