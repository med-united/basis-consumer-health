package de.servicehealtherx.apdu.card;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * {@link CardAttributeReader} for the Institutionskarte (SMC-B). Reads the AUT certificate from
 * {@code MF / DF.ESIGN / EF.C.HCI.AUT.E256} (ECC, preferred) or {@code EF.C.HCI.AUT.R2048} (RSA,
 * fallback); the institution name and certificate expiry are derived from it. The SMC-B carries no
 * KVNR, so that field is left null (FR-010).
 */
public final class SmcBCardAttributeReader extends CardAttributeReader {

    @Override
    public CardType cardType() {
        return CardType.SMC_B;
    }

    @Override
    protected byte[] readAutCertificate(CardReaderPort port, int slotNo) {
        return readEsignAutCertificate(port, slotNo,
                GematikISO7816.FID_EF_C_HCI_AUT_E256, GematikISO7816.FID_EF_C_HCI_AUT_R2048);
    }
}
