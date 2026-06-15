package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.transport.FakeCardReaderPort;
import de.servicehealtherx.apdu.model.CardType;

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
