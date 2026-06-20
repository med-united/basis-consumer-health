package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Arrays;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.transport.FakeCardReaderPort;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * Unit tests for {@link CardAttributeReader} (US1 task T014; FR-005, FR-007, FR-009, FR-011).
 * Uses a {@link FakeCardReaderPort} returning canned APDU responses — no hardware.
 */
class CardAttributeReaderTest {

    private final CardAttributeReader reader = new CardAttributeReader();

    /** Append SW 9000 to a data payload to form a ResponseAPDU. */
    private static ResponseAPDU ok(byte[] data) {
        byte[] apdu = new byte[data.length + 2];
        System.arraycopy(data, 0, apdu, 0, data.length);
        apdu[data.length] = (byte) 0x90;
        apdu[data.length + 1] = 0x00;
        return new ResponseAPDU(apdu);
    }

    @Test
    void test_FR_005_read_iccsn_decodes_bcd_from_ef_gdo_tag_5A() {
        // EF.GDO: DO tag 0x5A, len 10, BCD ICCSN 80276001011234567890
        byte[] gdo = {0x5A, 0x0A,
                (byte) 0x80, 0x27, 0x60, 0x01, 0x01, 0x12, 0x34, 0x56, 0x78, (byte) 0x90};
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader-1");
        port.setCannedResponse(ok(gdo));

        assertEquals("80276001011234567890", reader.readIccsn(port, 1));
    }

    @Test
    void test_FR_005_unreadable_iccsn_returns_null_not_failure() {
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader-1");
        port.setFailTransmit(true);
        assertNull(reader.readIccsn(port, 1));
    }

    @Test
    void test_FR_007_read_card_version_tolerant_tlv_walk() {
        // Two primitive version DOs → "4.3.0" and "5.2.0"
        byte[] efVersion = {
                (byte) 0xC0, 0x03, 0x04, 0x03, 0x00,
                (byte) 0xC1, 0x03, 0x05, 0x02, 0x00};
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader-1");
        port.setCannedResponse(ok(efVersion));

        CardVersionInfo v = reader.readCardVersion(port, 1);
        assertEquals("4.3.0", v.cosVersion());
        assertEquals("5.2.0", v.objectSystemVersion());
    }

    @Test
    void test_FR_007_unreadable_version_yields_empty_not_failure() {
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader-1");
        port.setFailTransmit(true);
        assertEquals(CardVersionInfo.empty(), reader.readCardVersion(port, 1));
    }

    @Test
    void test_FR_009_FR_011_parse_aut_certificate_extracts_cn_and_expiry() throws Exception {
        byte[] der;
        try (InputStream in = getClass().getResourceAsStream("/card/test-aut-cert.der")) {
            assertNotNull(in, "test cert resource present");
            der = in.readAllBytes();
        }
        CardAttributeReader.CertInfo info = reader.parseAutCertificate(der);

        assertNotNull(info);
        assertEquals("GEM.TSL-CA3", info.cardHolderName());
        assertEquals(2028, info.certExpirationDate().getYear());
    }

    @Test
    void test_cardholder_name_from_multivalued_rdn_subject_uses_cn() throws Exception {
        // Real eHBA regression: the C.HP.AUT subject carries surname, givenName, serialNumber and CN
        // as four components of a single MULTI-VALUED first RDN. Rdn.getType() only reports the most
        // significant component (surname), so the CN must be found by scanning the RDN's components —
        // otherwise GetCards logs "No cardholder name derivable" and leaves CardHolderName empty.
        byte[] der = resource("/card/test-hba-multivalued-aut.der");

        CardAttributeReader.CertInfo info = reader.parseAutCertificate(der);

        assertNotNull(info);
        assertEquals("Tanja DåvidTEST-ONLY", info.cardHolderName());
    }

    @Test
    void test_parse_aut_certificate_returns_null_on_garbage() {
        assertNull(reader.parseAutCertificate(new byte[] {0x00, 0x01, 0x02}));
    }

    @Test
    void test_bcd_helper_stops_at_F_filler() {
        assertEquals("123", CardAttributeReader.bcdToDigits(new byte[] {0x12, 0x3F}));
    }

    @Test
    void test_extract_do_returns_null_for_absent_tag() {
        assertNull(CardAttributeReader.extractDo(new byte[] {0x5A, 0x01, 0x00}, 0x42));
    }

    @Test
    void test_FR_010_read_leaves_cert_fields_null_when_esign_unreadable() {
        // Canned (non-certificate) response for every APDU: ICCSN/version parse, but DF.ESIGN
        // yields no DER certificate, so the cert-derived fields stay null (FR-005).
        byte[] gdo = {0x5A, 0x02, 0x12, 0x34};
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader-1");
        port.setCannedResponse(ok(gdo));

        CardObjectFactory.CardAttributes attrs = reader.read(port, 1, CardType.EGK);
        assertEquals("1234", attrs.iccsn());
        assertNotNull(attrs.cardVersion());
        assertNull(attrs.cardHolderName());
        assertNull(attrs.kvnr());
    }

    @Test
    void test_2c_read_egk_aut_certificate_from_esign_multiblock() throws Exception {
        byte[] cert = resource("/card/test-aut-cert.der");
        FakeCardReaderPort port = egkPort(cert);

        byte[] der = reader.readEgkAutCertificate(port, 1);

        assertNotNull(der, "ECC AUT certificate read from DF.ESIGN");
        assertEquals(cert.length, der.length, "full multi-block read (cert > 256 bytes)");
        CardAttributeReader.CertInfo info = reader.parseAutCertificate(der);
        assertEquals("GEM.TSL-CA3", info.cardHolderName());
    }

    @Test
    void test_2c_read_egk_populates_cardholder_and_expiry_from_certificate() throws Exception {
        byte[] cert = resource("/card/test-aut-cert.der");
        FakeCardReaderPort port = egkPort(cert);

        CardObjectFactory.CardAttributes attrs = reader.read(port, 1, CardType.EGK);

        assertEquals("80276001011234567890", attrs.iccsn());
        assertEquals("GEM.TSL-CA3", attrs.cardHolderName());
        assertEquals(2028, attrs.certExpirationDate().getYear());
    }

    @Test
    void test_2c_falls_back_to_rsa_when_ecc_certificate_absent() throws Exception {
        byte[] cert = resource("/card/test-aut-cert.der");
        // E256 file not found (SW 6A82) → RSA EF.C.CH.AUT.R2048 (FID C500) is used instead.
        FakeCardReaderPort port = egkPort(cert, /* eccPresent */ false);

        byte[] der = reader.readEgkAutCertificate(port, 1);
        assertNotNull(der, "RSA AUT certificate read when ECC is absent");
        assertEquals(cert.length, der.length);
    }

    @Test
    void test_extract_kvnr_prefers_ou_matching_insurant_pattern() {
        assertEquals("X110485291",
                CardAttributeReader.extractKvnr("CN=Erika Mustermann,OU=X110485291,OU=109500969,O=AOK,C=DE"));
    }

    @Test
    void test_extract_kvnr_returns_null_when_no_attribute_matches() {
        assertNull(CardAttributeReader.extractKvnr("CN=GEM.TSL-CA3,OU=TSL-Signer,O=gematik GmbH,C=DE"));
    }

    @Test
    void test_der_total_length_reads_two_byte_length_header() {
        // SEQUENCE, long form length 0x82 0x02 0x90 → content 656, total 660.
        assertEquals(660, CardAttributeReader.derTotalLength(new byte[] {0x30, (byte) 0x82, 0x02, (byte) 0x90}));
        assertEquals(-1, CardAttributeReader.derTotalLength(new byte[] {0x5A, 0x02, 0x12, 0x34}));
    }

    // --- card-type detection & dispatch ---------------------------------------------------------

    @Test
    void test_detect_egk_when_egk_application_is_present() {
        FakeCardReaderPort port = typedCardPort(GematikISO7816.AID_EGK, 0xC504, null);
        assertEquals(CardType.EGK, CardAttributeReader.detectCardType(port, 1));
    }

    @Test
    void test_detect_hba_when_hba_application_is_present() {
        FakeCardReaderPort port = typedCardPort(GematikISO7816.AID_HBA, 0xC506, null);
        assertEquals(CardType.HBA, CardAttributeReader.detectCardType(port, 1));
    }

    @Test
    void test_detect_smcb_when_smcb_application_is_present() {
        FakeCardReaderPort port = typedCardPort(GematikISO7816.AID_SMC_B, 0xC506, null);
        assertEquals(CardType.SMC_B, CardAttributeReader.detectCardType(port, 1));
    }

    @Test
    void test_detect_unknown_when_no_known_application_present() {
        // Common files readable, but no eGK/HBA/SMC-B application selectable.
        FakeCardReaderPort port = typedCardPort(new byte[] {0x12, 0x34}, 0x0000, null);
        assertEquals(CardType.UNKNOWN, CardAttributeReader.detectCardType(port, 1));
    }

    @Test
    void test_detect_unknown_when_card_does_not_answer() {
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("dead");
        port.setFailTransmit(true);
        assertEquals(CardType.UNKNOWN, CardAttributeReader.detectCardType(port, 1));
    }

    @Test
    void test_for_card_returns_the_card_type_specific_subclass() {
        assertTrue(CardAttributeReader.forCard(
                typedCardPort(GematikISO7816.AID_EGK, 0xC504, null), 1) instanceof EgkCardAttributeReader);
        assertTrue(CardAttributeReader.forCard(
                typedCardPort(GematikISO7816.AID_HBA, 0xC506, null), 1) instanceof HbaCardAttributeReader);
        assertTrue(CardAttributeReader.forCard(
                typedCardPort(GematikISO7816.AID_SMC_B, 0xC506, null), 1) instanceof SmcBCardAttributeReader);
        FakeCardReaderPort dead = FakeCardReaderPort.pcsc("dead");
        dead.setFailTransmit(true);
        assertTrue(CardAttributeReader.forCard(dead, 1) instanceof UnknownCardAttributeReader);
    }

    @Test
    void test_reader_for_maps_every_card_type() {
        assertEquals(CardType.EGK, CardAttributeReader.readerFor(CardType.EGK).cardType());
        assertEquals(CardType.HBA, CardAttributeReader.readerFor(CardType.HBA).cardType());
        assertEquals(CardType.HBA, CardAttributeReader.readerFor(CardType.HBAX).cardType());
        assertEquals(CardType.SMC_B, CardAttributeReader.readerFor(CardType.SMC_B).cardType());
        assertEquals(CardType.KVK, CardAttributeReader.readerFor(CardType.KVK).cardType());
        assertEquals(CardType.UNKNOWN, CardAttributeReader.readerFor(CardType.UNKNOWN).cardType());
    }

    @Test
    void test_hba_subclass_reads_c_hp_aut_certificate_from_esign() throws Exception {
        byte[] cert = resource("/card/test-aut-cert.der");
        FakeCardReaderPort port = typedCardPort(GematikISO7816.AID_HBA, 0xC506, cert);

        CardAttributeReader reader = CardAttributeReader.forCard(port, 1);
        assertTrue(reader instanceof HbaCardAttributeReader);
        CardObjectFactory.CardAttributes attrs = reader.read(port, 1);

        assertEquals("80276001011234567890", attrs.iccsn());
        assertEquals("GEM.TSL-CA3", attrs.cardHolderName());
        assertNull(attrs.kvnr(), "HBA carries no KVNR");
    }

    @Test
    void test_hba_cardholder_name_read_via_sfi_when_file_id_absent() throws Exception {
        // Real-card regression (BUGS.txt #1): the HBA does not expose C.HP.AUT by full file id, so
        // the FID read returns nothing and GetCards shows no CardHolderName. The reader must fall back
        // to the SFI access path (DF.ESIGN, SFI 0x01) so the cardholder name is still derived.
        byte[] cert = resource("/card/test-aut-cert.der");
        FakeCardReaderPort port = hbaSfiOnlyPort(cert);

        CardObjectFactory.CardAttributes attrs =
                CardAttributeReader.readerFor(CardType.HBA).read(port, 1, CardType.HBA);

        assertEquals("GEM.TSL-CA3", attrs.cardHolderName(),
                "cardholder name must come through via the SFI fallback");
        assertEquals("80276001011234567890", attrs.iccsn());
        assertNull(attrs.kvnr(), "HBA carries no KVNR");
    }

    @Test
    void test_read_common_data_reads_atr_iccsn_and_version() {
        FakeCardReaderPort port = typedCardPort(GematikISO7816.AID_EGK, 0xC504, null);
        CardAttributeReader.CommonCardData common = new CardAttributeReader().readCommonData(port, 1);
        assertEquals("80276001011234567890", common.iccsn());
        assertNotNull(common.cardVersion());
        assertFalse(common.isUnreadable());
    }

    @Test
    void test_read_iccsn_after_esign_selected_reselects_mf_and_keeps_type() {
        // Regression: a DF-aware card rejects SELECT EF.GDO (MF-level) while DF.ESIGN is the current
        // DF (SW=6A82), exactly as observed on a real SMC-B. read() must re-select the MF so the
        // ICCSN is still read after the AUT certificate — otherwise CardObjectFactory downgrades the
        // correctly-detected SMC-B to CardType.UNKNOWN.
        byte[] cert = certBytesOrSkip();
        FakeCardReaderPort port = dfAwarePort(GematikISO7816.AID_SMC_B, 0xC506, cert);

        CardObjectFactory.CardAttributes attrs =
                CardAttributeReader.readerFor(CardType.SMC_B).read(port, 1, CardType.SMC_B);

        assertEquals("80276001011234567890", attrs.iccsn(),
                "ICCSN must still be read after DF.ESIGN was selected for the certificate");
    }

    // --- test helpers ---------------------------------------------------------------------------

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = CardAttributeReaderTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "resource present: " + path);
            return in.readAllBytes();
        }
    }

    private static ResponseAPDU sw(int sw) {
        return new ResponseAPDU(new byte[] {(byte) (sw >> 8), (byte) sw});
    }

    /** Build an eGK-like port that serves EF.GDO, EF.Version2 and the DF.ESIGN AUT certificate. */
    private static FakeCardReaderPort egkPort(byte[] cert) {
        return egkPort(cert, true);
    }

    private static FakeCardReaderPort egkPort(byte[] cert, boolean eccPresent) {
        byte[] gdo = {0x5A, 0x0A,
                (byte) 0x80, 0x27, 0x60, 0x01, 0x01, 0x12, 0x34, 0x56, 0x78, (byte) 0x90};
        byte[] version = {(byte) 0xC0, 0x03, 0x04, 0x03, 0x00, (byte) 0xC1, 0x03, 0x05, 0x02, 0x00};

        FakeCardReaderPort port = FakeCardReaderPort.pcsc("egk-1");
        int[] selected = {-1};
        port.setResponder((slot, cmd) -> {
            if (cmd.getINS() == 0xA4) { // SELECT
                if (cmd.getP1() == 0x02) { // by file id
                    byte[] d = cmd.getData();
                    int fid = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
                    if (fid == 0xC504 && !eccPresent) {
                        return sw(0x6A82); // ECC cert absent → reader falls back to RSA
                    }
                    selected[0] = fid;
                }
                return sw(0x9000);
            }
            if (cmd.getINS() == 0xB0) { // READ BINARY
                int offset = (cmd.getP1() << 8) | cmd.getP2();
                byte[] content = switch (selected[0]) {
                    case 0x2F02 -> gdo;
                    case 0x2F11 -> version;
                    case 0xC504, 0xC500 -> cert;
                    default -> new byte[0];
                };
                return readChunk(content, offset);
            }
            return sw(0x6D00);
        });
        return port;
    }

    /**
     * Build a card that answers the common MF files (EF.ATR/EF.GDO/EF.Version2), exposes the
     * application {@code appAid} (SELECT by AID succeeds only for that AID and — when a cert is
     * supplied — DF.ESIGN), and serves {@code cert} from the {@code certFid} file in DF.ESIGN.
     */
    private static FakeCardReaderPort typedCardPort(byte[] appAid, int certFid, byte[] cert) {
        byte[] atr = {(byte) 0xE0, 0x02, 0x12, 0x34};
        byte[] gdo = {0x5A, 0x0A,
                (byte) 0x80, 0x27, 0x60, 0x01, 0x01, 0x12, 0x34, 0x56, 0x78, (byte) 0x90};
        byte[] version = {(byte) 0xC0, 0x03, 0x04, 0x03, 0x00, (byte) 0xC1, 0x03, 0x05, 0x02, 0x00};

        FakeCardReaderPort port = FakeCardReaderPort.pcsc("typed-card");
        int[] selectedFid = {-1};
        port.setResponder((slot, cmd) -> {
            if (cmd.getINS() == 0xA4) { // SELECT
                if (cmd.getP1() == 0x04) { // by DF name (AID)
                    byte[] aid = cmd.getData();
                    if (Arrays.equals(aid, appAid)) {
                        return sw(0x9000);
                    }
                    if (Arrays.equals(aid, GematikISO7816.AID_DF_ESIGN)) {
                        return cert != null ? sw(0x9000) : sw(0x6A82);
                    }
                    return sw(0x6A82); // application not present
                }
                if (cmd.getP1() == 0x02) { // by file id
                    byte[] d = cmd.getData();
                    selectedFid[0] = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
                    return (selectedFid[0] == certFid && cert == null) ? sw(0x6A82) : sw(0x9000);
                }
                return sw(0x9000);
            }
            if (cmd.getINS() == 0xB0) { // READ BINARY
                int offset = (cmd.getP1() << 8) | cmd.getP2();
                byte[] content = switch (selectedFid[0]) {
                    case 0x2F01 -> atr;
                    case 0x2F02 -> gdo;
                    case 0x2F11 -> version;
                    default -> (selectedFid[0] == certFid && cert != null) ? cert : new byte[0];
                };
                return readChunk(content, offset);
            }
            return sw(0x6D00);
        });
        return port;
    }

    /**
     * An HBA-like card whose certificate EFs are NOT reachable by full file id (SELECT C5xx → 6A82),
     * only by short file identifier (READ BINARY with the SFI in P1). Serves EF.GDO/EF.Version2 by
     * file id and {@code cert} from DF.ESIGN SFI 0x01 — exercising the SFI fallback (BUGS.txt #1).
     */
    private static FakeCardReaderPort hbaSfiOnlyPort(byte[] cert) {
        byte[] gdo = {0x5A, 0x0A,
                (byte) 0x80, 0x27, 0x60, 0x01, 0x01, 0x12, 0x34, 0x56, 0x78, (byte) 0x90};
        byte[] version = {(byte) 0xC0, 0x03, 0x04, 0x03, 0x00, (byte) 0xC1, 0x03, 0x05, 0x02, 0x00};

        FakeCardReaderPort port = FakeCardReaderPort.pcsc("hba-sfi");
        // Current read source: 0x2F02/0x2F11 = MF files, -1 = the SFI-selected certificate.
        int[] source = {0};
        port.setResponder((slot, cmd) -> {
            if (cmd.getINS() == 0xA4) { // SELECT
                if (cmd.getP1() == 0x04) { // by DF name (AID)
                    return Arrays.equals(cmd.getData(), GematikISO7816.AID_DF_ESIGN) ? sw(0x9000) : sw(0x6A82);
                }
                if (cmd.getP1() == 0x00) { // select MF
                    return sw(0x9000);
                }
                if (cmd.getP1() == 0x02) { // by file id
                    byte[] d = cmd.getData();
                    int fid = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
                    if (fid == 0xC506 || fid == 0xC500) {
                        return sw(0x6A82); // C.HP.AUT not reachable by file id on this card
                    }
                    source[0] = fid;
                    return sw(0x9000);
                }
                return sw(0x9000);
            }
            if (cmd.getINS() == 0xB0) { // READ BINARY
                int p1 = cmd.getP1();
                if ((p1 & 0x80) != 0) { // SFI read
                    if ((p1 & 0x1F) != GematikISO7816.SFI_C_HP_AUT) {
                        return sw(0x6A82);
                    }
                    source[0] = -1; // certificate session
                    return readChunk(cert, 0);
                }
                int offset = (p1 << 8) | cmd.getP2();
                byte[] content = switch (source[0]) {
                    case 0x2F02 -> gdo;
                    case 0x2F11 -> version;
                    case -1 -> cert;
                    default -> new byte[0];
                };
                return readChunk(content, offset);
            }
            return sw(0x6D00);
        });
        return port;
    }

    private static byte[] certBytesOrSkip() {
        try {
            return resource("/card/test-aut-cert.der");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A DF-aware card that mirrors real gematik G2 behaviour: a SELECT BY FILE ID of an MF-level EF
     * (EF.GDO/EF.Version2/EF.ATR) only succeeds while the MF is the current DF. Selecting the
     * application or DF.ESIGN by AID switches the current DF away from the MF, so the MF-level EFs
     * become unreachable (SW=6A82) until the MF is re-selected (SELECT P1=0x00, data 3F00).
     */
    private static FakeCardReaderPort dfAwarePort(byte[] appAid, int certFid, byte[] cert) {
        byte[] atr = {(byte) 0xE0, 0x02, 0x12, 0x34};
        byte[] gdo = {0x5A, 0x0A,
                (byte) 0x80, 0x27, 0x60, 0x01, 0x01, 0x12, 0x34, 0x56, 0x78, (byte) 0x90};
        byte[] version = {(byte) 0xC0, 0x03, 0x04, 0x03, 0x00, (byte) 0xC1, 0x03, 0x05, 0x02, 0x00};

        FakeCardReaderPort port = FakeCardReaderPort.pcsc("df-aware");
        boolean[] mfCurrent = {true};
        int[] selectedFid = {-1};
        port.setResponder((slot, cmd) -> {
            if (cmd.getINS() == 0xA4) { // SELECT
                if (cmd.getP1() == 0x04) { // by DF name (AID) — switches current DF away from MF
                    byte[] aid = cmd.getData();
                    if (Arrays.equals(aid, appAid) || Arrays.equals(aid, GematikISO7816.AID_DF_ESIGN)) {
                        mfCurrent[0] = false;
                        return sw(0x9000);
                    }
                    return sw(0x6A82);
                }
                if (cmd.getP1() == 0x00) { // select MF (3F00)
                    mfCurrent[0] = true;
                    return sw(0x9000);
                }
                if (cmd.getP1() == 0x02) { // by file id, relative to current DF
                    byte[] d = cmd.getData();
                    int fid = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
                    boolean mfLevel = fid == 0x2F01 || fid == 0x2F02 || fid == 0x2F11;
                    if (mfLevel && !mfCurrent[0]) {
                        return sw(0x6A82); // MF-level EF not reachable from an application DF
                    }
                    selectedFid[0] = fid;
                    return sw(0x9000);
                }
                return sw(0x9000);
            }
            if (cmd.getINS() == 0xB0) { // READ BINARY
                int offset = (cmd.getP1() << 8) | cmd.getP2();
                byte[] content = switch (selectedFid[0]) {
                    case 0x2F01 -> atr;
                    case 0x2F02 -> gdo;
                    case 0x2F11 -> version;
                    default -> (selectedFid[0] == certFid && cert != null) ? cert : new byte[0];
                };
                return readChunk(content, offset);
            }
            return sw(0x6D00);
        });
        return port;
    }

    private static ResponseAPDU readChunk(byte[] content, int offset) {
        int remaining = content.length - offset;
        if (remaining <= 0) {
            return sw(0x6B00); // wrong offset / end of file
        }
        int n = Math.min(256, remaining);
        byte[] out = new byte[n + 2];
        System.arraycopy(content, offset, out, 0, n);
        out[n] = (byte) 0x90;
        out[n + 1] = 0x00;
        return new ResponseAPDU(out);
    }
}
