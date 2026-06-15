package de.servicehealtherx.apdu.card;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * {@link CardAttributeReader} for the Heilberufsausweis (HBA). Reads the AUT certificate from
 * {@code MF / DF.ESIGN / EF.C.HP.AUT.E256} (ECC, preferred) or {@code EF.C.HP.AUT.R2048} (RSA,
 * fallback); the cardholder name and certificate expiry are derived from it. The HBA carries no
 * KVNR, so that field is left null (FR-010).
 */
public final class HbaCardAttributeReader extends CardAttributeReader {

    @Override
    public CardType cardType() {
        return CardType.HBA;
    }

    @Override
    protected byte[] readAutCertificate(CardReaderPort port, int slotNo) {
        return readEsignAutCertificate(port, slotNo,
                GematikISO7816.FID_EF_C_HP_AUT_E256, GematikISO7816.FID_EF_C_HP_AUT_R2048);
    }
}
