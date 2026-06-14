package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

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
    void test_FR_010_read_populates_iccsn_and_version_cert_fields_deferred() {
        byte[] gdo = {0x5A, 0x02, 0x12, 0x34};
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader-1");
        port.setCannedResponse(ok(gdo));

        CardObjectFactory.CardAttributes attrs = reader.read(port, 1, CardType.EGK);
        assertEquals("1234", attrs.iccsn());
        assertNotNull(attrs.cardVersion());
        assertNull(attrs.cardHolderName());
        assertTrue(attrs.kvnr() == null);
    }
}
