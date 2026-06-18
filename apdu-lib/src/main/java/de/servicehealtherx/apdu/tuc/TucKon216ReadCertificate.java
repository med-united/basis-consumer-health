package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;
import java.util.Map;

/**
 * TUC_KON_216 "LeseZertifikat" — generates the APDU steps to read an X.509 certificate file from a
 * card: SELECT the certificate EF, then READ BINARY its contents (gemSpec_Kon §4.1.9.5.2).
 *
 * <p>The target EF is determined from the card type, the certificate reference and the crypto
 * algorithm per <strong>TAB_KON_858</strong> (see {@link #fileIdentifierFor}). The certificate files
 * live inside {@code DF.ESIGN}, which the caller must SELECT before executing these steps. The
 * generated READ BINARY is the canonical single-block read; a runtime executor that needs the whole
 * (possibly multi-kibibyte) certificate iterates READ BINARY by offset itself.
 */
public final class TucKon216ReadCertificate {

    /**
     * Generate SELECT-EF + READ-BINARY steps for an explicit certificate file identifier.
     */
    public TucGenerationResult generateReadCertificate(CardSession cardSession, short fileIdentifier) {
        byte[] fid = new byte[]{(byte) (fileIdentifier >> 8), (byte) (fileIdentifier & 0xFF)};
        var select = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fid);
        var selectStep = new GeneratedApduStep(select, ExpectedStatusSet.successOnly(),
                "SELECT EF " + String.format("%04X", fileIdentifier & 0xFFFF));

        var readBinary = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY, 0x00, 0x00, 256);
        var readStep = new GeneratedApduStep(readBinary, ExpectedStatusSet.successOnly(), "READ BINARY CERTIFICATE");

        return TucGenerationResult.of(List.of(selectStep, readStep),
                Map.of("certFileId", fileIdentifier));
    }

    /**
     * Generate the read steps for a certificate addressed by {@code certRef} and crypto algorithm,
     * resolving the file identifier from the session's card type via TAB_KON_858.
     *
     * @param ecc {@code true} for the ECC certificate, {@code false} for the RSA certificate
     */
    public TucGenerationResult generateReadCertificate(CardSession cardSession, CertificateRef certRef, boolean ecc) {
        return generateReadCertificate(cardSession, fileIdentifierFor(cardSession.cardType(), certRef, ecc));
    }

    /**
     * Resolve the certificate EF file identifier inside {@code DF.ESIGN} per TAB_KON_858, for the
     * given card type, certificate reference and crypto algorithm.
     *
     * <p>Reading certificates from an {@code eGK} is not supported by ReadCardCertificate
     * ("DARF das Lesen von Zertifikaten der eGK NICHT unterstützen") and is rejected here.
     *
     * @throws IllegalArgumentException if the combination has no defined certificate object
     */
    public static short fileIdentifierFor(CardType cardType, CertificateRef certRef, boolean ecc) {
        return switch (certRef) {
            case C_AUT -> switch (cardType) {
                case HBA, HBAX -> ecc ? GematikISO7816.FID_EF_C_HP_AUT_E256 : GematikISO7816.FID_EF_C_HP_AUT_R2048;
                case SMC_B -> ecc ? GematikISO7816.FID_EF_C_HCI_AUT_E256 : GematikISO7816.FID_EF_C_HCI_AUT_R2048;
                default -> throw unsupported(cardType, certRef);
            };
            case C_SIG -> switch (cardType) {
                case SMC_B -> ecc ? GematikISO7816.FID_EF_C_HCI_OSIG_E256 : GematikISO7816.FID_EF_C_HCI_OSIG_R2048;
                default -> throw unsupported(cardType, certRef);
            };
            // C.HP.ENC / C.HP.QES are HBA-only and addressed by SFI (see CardCertificateReader),
            // not by a full file identifier through this FID resolver.
            case C_ENC, C_QES -> throw unsupported(cardType, certRef);
        };
    }

    private static IllegalArgumentException unsupported(CardType cardType, CertificateRef certRef) {
        return new IllegalArgumentException(
                "No " + certRef.id() + " certificate object defined for card type " + cardType + " (TAB_KON_858)");
    }
}
