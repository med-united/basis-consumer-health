package de.servicehealtherx.apdu.card;

import java.util.List;
import java.util.UUID;

import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.crypto.CryptoProvider;

public class CryptoProviderCardListAdapter extends CmCardList {

    private static final UUID P12_CTID = UUID.fromString("5c30a80a-c173-4498-84bb-6ab2aae161b8");

    private final CryptoProvider cryptoProvider;

    public CryptoProviderCardListAdapter(CryptoProvider cryptoProvider) {
        this.cryptoProvider = cryptoProvider;
    }

    @Override
    public List<CardObject> findAll() {
        // This is a placeholder implementation. The actual logic would depend on how
        // the CryptoProvider exposes its cards.
        // For example, if the CryptoProvider has a method like `List<CardObject>
        // getCards()`, we would call it here.
        return cryptoProvider.listKeyStores().stream()
                .map(keyStore -> {
                    return CardObject.builder()
                            .cardHandle(keyStore.getAlias().value()) // Assuming alias can serve as cardHandle
                            .ctid(P12_CTID) // Using a fixed CTID for all cards from this provider, or derive it from
                                            // keyStore if possible
                            .slotNo(1) // Slot number is not applicable for CryptoProvider, set to 0 or a fixed value
                            .type(CardType.UNKNOWN)
                            .build();
                })
                .toList();
    }

}
