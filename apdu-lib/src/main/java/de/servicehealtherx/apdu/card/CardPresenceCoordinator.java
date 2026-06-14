package de.servicehealtherx.apdu.card;

import java.util.Objects;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.model.CardType;

/**
 * Wires a {@link CardReaderPort}'s insert/remove signals to a provider's {@link CmCardList}
 * (US1 task T017): on insertion it builds and registers a {@link CardObject} via
 * {@link CardObjectFactory} (the ≤2 s creation path, FR-001); on removal it invalidates the
 * slot's CardObject (FR-018). Lifecycle notifications are forwarded to a
 * {@link CardLifecycleListener} so the runtime can emit CARD/INSERTED and CARD/REMOVED events.
 *
 * <p>Transport-neutral: the same coordinator serves the PC/SC and SICCT providers. The provider
 * supplies the {@link CardType} resolution strategy (ATR/AID based) via {@link CardTypeResolver}.
 */
public final class CardPresenceCoordinator implements CardReaderPort.PresenceListener {

    /** Resolves the card type for a freshly inserted card in a slot (ATR/AID based, per provider). */
    @FunctionalInterface
    public interface CardTypeResolver {
        CardType resolve(CardReaderPort port, int slotNo);
    }

    private final CardReaderPort port;
    private final CmCardList cardList;
    private final CardObjectFactory factory;
    private final CardLifecycleListener listener;
    private final CardTypeResolver typeResolver;

    public CardPresenceCoordinator(CardReaderPort port, CmCardList cardList, CardObjectFactory factory,
                                   CardLifecycleListener listener, CardTypeResolver typeResolver) {
        this.port = Objects.requireNonNull(port, "port");
        this.cardList = Objects.requireNonNull(cardList, "cardList");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.listener = listener != null ? listener : CardLifecycleListener.NO_OP;
        this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
    }

    /** Subscribe to the port's presence events. Call once when the provider starts the reader. */
    public void start() {
        port.addPresenceListener(this);
    }

    /** Unsubscribe (provider shutdown). */
    public void stop() {
        port.removePresenceListener(this);
    }

    @Override
    public void onCardInserted(int slotNo) {
        CardType type = typeResolver.resolve(port, slotNo);
        CardObject card = factory.readAndCreate(cardList, port, slotNo, type);
        listener.onCardInserted(card);
    }

    @Override
    public void onCardRemoved(int slotNo) {
        cardList.removeBySlot(port.ctid(), slotNo).ifPresent(listener::onCardRemoved);
    }
}
