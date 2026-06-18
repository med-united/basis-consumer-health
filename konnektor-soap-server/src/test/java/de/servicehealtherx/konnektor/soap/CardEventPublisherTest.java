package de.servicehealtherx.konnektor.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.cetp.EventType;
import de.servicehealtherx.cetp.KonnektorSystemEvent;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Verifies the card lifecycle → CETP bridge (BUGS.txt #3): a card entering a reader must fire a
 * {@code CARD/INSERTED} {@link KonnektorSystemEvent}, and removal a {@code CARD/REMOVED} one.
 */
class CardEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final Event<KonnektorSystemEvent> bus = Mockito.mock(Event.class);

    private CardEventPublisher publisher() {
        CardEventPublisher p = new CardEventPublisher();
        p.eventBus = bus;
        return p;
    }

    private static CardObject hba() {
        return CardObject.builder()
                .cardHandle("card-123")
                .ctid(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
                .slotNo(1)
                .type(CardType.HBA)
                .iccsn("80276001011234567890")
                .cardHolderName("Dr. Test")
                .build();
    }

    @Test
    void fires_card_inserted_event_with_card_parameters() {
        publisher().onCardInserted(hba());

        ArgumentCaptor<KonnektorSystemEvent> captor = ArgumentCaptor.forClass(KonnektorSystemEvent.class);
        verify(bus).fireAsync(captor.capture());

        KonnektorSystemEvent event = captor.getValue();
        assertEquals(CardEventPublisher.TOPIC_CARD_INSERTED, event.topic());
        assertEquals(EventType.Operation, event.eventType());
        assertEquals("card-123", event.parameters().get("CardHandle"));
        assertEquals("HBA", event.parameters().get("CardType"));
        assertEquals("1", event.parameters().get("SlotID"));
        assertEquals("Dr. Test", event.parameters().get("CardHolderName"));
    }

    @Test
    void fires_card_removed_event() {
        publisher().onCardRemoved(hba());

        ArgumentCaptor<KonnektorSystemEvent> captor = ArgumentCaptor.forClass(KonnektorSystemEvent.class);
        verify(bus).fireAsync(captor.capture());
        assertEquals(CardEventPublisher.TOPIC_CARD_REMOVED, captor.getValue().topic());
    }

    @Test
    void null_card_is_ignored() {
        publisher().onCardInserted(null);
        verify(bus, Mockito.never()).fireAsync(any());
    }
}
