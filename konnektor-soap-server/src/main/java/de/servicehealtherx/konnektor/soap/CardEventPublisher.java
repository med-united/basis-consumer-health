package de.servicehealtherx.konnektor.soap;

import java.util.LinkedHashMap;
import java.util.Map;

import de.servicehealtherx.apdu.card.CardLifecycleListener;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.cetp.EventSeverity;
import de.servicehealtherx.cetp.EventType;
import de.servicehealtherx.cetp.KonnektorSystemEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Bridges the transport-neutral card lifecycle ({@code apdu-lib} {@link CardLifecycleListener},
 * driven by the PC/SC and SICCT providers) to the konnektor Systeminformationsdienst: on card
 * insert/remove it fires a {@link KonnektorSystemEvent} ({@code CARD/INSERTED} / {@code CARD/REMOVED},
 * TUC_KON_256, gemSpec_Kon §4.1.6) asynchronously, which {@code CetpClient} observes and delivers to
 * subscribed client systems over CETP.
 *
 * <p>This is the "runtime layer wires CDI eventing" the crypto providers defer to: the providers
 * inject this bean as their {@link CardLifecycleListener}, so a card entering a reader now actually
 * produces a CETP event instead of being silently dropped.
 */
@ApplicationScoped
public class CardEventPublisher implements CardLifecycleListener {

    private static final Logger LOG = Logger.getLogger(CardEventPublisher.class);

    static final String TOPIC_CARD_INSERTED = "CARD/INSERTED";
    static final String TOPIC_CARD_REMOVED = "CARD/REMOVED";

    @Inject
    Event<KonnektorSystemEvent> eventBus;

    @Override
    public void onCardInserted(CardObject card) {
        fire(TOPIC_CARD_INSERTED, card);
    }

    @Override
    public void onCardRemoved(CardObject card) {
        fire(TOPIC_CARD_REMOVED, card);
    }

    private void fire(String topic, CardObject card) {
        if (card == null) {
            return;
        }
        KonnektorSystemEvent event = new KonnektorSystemEvent(
                topic, EventType.Operation, EventSeverity.Info, cardParameters(card));
        LOG.infof("Publishing %s for card %s (type %s, ctId %s, slot %d)",
                topic, card.cardHandle(), card.type(), card.ctid(), card.slotNo());
        // Asynchronous so the reader-poll thread is never blocked by event delivery (CetpClient
        // observes with @ObservesAsync).
        eventBus.fireAsync(event);
    }

    /** The CardEvents parameters (gemSpec_Kon CardEventType): CardHandle, CardType, CtID, SlotID, … */
    private static Map<String, String> cardParameters(CardObject card) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("CardHandle", card.cardHandle());
        p.put("CardType", String.valueOf(card.type()));
        if (card.ctid() != null) {
            p.put("CtID", card.ctid().toString());
        }
        p.put("SlotID", String.valueOf(card.slotNo()));
        if (card.iccsn() != null) {
            p.put("Iccsn", card.iccsn());
        }
        if (card.cardHolderName() != null) {
            p.put("CardHolderName", card.cardHolderName());
        }
        return p;
    }
}
