package de.servicehealtherx.apdu.card;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * Reads on-card attributes for TUC_KON_001 (US1 task T016) via a {@link CardReaderPort}.
 * Transport-neutral — it only transmits ISO 7816 APDUs through the port, so it serves both the
 * PC/SC and SICCT providers.
 *
 * <p>Implemented here:
 * <ul>
 *   <li>{@link #readIccsn} — SELECT EF.GDO (0x2F02) + READ BINARY, extract ICCSN from DO tag 0x5A
 *       (BCD), standard across card types.</li>
 *   <li>{@link #readCardVersion} — SELECT EF.Version2 + READ BINARY, tolerant TLV walk into the
 *       eight CARDVERSION sub-fields; unreadable → empty (FR-007).</li>
 *   <li>{@link #parseAutCertificate} — parse a DER AUT certificate into cardholder name
 *       (subject CN) and expiry (notAfter) (FR-009, FR-011).</li>
 * </ul>
 *
 * <p>Per FR-005/FR-007 every read is best-effort: a field that cannot be read is returned as
 * {@code null} rather than failing handle creation. For an eGK the AUT certificate is read from
 * DF.ESIGN by {@link #readEgkAutCertificate} (ECC {@code EF.C.CH.AUT.E256} preferred, RSA
 * {@code EF.C.CH.AUT.R2048} fallback) and parsed into cardholder name, expiry and KVNR.
 *
 * <h2>Card-type dispatch</h2>
 * This base class implements the read steps common to every gematik G2 card and serves as the
 * factory for the card-type-specific subclasses. {@link #forCard(CardReaderPort, int)} performs
 * TUC_KON_001 "Karte zuordnen": it first reads the three universally-present MF files
 * — {@code EF.ATR}, {@code EF.GDO} and {@code EF.Version2} (see {@link #readCommonData}) — and then
 * {@link #detectCardType(CardReaderPort, int) detects the card type} by probing which application
 * the card carries (SELECT by AID of the eGK / HBA / SMC-B application as listed in EF.DIR). The
 * matching subclass is returned:
 * <ul>
 *   <li>{@link EgkCardAttributeReader} — eGK, reads {@code C.CH.AUT} and extracts the KVNR</li>
 *   <li>{@link HbaCardAttributeReader} — HBA, reads {@code C.HP.AUT}</li>
 *   <li>{@link SmcBCardAttributeReader} — SMC-B, reads {@code C.HCI.AUT}</li>
 *   <li>{@link KvkCardAttributeReader} — legacy KVK (no DF.ESIGN AUT certificate)</li>
 *   <li>{@link UnknownCardAttributeReader} — fallback when no known application is present</li>
 * </ul>
 * Each subclass overrides {@link #cardType()} and {@link #readAutCertificate(CardReaderPort, int)};
 * the rest of the read logic is shared here. {@link #read(CardReaderPort, int)} then assembles the
 * full {@link CardObjectFactory.CardAttributes} for the detected card.
 */
public class CardAttributeReader {

    /** gematik EF.Version2 file identifier. */
    static final short FID_EF_VERSION2 = (short) 0x2F11;

    /** DO tag carrying the ICCSN in EF.GDO. */
    private static final int TAG_ICCSN = 0x5A;

    /** The card type this reader handles. Overridden by each card-type subclass. */
    public CardType cardType() {
        return CardType.UNKNOWN;
    }

    /**
     * TUC_KON_001 "Karte zuordnen": read the universally-present MF files ({@code EF.ATR},
     * {@code EF.GDO}, {@code EF.Version2}), determine the card type from the application the card
     * carries, and return the matching card-type-specific reader. Never fails: an unidentifiable
     * card yields an {@link UnknownCardAttributeReader} so handle creation can still proceed
     * (FR-005).
     */
    public static CardAttributeReader forCard(CardReaderPort port, int slotNo) {
        return readerFor(detectCardType(port, slotNo));
    }

    /** The card-type reader for an already-resolved {@link CardType}. */
    public static CardAttributeReader readerFor(CardType type) {
        return switch (type) {
            case EGK -> new EgkCardAttributeReader();
            case HBA, HBAX -> new HbaCardAttributeReader();
            case SMC_B -> new SmcBCardAttributeReader();
            case KVK -> new KvkCardAttributeReader();
            case UNKNOWN -> new UnknownCardAttributeReader();
        };
    }

    /**
     * Detect the card type of the card in {@code slotNo}. First reads the three MF files every
     * gematik G2 card exposes ({@code EF.ATR}, {@code EF.GDO}, {@code EF.Version2}); a card that
     * does not answer these at all is treated as {@link CardType#UNKNOWN}. Otherwise the type is
     * resolved by probing which application is present (SELECT by AID, EF.DIR DO '4F'):
     * eGK → HBA → SMC-B. Suitable for use as a {@link CardPresenceCoordinator.CardTypeResolver}.
     */
    public static CardType detectCardType(CardReaderPort port, int slotNo) {
        CommonCardData common = new CardAttributeReader().readCommonData(port, slotNo);
        if (common.isUnreadable()) {
            return CardType.UNKNOWN;
        }
        if (selectApplication(port, slotNo, GematikISO7816.AID_EGK)) {
            return CardType.EGK;
        }
        if (selectApplication(port, slotNo, GematikISO7816.AID_HBA)) {
            return CardType.HBA;
        }
        if (selectApplication(port, slotNo, GematikISO7816.AID_SMC_B)) {
            return CardType.SMC_B;
        }
        return CardType.UNKNOWN;
    }

    /** Whether the application identified by {@code aid} can be SELECTed in the slot. */
    private static boolean selectApplication(CardReaderPort port, int slotNo, byte[] aid) {
        try {
            CommandAPDU select = new CommandAPDU(
                    GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                    GematikISO7816.SELECT_BY_DF_NAME, 0x0C, aid);
            return port.transmit(slotNo, select).getSW() == GematikISO7816.SW_SUCCESS;
        } catch (CardTransportException | RuntimeException e) {
            return false;
        }
    }

    /** The three MF files read before the card type is known. Each field is null if unreadable. */
    public record CommonCardData(byte[] efAtr, String iccsn, CardVersionInfo cardVersion) {
        /** True when none of the common files could be read (likely no/foreign card). */
        public boolean isUnreadable() {
            return efAtr == null && iccsn == null
                    && (cardVersion == null || cardVersion.equals(CardVersionInfo.empty()));
        }
    }

    /** Read {@code EF.ATR}, {@code EF.GDO} (ICCSN) and {@code EF.Version2} — common to all G2 cards. */
    public CommonCardData readCommonData(CardReaderPort port, int slotNo) {
        return new CommonCardData(readEfAtr(port, slotNo), readIccsn(port, slotNo),
                readCardVersion(port, slotNo));
    }

    /** SELECT EF.ATR + READ BINARY; raw answer-to-reset attributes, or {@code null} if unreadable. */
    public byte[] readEfAtr(CardReaderPort port, int slotNo) {
        try {
            return selectAndReadBinary(port, slotNo, GematikISO7816.FID_EF_ATR);
        } catch (CardTransportException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Read all attributes for the card this reader handles: ICCSN + card version, plus — when the
     * card type carries one — the AUT certificate (cardholder name, expiry, and KVNR for eGK),
     * obtained via the subclass's {@link #readAutCertificate(CardReaderPort, int)}.
     */
    public CardObjectFactory.CardAttributes read(CardReaderPort port, int slotNo) {
        return read(port, slotNo, cardType(), readAutCertificate(port, slotNo));
    }

    /**
     * Read the DER-encoded AUT certificate for this card type, or {@code null} if the card type has
     * none / it is unreadable. Overridden by the eGK, HBA and SMC-B subclasses.
     */
    protected byte[] readAutCertificate(CardReaderPort port, int slotNo) {
        return null;
    }

    /**
     * Read the DER-encoded C.AUT certificate (C.CH.AUT / C.HP.AUT / C.HCI.AUT) for the card of the
     * given {@code type} from DF.ESIGN; ECC preferred, RSA fallback. READ BINARY on these files is
     * access condition ALWAYS, so no PIN is required. Returns {@code null} if none could be read.
     */
    public static byte[] readAutCertificate(CardReaderPort port, int slotNo, CardType type) {
        return readerFor(type).readAutCertificate(port, slotNo);
    }

    /**
     * Read ICCSN + card version from the card and, for an eGK, the AUT certificate from DF.ESIGN
     * (cardholder name, expiry, KVNR). The cert is acquired through the per-type subclass reader
     * ({@link #readerFor(CardType)}), so passing {@code type=HBA}/{@code SMC_B} now reads their
     * {@code C.HP.AUT} / {@code C.HCI.AUT} certificate too.
     */
    public CardObjectFactory.CardAttributes read(CardReaderPort port, int slotNo, CardType type) {
        byte[] autCertDer = readerFor(type).readAutCertificate(port, slotNo);
        return read(port, slotNo, type, autCertDer);
    }

    /**
     * Read ICCSN + card version from the card and, if {@code autCertDer} is supplied, derive
     * cardholder name / expiry (and KVNR for eGK) from it.
     */
    public CardObjectFactory.CardAttributes read(CardReaderPort port, int slotNo, CardType type, byte[] autCertDer) {
        String iccsn = readIccsn(port, slotNo);
        CardVersionInfo version = readCardVersion(port, slotNo);

        String cardHolderName = null;
        LocalDate expiry = null;
        String kvnr = null;
        if (autCertDer != null) {
            X509Certificate cert = toCertificate(autCertDer);
            if (cert != null) {
                cardHolderName = commonName(cert.getSubjectX500Principal().getName());
                expiry = cert.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                // KVNR is only meaningful for eGK and is the "unveränderbarer Teil der KVNR"
                // carried in the C.CH.AUT subject (TUC_KON_001 §2c).
                if (type == CardType.EGK) {
                    kvnr = extractKvnr(cert.getSubjectX500Principal().getName());
                }
            }
        }
        return new CardObjectFactory.CardAttributes(iccsn, version, cardHolderName, kvnr, expiry);
    }

    /**
     * Read the eGK AUT certificate from DF.ESIGN for TUC_KON_001 §2c. Selects DF.ESIGN by its AID,
     * then reads {@code EF.C.CH.AUT.E256} (ECC, preferred) and falls back to
     * {@code EF.C.CH.AUT.R2048} (RSA) when the ECC certificate is absent or unreadable
     * ("Wenn vorhanden, ist das ECC-Zertifikat zu verwenden, andernfalls das RSA-Zertifikat").
     * READ BINARY on these files is access condition ALWAYS (no PIN). Returns DER bytes, or
     * {@code null} if no certificate could be read (FR-005: must not fail handle creation).
     */
    public byte[] readEgkAutCertificate(CardReaderPort port, int slotNo) {
        return readEsignAutCertificate(port, slotNo,
                GematikISO7816.FID_EF_C_CH_AUT_E256, GematikISO7816.FID_EF_C_CH_AUT_R2048);
    }

    /**
     * Read an AUT certificate from DF.ESIGN, preferring the ECC certificate ({@code eccFid}) and
     * falling back to the RSA certificate ({@code rsaFid}) when the ECC file is absent or unreadable
     * ("Wenn vorhanden, ist das ECC-Zertifikat zu verwenden, andernfalls das RSA-Zertifikat").
     * Shared by the eGK ({@code C.CH.AUT}), HBA ({@code C.HP.AUT}) and SMC-B ({@code C.HCI.AUT})
     * subclasses; READ BINARY on these files is access condition ALWAYS (no PIN). Returns DER bytes,
     * or {@code null} if no certificate could be read (FR-005: must not fail handle creation).
     */
    protected byte[] readEsignAutCertificate(CardReaderPort port, int slotNo, short eccFid, short rsaFid) {
        try {
            CommandAPDU selectDf = new CommandAPDU(
                    GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                    GematikISO7816.SELECT_BY_DF_NAME, 0x0C, GematikISO7816.AID_DF_ESIGN);
            ResponseAPDU dfResp = port.transmit(slotNo, selectDf);
            if (dfResp.getSW() != GematikISO7816.SW_SUCCESS) {
                return null;
            }
            byte[] ecc = readCertificateFile(port, slotNo, eccFid);
            return ecc != null ? ecc : readCertificateFile(port, slotNo, rsaFid);
        } catch (CardTransportException | RuntimeException e) {
            return null;
        }
    }

    /** SELECT EF.GDO + READ BINARY, return ICCSN digit string, or {@code null} if unreadable. */
    public String readIccsn(CardReaderPort port, int slotNo) {
        try {
            byte[] gdo = selectAndReadBinary(port, slotNo, GematikISO7816.FID_EF_GDO);
            byte[] iccsn = extractDo(gdo, TAG_ICCSN);
            return iccsn == null ? null : bcdToDigits(iccsn);
        } catch (CardTransportException | RuntimeException e) {
            return null; // FR-005: unreadable ICCSN must not fail handle creation
        }
    }

    /** SELECT EF.Version2 + READ BINARY, tolerant parse into the 8 CARDVERSION sub-fields. */
    public CardVersionInfo readCardVersion(CardReaderPort port, int slotNo) {
        try {
            byte[] data = selectAndReadBinary(port, slotNo, FID_EF_VERSION2);
            String[] v = new String[8];
            int idx = 0;
            int pos = 0;
            // Tolerant TLV walk: collect primitive value bytes (skip constructed wrappers) as
            // dotted version strings, in order, into the eight fields.
            while (pos < data.length && idx < v.length) {
                int tag = data[pos++] & 0xFF;
                if (pos >= data.length) {
                    break;
                }
                int len = data[pos++] & 0xFF;
                if (len < 0 || pos + len > data.length) {
                    break;
                }
                boolean constructed = (tag & 0x20) != 0;
                if (!constructed) {
                    v[idx++] = toVersionString(data, pos, len);
                }
                pos += len;
            }
            return new CardVersionInfo(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]);
        } catch (CardTransportException | RuntimeException e) {
            return CardVersionInfo.empty();
        }
    }

    /** Parse a DER-encoded AUT certificate into cardholder name (CN) and expiry. */
    public CertInfo parseAutCertificate(byte[] der) {
        X509Certificate cert = toCertificate(der);
        if (cert == null) {
            return null;
        }
        String cn = commonName(cert.getSubjectX500Principal().getName());
        LocalDate notAfter = cert.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return new CertInfo(cn, notAfter);
    }

    /** Cardholder name + certificate expiry derived from an AUT certificate. */
    public record CertInfo(String cardHolderName, LocalDate certExpirationDate) {}

    private static X509Certificate toCertificate(byte[] der) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    // --- ISO 7816 helpers ---------------------------------------------------------------------

    private byte[] selectAndReadBinary(CardReaderPort port, int slotNo, short fid) throws CardTransportException {
        // EF.ATR/EF.GDO/EF.Version2 are MF-level transparent files. SELECT BY FILE ID (P1=0x02)
        // resolves the EF relative to the *currently selected* DF, so if an application DF
        // (e.g. DF.ESIGN, selected while reading the AUT certificate) is active, the card answers
        // SW=6A82 "file not found". Re-select the MF first so these global EFs are always reachable
        // — otherwise a successfully type-detected card loses its ICCSN and is downgraded to
        // CardType.UNKNOWN (see CardObjectFactory#readAndCreate).
        selectMf(port, slotNo);
        byte[] fidBytes = {(byte) (fid >> 8), (byte) (fid & 0xFF)};
        CommandAPDU select = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fidBytes);
        ResponseAPDU selResp = port.transmit(slotNo, select);
        if (selResp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardTransportException("SELECT " + String.format("%04X", fid & 0xFFFF)
                    + " failed: SW=" + Integer.toHexString(selResp.getSW()));
        }
        // Ne=256 (Le byte 0x00) — an ISO case-2 READ BINARY. Passing Ne=0 would emit a case-1
        // APDU with no Le byte (00 B0 00 00), which gematik G2 cards reject with SW=6700.
        CommandAPDU readBinary = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY, 0x00, 0x00, 256);
        ResponseAPDU readResp = port.transmit(slotNo, readBinary);
        if (readResp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardTransportException("READ BINARY failed: SW=" + Integer.toHexString(readResp.getSW()));
        }
        return readResp.getData();
    }

    /**
     * SELECT the Master File (3F00) so a subsequent SELECT-by-file-id resolves MF-level EFs
     * regardless of which application DF is currently active. Best-effort: the SW is not checked
     * here — the caller's SELECT of the target EF is the real gate, and a card that rejects an
     * explicit MF select but already has the MF current will still succeed there.
     */
    private void selectMf(CardReaderPort port, int slotNo) throws CardTransportException {
        byte[] mf = {(byte) (GematikISO7816.FID_MF >> 8), (byte) (GematikISO7816.FID_MF & 0xFF)};
        CommandAPDU selectMf = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_FIRST_OCCURRENCE, 0x0C, mf);
        port.transmit(slotNo, selectMf);
    }

    /** SELECT a transparent certificate EF by file id and READ BINARY its whole content. */
    private byte[] readCertificateFile(CardReaderPort port, int slotNo, short fid) {
        try {
            byte[] fidBytes = {(byte) (fid >> 8), (byte) (fid & 0xFF)};
            CommandAPDU select = new CommandAPDU(
                    GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                    GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fidBytes);
            if (port.transmit(slotNo, select).getSW() != GematikISO7816.SW_SUCCESS) {
                return null;
            }
            return readBinaryFull(port, slotNo);
        } catch (CardTransportException | RuntimeException e) {
            return null;
        }
    }

    /** SW returned when READ BINARY reaches end-of-file but still delivered the trailing bytes. */
    private static final int SW_END_OF_FILE = 0x6282;

    /**
     * READ BINARY a transparent file in ≤256-byte blocks (short offset in P1/P2) until the leading
     * ASN.1 SEQUENCE is fully read. Returns {@code null} if the content is not a DER certificate
     * (does not start with a SEQUENCE) — guarding against reading non-certificate files.
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

    /** eGK KVNR (unveränderbarer Teil): one uppercase letter followed by nine digits. */
    private static final Pattern KVNR_PATTERN = Pattern.compile("[A-Z]\\d{9}");

    /**
     * Extract the "unveränderbarer Teil der KVNR" from an eGK C.CH.AUT subject DN. The 10-character
     * insurant id is carried in an {@code OU} attribute; if no OU matches the KVNR pattern, any
     * subject attribute value matching it is accepted. Returns {@code null} if none is found.
     */
    static String extractKvnr(String subjectDn) {
        try {
            LdapName dn = new LdapName(subjectDn);
            for (Rdn rdn : dn.getRdns()) {
                String value = String.valueOf(rdn.getValue());
                if (rdn.getType().equalsIgnoreCase("OU") && isKvnr(value)) {
                    return value;
                }
            }
            for (Rdn rdn : dn.getRdns()) {
                String value = String.valueOf(rdn.getValue());
                if (isKvnr(value)) {
                    return value;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static boolean isKvnr(String value) {
        return value != null && KVNR_PATTERN.matcher(value).matches();
    }

    /** Extract the value of a single-byte primitive/constructed DO tag from a flat TLV buffer. */
    static byte[] extractDo(byte[] tlv, int tag) {
        int pos = 0;
        while (pos + 1 < tlv.length) {
            int t = tlv[pos++] & 0xFF;
            int len = tlv[pos++] & 0xFF;
            if (len < 0 || pos + len > tlv.length) {
                return null;
            }
            if (t == tag) {
                byte[] value = new byte[len];
                System.arraycopy(tlv, pos, value, 0, len);
                return value;
            }
            pos += len;
        }
        return null;
    }

    /** Decode BCD-packed bytes into a digit string, stopping at an 'F' filler nibble. */
    static String bcdToDigits(byte[] bcd) {
        StringBuilder sb = new StringBuilder(bcd.length * 2);
        for (byte b : bcd) {
            int hi = (b >> 4) & 0x0F;
            int lo = b & 0x0F;
            if (hi == 0x0F) {
                break;
            }
            sb.append((char) ('0' + hi));
            if (lo == 0x0F) {
                break;
            }
            sb.append((char) ('0' + lo));
        }
        return sb.toString();
    }

    private static String toVersionString(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(data[off + i] & 0xFF);
        }
        return sb.toString();
    }

    private static String commonName(String dn) {
        try {
            LdapName ldap = new LdapName(dn);
            return ldap.getRdns().stream()
                    .filter(r -> r.getType().equalsIgnoreCase("CN"))
                    .map(r -> String.valueOf(r.getValue()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
