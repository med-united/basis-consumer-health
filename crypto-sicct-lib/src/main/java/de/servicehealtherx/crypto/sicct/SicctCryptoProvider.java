package de.servicehealtherx.crypto.sicct;

import java.util.List;
import java.util.Map;

import de.servicehealtherx.apdu.card.CardCertificateReadService;
import de.servicehealtherx.apdu.card.CardListProvider;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
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
        throw new UnsupportedOperationException("SicctCryptoProvider.encrypt not yet implemented");
    }

    @Override
    public CryptoOperationResult decrypt(CryptoOperationRequest request) {
        throw new UnsupportedOperationException("SicctCryptoProvider.decrypt not yet implemented");
    }

    @Override
    public java.security.cert.X509Certificate readCertificate(KeyAlias alias, String certRef, String crypt) {
        return certReadService.readCertificate(cardHandle(alias), certRef, crypt);
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
    public boolean ownsCard(String cardHandle) {
        return cmCardList.findByHandle(cardHandle).isPresent();
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
