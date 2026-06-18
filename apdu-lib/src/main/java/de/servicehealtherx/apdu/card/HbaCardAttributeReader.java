package de.servicehealtherx.apdu.card;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * {@link CardAttributeReader} for the Heilberufsausweis (HBA). Reads the AUT certificate from
 * {@code MF / DF.ESIGN / EF.C.HP.AUT}; the cardholder name and certificate expiry are derived from
 * it. The HBA carries no KVNR, so that field is left null (FR-010).
 *
 * <p>The certificate is first attempted by full file identifier ({@code EF.C.HP.AUT.E256}, ECC
 * preferred, then {@code EF.C.HP.AUT.R2048} RSA), and — when neither answers — by short file
 * identifier ({@code SFI 0x01} in {@code DF.ESIGN}), the access path verified against the real
 * gematik G2.1 HBA (reference impl {@code ehba-cades-qes-sign}). Without the SFI fallback the
 * cardholder name is missing from {@code GetCards} for HBAs that do not expose the AUT EF by file id.
 */
public final class HbaCardAttributeReader extends CardAttributeReader {

    @Override
    public CardType cardType() {
        return CardType.HBA;
    }

    @Override
    protected byte[] readAutCertificate(CardReaderPort port, int slotNo) {
        byte[] der = readEsignAutCertificate(port, slotNo,
                GematikISO7816.FID_EF_C_HP_AUT_E256, GematikISO7816.FID_EF_C_HP_AUT_R2048);
        if (der != null) {
            return der;
        }
        return readCertificateBySfi(port, slotNo,
                GematikISO7816.AID_DF_ESIGN, GematikISO7816.SFI_C_HP_AUT);
    }
}
