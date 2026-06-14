package de.servicehealtherx.apdu.card;

/**
 * Implemented by every CryptoProvider that owns a CM_CARD_LIST (FR-062) — currently the PC/SC and
 * SICCT providers. Lets the card service discover each provider's own list and aggregate them into
 * the unified GetCards view (FR-064) without coupling to a concrete transport.
 */
public interface CardListProvider {

    /** This provider's own CM_CARD_LIST instance (never shared with another provider). */
    CmCardList cmCardList();
}
