package de.servicehealtherx.konnektor.soap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.CardListAggregator;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.konnektor.vsdm.ReadVsdService;
import jakarta.enterprise.inject.Instance;

class VsdServiceProducerTest {

    @SuppressWarnings("unchecked")
    private VsdServiceProducer producer(boolean c2cEnabled, boolean infraResolvable) {
        VsdServiceProducer p = new VsdServiceProducer();
        Instance<CardReaderPortResolver> resolver = mock(Instance.class);
        when(resolver.isResolvable()).thenReturn(infraResolvable);
        if (infraResolvable) {
            when(resolver.get()).thenReturn(CardReaderPortResolver.NONE);
        }
        p.cardListAggregator = new CardListAggregator();
        p.portResolver = resolver;
        p.timeoutMillis = 30_000;
        p.cardToCardEnabled = c2cEnabled;
        return p;
    }

    @Test
    void produces_service_with_c2c_disabled_and_unresolved_infra() {
        ReadVsdService service = producer(false, false).readVsdService();
        assertNotNull(service);
    }

    @Test
    void produces_service_with_c2c_enabled_and_resolved_infra() {
        ReadVsdService service = producer(true, true).readVsdService();
        assertNotNull(service);
    }
}
