package de.servicehealtherx.apdu.card;

import de.servicehealtherx.apdu.model.CardType;

/**
 * {@link CardAttributeReader} for the legacy Krankenversichertenkarte (KVK). The KVK is a memory
 * card without a DF.ESIGN application, so it has no AUT certificate; only the ICCSN and card
 * version (where present) are read by the base class. Retained for completeness — auto-detection
 * via {@link CardAttributeReader#detectCardType} does not produce KVK (no health-card application),
 * so this reader is used only when a KVK is resolved explicitly.
 */
public final class KvkCardAttributeReader extends CardAttributeReader {

    @Override
    public CardType cardType() {
        return CardType.KVK;
    }
}
