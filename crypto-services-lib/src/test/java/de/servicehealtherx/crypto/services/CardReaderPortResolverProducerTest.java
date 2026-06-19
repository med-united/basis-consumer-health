package de.servicehealtherx.crypto.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.CardListProvider;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import jakarta.enterprise.inject.Instance;

/**
 * Regression test for the bug where {@code VsdServiceProducer} always fell back to
 * {@link CardReaderPortResolver#NONE} (no spanning resolver bean existed), so ReadVSD reported every
 * eGK as "reader not available". The produced resolver must turn a card's {@code ctid} into the port
 * of whichever provider owns that terminal.
 */
class CardReaderPortResolverProducerTest {

    private static final UUID EGK_CTID = UUID.fromString("00000000-0000-0000-0000-0000000000e9");
    private static final UUID OTHER_CTID = UUID.fromString("00000000-0000-0000-0000-0000000000a0");

    @Test
    void resolves_the_port_from_the_provider_that_owns_the_terminal() {
        CardReaderPort egkPort = mock(CardReaderPort.class);
        FakeProvider owns = new FakeProvider(EGK_CTID, egkPort);
        FakeProvider other = new FakeProvider(OTHER_CTID, mock(CardReaderPort.class));

        CardReaderPortResolver resolver = produce(other, owns);

        assertSame(egkPort, resolver.portFor(EGK_CTID).orElseThrow(),
                "resolver must span providers and return the owning provider's port");
    }

    @Test
    void returns_empty_when_no_provider_owns_the_terminal() {
        CardReaderPortResolver resolver = produce(new FakeProvider(OTHER_CTID, mock(CardReaderPort.class)));

        assertTrue(resolver.portFor(EGK_CTID).isEmpty());
    }

    private static CardReaderPortResolver produce(CryptoProvider... providers) {
        @SuppressWarnings("unchecked")
        Instance<CryptoProvider> instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(List.of(providers).iterator());
        CardReaderPortResolverProducer producer = new CardReaderPortResolverProducer();
        producer.cryptoProviderInstances = instance;
        return producer.cardReaderPortResolver();
    }

    /** Minimal card-owning provider that resolves a single terminal. */
    private static final class FakeProvider implements CryptoProvider, CardListProvider {

        private final UUID ctid;
        private final CardReaderPort port;

        FakeProvider(UUID ctid, CardReaderPort port) {
            this.ctid = ctid;
            this.port = port;
        }

        @Override
        public CmCardList cmCardList() {
            return new CmCardList();
        }

        @Override
        public CardReaderPortResolver portResolver() {
            return id -> id.equals(ctid) ? Optional.of(port) : Optional.empty();
        }

        @Override
        public CryptoOperationResult sign(CryptoOperationRequest request) {
            return null;
        }

        @Override
        public boolean verify(CryptoOperationRequest request, byte[] signature) {
            return false;
        }

        @Override
        public CryptoOperationResult encrypt(CryptoOperationRequest request) {
            return null;
        }

        @Override
        public CryptoOperationResult decrypt(CryptoOperationRequest request) {
            return null;
        }

        @Override
        public List<KeyStoreDescriptor> listKeyStores() {
            return List.of();
        }

        @Override
        public KeyStoreAvailability getAvailability(KeyAlias alias) {
            return null;
        }

        @Override
        public Map<String, KeyStoreAvailability> getAvailabilities() {
            return Map.of();
        }
    }
}
