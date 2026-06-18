package de.servicehealtherx.apdu.card;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.AuthState;
import de.servicehealtherx.apdu.model.CardSession;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.CardVersion;
import de.servicehealtherx.apdu.model.CertificateRef;
import de.servicehealtherx.apdu.model.GematikISO7816;
import de.servicehealtherx.apdu.model.GeneratedApduStep;
import de.servicehealtherx.apdu.tuc.TucKon216ReadCertificate;

/**
 * Executes the card-backed certificate read of gemSpec_Kon ReadCardCertificate (TUC_KON_216
 * "LeseZertifikat") against a {@link CardReaderPort}. Transport-neutral: the same reader serves both
 * the PC/SC and SICCT providers (research D9), since all transport-specific transmission happens
 * behind the port.
 *
 * <p>The read sequence is:
 * <ol>
 *   <li>SELECT {@code DF.ESIGN} (the certificate files live in this application);</li>
 *   <li>generate the SELECT-EF step via {@link TucKon216ReadCertificate}, resolving the file
 *       identifier from card type + certificate reference + crypto algorithm (TAB_KON_858);</li>
 *   <li>SELECT the certificate EF, then READ BINARY the whole transparent file in ≤256-byte blocks.</li>
 * </ol>
 *
 * <p>Reading certificates from an {@code eGK} is forbidden by the operation
 * ("DARF das Lesen von Zertifikaten der eGK NICHT unterstützen") and is rejected before any APDU is
 * sent. Unlike TUC_KON_001's best-effort attribute read, this is an explicit client operation: a
 * missing or unreadable certificate raises {@link CardCertificateException} (gemSpec error 4258,
 * "&lt;Crypt&gt;Zertifikat nicht vorhanden auf Karte") rather than returning {@code null}.
 */
public final class CardCertificateReader {

    /** SW returned when READ BINARY reaches end-of-file but still delivered the trailing bytes. */
    private static final int SW_END_OF_FILE = 0x6282;

    private final TucKon216ReadCertificate tuc = new TucKon216ReadCertificate();

    /**
     * Read the certificate addressed by {@code certRef} for the given crypto algorithm from the card
     * in {@code slotNo}.
     *
     * @param ecc {@code true} for the ECC certificate, {@code false} for the RSA certificate
     * @return the parsed X.509 certificate
     * @throws CardCertificateException if the card type is unsupported, the certificate is absent, or
     *                                  the bytes read are not a valid X.509 certificate
     */
    public X509Certificate readCertificate(CardReaderPort port, int slotNo, String cardHandle,
                                           CardType cardType, CertificateRef certRef, boolean ecc) {
        if (cardType == CardType.EGK) {
            throw new CardCertificateException("Reading certificates from an eGK is not permitted (gemSpec_Kon 4090)");
        }
        // The HBA addresses its certificate EFs by short file identifier in the relevant DF
        // (C.HP.AUT/C.HP.ENC in DF.ESIGN, C.HP.QES in DF.QES) — the access path verified against the
        // real gematik G2.1 HBA. SMC-B keeps the full-file-id path (TAB_KON_858) below.
        SfiLocation sfi = hbaSfiLocation(cardType, certRef);
        if (sfi != null) {
            return readCertificateBySfi(port, slotNo, cardHandle, certRef, sfi);
        }
        short fid = TucKon216ReadCertificate.fileIdentifierFor(cardType, certRef, ecc);
        try {
            selectEsign(port, slotNo);

            CardSession session = new CardSession(cardHandle, cardType, CardVersion.GENERATION_2_1, new AuthState());
            GeneratedApduStep selectEf = tuc.generateReadCertificate(session, fid).steps().get(0);
            ResponseAPDU selResp = port.transmit(slotNo, selectEf.command());
            if (selResp.getSW() != GematikISO7816.SW_SUCCESS) {
                throw new CardCertificateException(certRef.id() + " certificate not present on card " + cardHandle
                        + " (SELECT " + String.format("%04X", fid & 0xFFFF)
                        + " → SW=" + Integer.toHexString(selResp.getSW()) + ")");
            }

            byte[] der = readBinaryFull(port, slotNo);
            if (der == null) {
                throw new CardCertificateException(
                        certRef.id() + " file on card " + cardHandle + " is not a readable X.509 certificate");
            }
            return parse(der);
        } catch (CardTransportException e) {
            throw new CardCertificateException(
                    "Transport failure reading " + certRef.id() + " from card " + cardHandle + ": " + e.getMessage(), e);
        }
    }

    /** A certificate EF addressed by its containing DF (by AID) and its short file identifier. */
    private record SfiLocation(byte[] dfAid, int sfi) {}

    /**
     * The HBA's certificate EF location (DF AID + SFI) for a reference, or {@code null} when the
     * card/reference must be read through the full-file-id path instead (SMC-B). Reading from an HBA
     * uses SFIs verified against the real card: C.AUT/C.ENC live in DF.ESIGN, C.QES in DF.QES.
     */
    private static SfiLocation hbaSfiLocation(CardType cardType, CertificateRef certRef) {
        if (cardType != CardType.HBA && cardType != CardType.HBAX) {
            return null;
        }
        return switch (certRef) {
            case C_AUT -> new SfiLocation(GematikISO7816.AID_DF_ESIGN, GematikISO7816.SFI_C_HP_AUT);
            case C_ENC -> new SfiLocation(GematikISO7816.AID_DF_ESIGN, GematikISO7816.SFI_C_HP_ENC);
            case C_QES -> new SfiLocation(GematikISO7816.AID_DF_QES, GematikISO7816.SFI_C_HP_QES);
            case C_SIG -> throw new CardCertificateException(
                    "No C.SIG certificate object defined for an HBA (TAB_KON_858)");
        };
    }

    /** SELECT the certificate's DF by AID, then READ BINARY the whole transparent file via its SFI. */
    private X509Certificate readCertificateBySfi(CardReaderPort port, int slotNo, String cardHandle,
                                                 CertificateRef certRef, SfiLocation loc) {
        try {
            CommandAPDU selectDf = new CommandAPDU(
                    GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                    GematikISO7816.SELECT_BY_DF_NAME, 0x0C, loc.dfAid());
            ResponseAPDU dfResp = port.transmit(slotNo, selectDf);
            if (dfResp.getSW() != GematikISO7816.SW_SUCCESS) {
                throw new CardCertificateException(certRef.id() + " not present on card " + cardHandle
                        + " (SELECT DF → SW=" + Integer.toHexString(dfResp.getSW()) + ")");
            }
            byte[] der = readBinaryBySfi(port, slotNo, loc.sfi());
            if (der == null) {
                throw new CardCertificateException(
                        certRef.id() + " file on card " + cardHandle + " is not a readable X.509 certificate");
            }
            return parse(der);
        } catch (CardTransportException e) {
            throw new CardCertificateException(
                    "Transport failure reading " + certRef.id() + " from card " + cardHandle + ": " + e.getMessage(), e);
        }
    }

    /**
     * READ BINARY a transparent file addressed by short file identifier. The first READ BINARY carries
     * the SFI in P1 (bit 8 set, bits 1-5 = SFI), selecting and reading the EF in one step; further
     * blocks are read by 15-bit offset. Returns {@code null} if the content is not a DER certificate.
     */
    private byte[] readBinaryBySfi(CardReaderPort port, int slotNo, int sfi) throws CardTransportException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = -1;
        int offset = 0;
        boolean first = true;
        while (offset < 0x8000) {
            int p1 = first ? (0x80 | (sfi & 0x1F)) : ((offset >> 8) & 0x7F);
            int p2 = first ? 0x00 : (offset & 0xFF);
            CommandAPDU read = new CommandAPDU(
                    GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY, p1, p2, 256);
            ResponseAPDU resp = port.transmit(slotNo, read);
            int sw = resp.getSW();
            byte[] chunk = resp.getData();
            if ((sw != GematikISO7816.SW_SUCCESS && sw != SW_END_OF_FILE) || chunk.length == 0) {
                break;
            }
            out.write(chunk, 0, chunk.length);
            if (total < 0) {
                total = derTotalLength(out.toByteArray());
                if (total < 0) {
                    return null; // not an X.509 certificate
                }
            }
            offset += chunk.length;
            first = false;
            if (out.size() >= total || sw == SW_END_OF_FILE) {
                break;
            }
        }
        if (total <= 0 || out.size() < total) {
            return null;
        }
        return Arrays.copyOf(out.toByteArray(), total);
    }

    private void selectEsign(CardReaderPort port, int slotNo) throws CardTransportException {
        CommandAPDU selectDf = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_DF_NAME, 0x0C, GematikISO7816.AID_DF_ESIGN);
        ResponseAPDU resp = port.transmit(slotNo, selectDf);
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardCertificateException(
                    "SELECT DF.ESIGN failed: SW=" + Integer.toHexString(resp.getSW()));
        }
    }

    /**
     * READ BINARY a transparent file in ≤256-byte blocks (short offset in P1/P2) until the leading
     * ASN.1 SEQUENCE is fully read. Returns {@code null} if the content does not start with a DER
     * SEQUENCE (guarding against reading a non-certificate file).
     */
    private byte[] readBinaryFull(CardReaderPort port, int slotNo) throws CardTransportException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        int total = -1;
        while (offset < 0x8000) {
            CommandAPDU read = new CommandAPDU(
                    GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY,
                    (offset >> 8) & 0x7F, offset & 0xFF, 256); // Ne=256 → Le=0x00; Ne=0 omits Le (SW=6700)
            ResponseAPDU resp = port.transmit(slotNo, read);
            int sw = resp.getSW();
            byte[] chunk = resp.getData();
            if ((sw != GematikISO7816.SW_SUCCESS && sw != SW_END_OF_FILE) || chunk.length == 0) {
                break;
            }
            out.write(chunk, 0, chunk.length);
            if (total < 0) {
                total = derTotalLength(out.toByteArray());
                if (total < 0) {
                    return null; // not an X.509 certificate
                }
            }
            offset += chunk.length;
            if (out.size() >= total || sw == SW_END_OF_FILE) {
                break;
            }
        }
        if (total <= 0 || out.size() < total) {
            return null;
        }
        return Arrays.copyOf(out.toByteArray(), total);
    }

    /** Total length (header + content) of the leading DER TLV, or -1 if it is not a SEQUENCE. */
    static int derTotalLength(byte[] der) {
        if (der.length < 2 || (der[0] & 0xFF) != 0x30) {
            return -1;
        }
        int first = der[1] & 0xFF;
        if (first < 0x80) {
            return 2 + first;
        }
        int n = first & 0x7F;
        if (n == 0 || n > 4 || der.length < 2 + n) {
            return -1;
        }
        int len = 0;
        for (int i = 0; i < n; i++) {
            len = (len << 8) | (der[2 + i] & 0xFF);
        }
        return 2 + n + len;
    }

    private static X509Certificate parse(byte[] der) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new CardCertificateException("Failed to parse certificate read from card: " + e.getMessage(), e);
        }
    }
}
