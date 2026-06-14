package de.servicehealtherx.apdu.card;

/**
 * Transport-neutral hook fired when a CardObject is added to or removed from a CM_CARD_LIST
 * (US1 task T018). Lets the runtime layer publish CDI events (CARD/INSERTED, CARD/REMOVED via
 * TUC_KON_256) without {@code apdu-lib} importing CDI or any transport type (FR-046, FR-047).
 *
 * <p>Both callbacks have no-op defaults so a coordinator can be used without a listener.
 */
public interface CardLifecycleListener {

    /** A NO_OP listener for callers that do not need lifecycle notifications. */
    CardLifecycleListener NO_OP = new CardLifecycleListener() {};

    /** Invoked after a CardObject has been added to CM_CARD_LIST (TUC_KON_001). */
    default void onCardInserted(CardObject card) {
        // no-op by default
    }

    /** Invoked after a CardObject has been removed from CM_CARD_LIST. */
    default void onCardRemoved(CardObject card) {
        // no-op by default
    }
}
