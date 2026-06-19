package de.servicehealtherx.konnektor.vsdm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class StatusVdConverterTest {

    private final StatusVdConverter converter = new StatusVdConverter();

    /**
     * gemSpec_eGK_Fach_VSDM Tab_eGK_Fach_VSDM_04 (25 octets):
     * [0] Status (ASCII), [1..14] Timestamp (ASCII "20120131084713"),
     * [15..19] Version_XML BCD 0070030001 → 7.3.1,
     * [20..24] Version_Speicherstruktur BCD 0030000004 → 3.0.4.
     */
    private static byte[] statusVd(char status) {
        byte[] vd = new byte[25];
        vd[0] = (byte) status;
        byte[] ts = "20120131084713".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ts, 0, vd, 1, ts.length);
        byte[] versionXml = {0x00, 0x70, 0x03, 0x00, 0x01};
        System.arraycopy(versionXml, 0, vd, 15, versionXml.length);
        byte[] storageVersion = {0x00, 0x30, 0x00, 0x00, 0x04};
        System.arraycopy(storageVersion, 0, vd, 20, storageVersion.length);
        return vd;
    }

    @Test
    void test_VSDM_A_2708_converts_status_timestamp_and_version() {
        VsdStatus s = converter.convert(statusVd('0'));
        assertEquals("0", s.status());
        assertFalse(s.isInconsistent());
        assertEquals("7.3.1", s.version());
        assertEquals(2012, s.timestamp().getYear());
        assertEquals(1, s.timestamp().getMonthValue());
        assertEquals(31, s.timestamp().getDayOfMonth());
        assertEquals(8, s.timestamp().getHour());
        assertEquals(47, s.timestamp().getMinute());
        assertEquals(13, s.timestamp().getSecond());
    }

    @Test
    void test_VSDM_A_2660_flags_inconsistent_status() {
        assertTrue(converter.convert(statusVd('1')).isInconsistent());
    }

    @Test
    void test_VSDM_A_2979_rejects_unknown_storage_structure_version() {
        byte[] bad = statusVd('0');
        bad[20] = (byte) 0x99; // unknown storage-structure version
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> converter.convert(bad));
        assertEquals(VsdmErrorCode.VSD_READ_FAILED, ex.errorCode());
    }
}
