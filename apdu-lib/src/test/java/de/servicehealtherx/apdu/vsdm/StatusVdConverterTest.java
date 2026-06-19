package de.servicehealtherx.apdu.vsdm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatusVdConverterTest {

    private final StatusVdConverter converter = new StatusVdConverter();

    private static byte[] statusVd(int status) {
        // [0] storage-version 0x01, [1] status, [2..8] ts BCD 20120131084713, [9..13] ver BCD 0070030001
        return new byte[]{
                0x01, (byte) status,
                0x20, 0x12, 0x01, 0x31, 0x08, 0x47, 0x13,
                0x00, 0x70, 0x03, 0x00, 0x01
        };
    }

    @Test
    void test_VSDM_A_2708_converts_status_timestamp_and_version() {
        VsdStatus s = converter.convert(statusVd(0x00));
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
        assertTrue(converter.convert(statusVd(0x01)).isInconsistent());
    }

    @Test
    void test_VSDM_A_2979_rejects_unknown_storage_structure_version() {
        byte[] bad = statusVd(0x00);
        bad[0] = (byte) 0x99; // unknown storage-structure version
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> converter.convert(bad));
        assertEquals(VsdmErrorCode.VSD_READ_FAILED, ex.errorCode());
    }
}
