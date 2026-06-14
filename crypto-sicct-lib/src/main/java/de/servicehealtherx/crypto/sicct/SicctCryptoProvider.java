package de.servicehealtherx.crypto.sicct;

import java.util.List;
import java.util.Map;

import de.servicehealtherx.apdu.card.CardListProvider;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
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

    @Override
    public CmCardList cmCardList() {
        return cmCardList;
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
}
