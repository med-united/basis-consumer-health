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

    private static final System.Logger LOG = System.getLogger(CardPresenceCoordinator.class.getName());

    /** Resolves the card type for a freshly inserted card in a slot (ATR/AID based, per provider). */
    @FunctionalInterface
    public interface CardTypeResolver {
        CardType resolve(CardReaderPort port, int slotNo);
    }

    /**
     * The default, transport-neutral card-type resolver covering every auto-detectable known card.
     * It performs TUC_KON_001 "Karte zuordnen" via {@link CardAttributeReader#detectCardType}:
     * after reading the universally-present MF files it probes the card's application by AID and
     * resolves
     * <ul>
     *   <li>{@link CardType#EGK} — AID {@code D2 76 00 01 44 80 00}</li>
     *   <li>{@link CardType#HBA} — AID {@code D2 76 00 01 46 01}</li>
     *   <li>{@link CardType#SMC_B} — AID {@code D2 76 00 01 46 06}</li>
     * </ul>
     * Any card whose MF files are unreadable or that carries no known health-card application
     * resolves to {@link CardType#UNKNOWN} (FR-005); the legacy {@link CardType#KVK} memory card has
     * no SELECTable application and is therefore never auto-detected (resolve it explicitly). Both
     * the PC/SC and SICCT providers share this resolver.
     */
    public static CardTypeResolver defaultTypeResolver() {
        return CardAttributeReader::detectCardType;
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
        CardObject card;
        try {
            CardType type = typeResolver.resolve(port, slotNo);
            card = factory.readAndCreate(cardList, port, slotNo, type);
        } catch (RuntimeException e) {
            // Fehlerfall: even when card-type resolution or reading fails, TUC_KON_001 step 3 must
            // still run. Register a minimal CardObject with CardType=UNKNOWN so CARD/INSERTED is
            // published for the slot rather than the insertion being silently dropped. Log the cause:
            // a silently-swallowed failure here is why a card can surface as UNKNOWN for no visible
            // reason (e.g. a transient post-insert read error).
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Card-type resolution/read failed on slot " + slotNo + " of "
                            + port.readerName() + "; registering as UNKNOWN", e);
            card = factory.createAndRegister(cardList, port, slotNo, CardType.UNKNOWN,
                    CardObjectFactory.CardAttributes.empty());
        }
        // Step 3: notify the runtime to publish TUC_KON_256 CARD/INSERTED (eventType=Op, Info).
        listener.onCardInserted(card);
    }

    @Override
    public void onCardRemoved(int slotNo) {
        cardList.removeBySlot(port.ctid(), slotNo).ifPresent(listener::onCardRemoved);
    }
}
