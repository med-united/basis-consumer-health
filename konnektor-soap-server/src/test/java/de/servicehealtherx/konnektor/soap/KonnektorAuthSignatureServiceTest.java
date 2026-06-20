package de.servicehealtherx.konnektor.soap;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KonnektorAuthSignatureServiceTest {

    /** The exact 64-byte raw R||S the SMC-B returned in the failing ExternalAuthenticate trace. */
    private static final byte[] RAW_CARD_SIGNATURE = HexFormat.of().parseHex(
            "2DE6DB6282BCBD2EA85F2775108306FB1D759466699C8E4E1D0183FE07B33F49"
            + "032FF23C15A7FEB991B9A526DD0E89B95CCDB73275C33D23149E9CACB6193CCF");

    @Test
    void wrapsRawEcdsaSignatureAsValidDerSequence() {
        byte[] der = KonnektorAuthSignatureService.rawEcdsaToDer(RAW_CARD_SIGNATURE);

        // SEQUENCE { INTEGER r (32 bytes, positive), INTEGER s (32 bytes, positive) }
        assertEquals(0x30, der[0] & 0xFF, "must start with a DER SEQUENCE tag");
        assertEquals(der.length - 2, der[1] & 0xFF, "single-byte length must cover the content");

        // INTEGER r
        assertEquals(0x02, der[2] & 0xFF);
        assertEquals(0x20, der[3] & 0xFF, "r is 32 bytes, high bit clear → no padding");
        byte[] r = Arrays.copyOfRange(der, 4, 36);
        assertArrayEquals(Arrays.copyOfRange(RAW_CARD_SIGNATURE, 0, 32), r);

        // INTEGER s
        assertEquals(0x02, der[36] & 0xFF);
        assertEquals(0x20, der[37] & 0xFF, "s is 32 bytes, high bit clear → no padding");
        byte[] s = Arrays.copyOfRange(der, 38, 70);
        assertArrayEquals(Arrays.copyOfRange(RAW_CARD_SIGNATURE, 32, 64), s);
    }

    @Test
    void prependsZeroWhenHighBitSetToKeepIntegerPositive() {
        byte[] raw = new byte[64];
        raw[0] = (byte) 0x80; // r with the high bit set → must be padded with 0x00
        raw[32] = (byte) 0x01; // s with the high bit clear → no padding

        byte[] der = KonnektorAuthSignatureService.rawEcdsaToDer(raw);

        assertEquals(0x30, der[0] & 0xFF);
        assertEquals(0x02, der[2] & 0xFF);
        assertEquals(0x21, der[3] & 0xFF, "padded r occupies 33 bytes");
        assertEquals(0x00, der[4] & 0xFF, "leading zero keeps r non-negative");
        assertEquals((byte) 0x80, der[5]);
    }

    @Test
    void stripsLeadingZerosToProduceMinimalInteger() {
        byte[] raw = new byte[64];
        raw[30] = (byte) 0x11; // r = 0x...0000_1122 → leading zeros must be dropped
        raw[31] = (byte) 0x22;
        raw[63] = (byte) 0x33; // s = 0x...0033

        byte[] der = KonnektorAuthSignatureService.rawEcdsaToDer(raw);

        assertEquals(0x02, der[2] & 0xFF);
        assertEquals(0x02, der[3] & 0xFF, "r reduces to two significant bytes");
        assertEquals(0x11, der[4] & 0xFF);
        assertEquals(0x22, der[5] & 0xFF);
        // total length stays under the single-byte DER length boundary
        assertTrue(der.length < 0x80);
    }
}
