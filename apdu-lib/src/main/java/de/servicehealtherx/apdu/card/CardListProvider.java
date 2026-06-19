package de.servicehealtherx.apdu.card;

import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;

/**
 * Implemented by every CryptoProvider that owns a CM_CARD_LIST (FR-062) — currently the PC/SC and
 * SICCT providers. Lets the card service discover each provider's own list and aggregate them into
 * the unified GetCards view (FR-064) without coupling to a concrete transport.
 */
public interface CardListProvider {

    /** This provider's own CM_CARD_LIST instance (never shared with another provider). */
    CmCardList cmCardList();

    /**
     * Resolver for the live {@link de.servicehealtherx.apdu.card.transport.CardReaderPort} of the
     * terminals this provider owns. Aggregated across providers so a unified read (e.g. ReadVSD) can
     * turn any card's {@code ctid} into the port that transmits to it, regardless of which transport
     * owns the terminal. Defaults to {@link CardReaderPortResolver#NONE} for providers that bind no
     * ports.
     */
    default CardReaderPortResolver portResolver() {
        return CardReaderPortResolver.NONE;
    }
}
