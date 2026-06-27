package de.servicehealtherx.crypto.sicct;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.servicehealtherx.apdu.card.CardCertificateReadService;
import de.servicehealtherx.apdu.card.CardListProvider;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.EsignSigner;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.SourceType;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CryptoProvider backed by networked SICCT card terminals. Owns its <strong>own</strong>
 * {@link CmCardList} instance (FR-062 — not shared with the PC/SC provider) and implements
 * {@link CardListProvider} so the card service can aggregate it with the PC/SC provider's list for
 * the unified GetCards view (FR-064).
 *
 * <p>The SICCT runtime ({@code quarkus-sicct-extension}) feeds card insert/remove events into a
 * {@link SicctCardReaderPort} per terminal, which drives this list via a {@code CardPresenceCoordinator}
 * (wired in Phase 6 / runtime integration).
 */
@ApplicationScoped
public class SicctCryptoProvider implements CryptoProvider, CardListProvider {

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(SicctCryptoProvider.class);

    /**
     * Card PIN used to release the signing keys. gematik TEST-ONLY cards ship with the default PIN
     * {@code 123456}; overridable with {@code -Dcrypto.provider.sicct.pin=…}. Mirrors the PC/SC
     * provider so both card transports behave identically.
     */
    private static final String CARD_PIN = System.getProperty("crypto.provider.sicct.pin", "123456");

    // ECC signing parameters, identical to the PC/SC provider (verified against gematik G2.1 test
    // cards). MSE:SET key reference (DO '84') is the dfSpecific reference 0x86 (= Key 6 | 0x80) for
    // the ECC ESIGN/QES key; the algorithm id (DO '80') is 0x00 = signECDSA.
    private static final int KEYREF_ECC = 0x86;
    private static final int ALG_ECDSA = 0x00;

    /** PIN.CH (ref 0x01) releases the C.AUT key in DF.ESIGN; PIN.QES (ref 0x81) the qualified key in DF.QES. */
    private static final int PIN_CH = 0x01;
    private static final int PIN_QES = 0x81;

    /**
     * dfSpecific reference of the ECC C.ENC decipher key in DF.ESIGN (Key 3 | 0x80), used to unwrap
     * the ECIES transport key (PSO:DECIPHER). Sibling of {@link #KEYREF_ECC}; the exact reference is
     * not yet validated against real cards (no PC/SC reference implementation for decrypt exists).
     */
    private static final int KEYREF_ENC = 0x83;

    private final CmCardList cmCardList = new CmCardList();

    /**
     * Resolves the live {@link de.servicehealtherx.apdu.card.transport.CardReaderPort} for a SICCT
     * terminal. The SICCT runtime ({@code quarkus-sicct-extension}) binds this once it owns the
     * terminals; until then it knows no ports and card-backed reads report the card as unavailable.
     */
    private volatile CardReaderPortResolver portResolver = CardReaderPortResolver.NONE;

    private final CardCertificateReadService certReadService =
            new CardCertificateReadService(cmCardList, ctid -> portResolver.portFor(ctid));

    @Override
    public CmCardList cmCardList() {
        return cmCardList;
    }

    @Override
    public CardReaderPortResolver portResolver() {
        // Delegate to the (volatile) bound resolver at call time so a late bindPortResolver is seen.
        return ctid -> portResolver.portFor(ctid);
    }

    /** Bind the terminal-port resolver (called by the SICCT runtime once terminals are owned). */
    public void bindPortResolver(CardReaderPortResolver portResolver) {
        this.portResolver = portResolver != null ? portResolver : CardReaderPortResolver.NONE;
    }

    @Override
    public CryptoOperationResult sign(CryptoOperationRequest request) {
        throw new UnsupportedOperationException("SicctCryptoProvider.sign not yet implemented");
    }

    @Override
    public boolean verify(CryptoOperationRequest request, byte[] signature) {
        throw new UnsupportedOperationException("SicctCryptoProvider.verify not yet implemented");
    }

    @Override
    public CryptoOperationResult encrypt(CryptoOperationRequest request) {
        // ECIES encryption uses only the recipient's public key and is performed in software by
        // EncryptionService; it is not a card operation, so there is nothing for the SICCT card to do
        // (same as the PC/SC and P12 providers).
        throw new UnsupportedOperationException(
                "SicctCryptoProvider does not support encrypt — ECIES encryption is software-only (recipient public key)");
    }

    @Override
    public CryptoOperationResult decrypt(CryptoOperationRequest request) {
        // Hybrid (ECIES) decryption: the card unwraps the AES transport key with its C.ENC key in
        // DF.ESIGN (released by PIN.CH) via MSE:SET CT + PSO:DECIPHER. EncryptionService feeds the
        // ELC cryptogram in as request.data and uses the returned transport key. Encryption itself
        // is software-only (recipient public key) and is not a card operation — see encrypt().
        String cardHandle = cardHandle(request.alias);
        CardObject card = resolveCard(cardHandle);
        CardReaderPort port = portFor(card);
        try {
            byte[] transportKey = port.runExclusively(() -> new EsignSigner(port, card.slotNo()).decipher(
                    GematikISO7816.AID_DF_ESIGN, PIN_CH, CARD_PIN, KEYREF_ENC, request.data));
            return new CryptoOperationResult(request.alias, transportKey, null, request.algorithm);
        } catch (CardTransportException e) {
            throw new IllegalStateException("decrypt failed for card " + cardHandle + ": " + e.getMessage(), e);
        }
    }

    @Override
    public java.security.cert.X509Certificate readCertificate(KeyAlias alias, String certRef, String crypt) {
        return readCertificateExclusively(cardHandle(alias), certRef, crypt);
    }

    @Override
    public byte[] readCardCertificate(String cardHandle, String certRef, String crypt) {
        try {
            return readCertificateExclusively(cardHandle, certRef, crypt).getEncoded();
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException(
                    "Encoding " + certRef + " from card " + cardHandle + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Read a card certificate (SELECT DF + READ BINARY) holding the terminal lock so concurrent slot
     * discovery cannot reset the selected file between the SELECT and the READ. When no terminal is
     * bound for the card the read service is invoked directly so it still raises its usual
     * {@code CardCertificateException} (rather than a lock-resolution error).
     */
    private java.security.cert.X509Certificate readCertificateExclusively(
            String cardHandle, String certRef, String crypt) {
        Optional<CardReaderPort> port = cmCardList.findByHandle(cardHandle)
                .flatMap(card -> portResolver.portFor(card.ctid()));
        if (port.isEmpty()) {
            return certReadService.readCertificate(cardHandle, certRef, crypt);
        }
        try {
            return port.get().runExclusively(() -> certReadService.readCertificate(cardHandle, certRef, crypt));
        } catch (CardTransportException e) {
            throw new IllegalStateException(
                    "readCardCertificate " + certRef + " from card " + cardHandle + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean ownsCard(String cardHandle) {
        return cmCardList.findByHandle(cardHandle).isPresent();
    }

    @Override
    public byte[] externalAuthenticate(String cardHandle, byte[] hash) {
        CardObject card = resolveCard(cardHandle);
        CardReaderPort port = portFor(card);
        // ExternalAuthenticate signs the supplied hash with the C.AUT key in DF.ESIGN, released by
        // PIN.CH (ref 0x01) — identical to the PC/SC provider, driven over the SICCT terminal port.
        // Held under the terminal lock so the SELECT DF.ESIGN + VERIFY PIN + PSO sequence is atomic
        // against concurrent slot discovery.
        try {
            return port.runExclusively(() -> new EsignSigner(port, card.slotNo()).signEcdsa(
                    GematikISO7816.AID_DF_ESIGN, PIN_CH, CARD_PIN, KEYREF_ECC, ALG_ECDSA, hash));
        } catch (CardTransportException e) {
            throw new IllegalStateException("externalAuthenticate failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] signQes(String cardHandle, byte[] data) {
        CardObject card = resolveCard(cardHandle);
        CardReaderPort port = portFor(card);
        byte[] hash = sha256(data);
        // The whole qualified-or-fallback sequence runs under the terminal lock so the DF.QES attempt
        // and the DF.ESIGN fallback are not split by concurrent slot discovery.
        try {
            return port.runExclusively(() -> {
                EsignSigner signer = new EsignSigner(port, card.slotNo());
                try {
                    // Qualified signature with PrK.HP.QES.E256 in DF.QES, released by PIN.QES (ref 0x81).
                    return signer.signEcdsa(GematikISO7816.AID_DF_QES, PIN_QES, CARD_PIN, KEYREF_ECC, ALG_ECDSA, hash);
                } catch (CardTransportException e) {
                    // Only the HBA carries a DF.QES qualified key; an SMC-B has none (SELECT → 6A82).
                    // Fall back to a card signature with the C.AUT key in DF.ESIGN so the flow still
                    // yields a verifiable signature (mirrors the PC/SC provider).
                    LOG.warnf("[SICCT] Qualified signature unavailable (%s); signing with the C.AUT key instead",
                            e.getMessage());
                    return signer.signEcdsa(GematikISO7816.AID_DF_ESIGN, PIN_CH, CARD_PIN, KEYREF_ECC, ALG_ECDSA, hash);
                }
            });
        } catch (CardTransportException e) {
            throw new IllegalStateException("signQes fallback failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] transmitApdu(String cardHandle, byte[] commandApdu) {
        CardObject card = resolveCard(cardHandle);
        CardReaderPort port = portFor(card);
        try {
            ResponseAPDU response = port.transmit(card.slotNo(), new CommandAPDU(commandApdu));
            return response.getBytes();
        } catch (CardTransportException e) {
            throw new IllegalStateException("transmitApdu failed: " + e.getMessage(), e);
        }
    }

    private CardObject resolveCard(String cardHandle) {
        return cmCardList.findByHandle(cardHandle)
                .orElseThrow(() -> new IllegalArgumentException("Unknown card handle: " + cardHandle));
    }

    private CardReaderPort portFor(CardObject card) {
        return portResolver.portFor(card.ctid())
                .orElseThrow(() -> new IllegalStateException(
                        "No active SICCT terminal for card " + card.cardHandle() + " (terminal " + card.ctid() + ")"));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<KeyStoreDescriptor> listKeyStores() {
        return List.of();
    }

    @Override
    public KeyStoreAvailability getAvailability(KeyAlias alias) {
        if (alias.sourceType() != SourceType.SICCT) {
            return KeyStoreAvailability.UNAVAILABLE;
        }
        return certReadService.hasCard(cardHandle(alias))
                ? KeyStoreAvailability.AVAILABLE
                : KeyStoreAvailability.UNAVAILABLE;
    }

    /**
     * The {@code cardHandle} carried in a SICCT alias: everything after the {@code sicct/} prefix
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
