package de.servicehealtherx.apdu.card;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.model.CardType;

/**
 * {@link CardAttributeReader} for the elektronische Gesundheitskarte (eGK). Reads the AUT
 * certificate from {@code MF / DF.ESIGN / EF.C.CH.AUT.E256} (ECC, preferred) or
 * {@code EF.C.CH.AUT.R2048} (RSA, fallback); the cardholder name, expiry and the KVNR
 * ("unveränderbarer Teil") are derived from it (TUC_KON_001 §2c, FR-009..FR-011).
 */
public final class EgkCardAttributeReader extends CardAttributeReader {

    @Override
    public CardType cardType() {
        return CardType.EGK;
    }

    @Override
    protected byte[] readAutCertificate(CardReaderPort port, int slotNo) {
        return readEgkAutCertificate(port, slotNo);
    }
}
