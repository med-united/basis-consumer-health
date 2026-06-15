package de.servicehealtherx.apdu.card;

import de.servicehealtherx.apdu.model.CardType;

/**
 * {@link CardAttributeReader} fallback for a card whose type could not be determined — no known
 * gematik application (eGK / HBA / SMC-B) is present, or the card did not answer the common MF
 * file reads. Reads only the base attributes (ICCSN, card version where available) and supplies no
 * AUT certificate, so handle creation still proceeds with {@code CardType=UNKNOWN} (TUC_KON_001
 * Fehlerfall, FR-005).
 */
public final class UnknownCardAttributeReader extends CardAttributeReader {

    @Override
    public CardType cardType() {
        return CardType.UNKNOWN;
    }
}
