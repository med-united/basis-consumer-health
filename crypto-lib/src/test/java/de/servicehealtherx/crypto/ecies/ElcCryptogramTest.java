package de.servicehealtherx.crypto.ecies;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link ElcCryptogram} codec against the normative {@code (PO, C, T)} example
 * published in gemSpec_Krypt §4.7 (the {@code dumpasn1} sample on p. 81).
 */
class ElcCryptogramTest {

    /** The §4.7 example cryptogram (Base64 of the card-compatible (PO,C,T) DER). */
    private static final String EXAMPLE_B64 =
            "poGOBgkrJAMDAggBAQd/SUOGQQRouC6tM2TQQ+RP3pptgdAaDF8Te7IVCkUBe2H+PJSLK4W/"
            + "BXIXkndiBwEfftd5wk4pjzCdC2j1q14/CIWcW89nhjEC7G47UAu2ZqmbIhxstkXV3UI2UUek/"
            + "qwBwtb26aUild+5kkTZXf5674OKHSdj6IFwjggvhYt9b/CTsA==";

    @Test
    void parses_the_gematik_example_into_curve_po_c_and_t() {
        byte[] der = Base64.getDecoder().decode(EXAMPLE_B64);

        ElcCryptogram parsed = ElcCryptogram.parse(der);

        assertEquals(EciesOids.BRAINPOOL_P256R1, parsed.curveOid());
        assertEquals(65, parsed.po().length, "uncompressed brainpoolP256r1 point is 04||X||Y = 65 bytes");
        assertEquals(0x04, parsed.po()[0] & 0xFF, "point must be uncompressed");
        assertEquals(49, parsed.c().length);
        assertEquals(8, parsed.t().length, "ELC CMAC tag is truncated to 8 bytes");
    }

    @Test
    void round_trips_the_gematik_example_byte_for_byte() {
        byte[] der = Base64.getDecoder().decode(EXAMPLE_B64);

        byte[] reEncoded = ElcCryptogram.parse(der).toAsn1();

        assertArrayEquals(der, reEncoded, "re-encoding must reproduce the exact card-compatible bytes");
    }

    @Test
    void constructs_and_round_trips_an_arbitrary_cryptogram() {
        byte[] po = new byte[65];
        po[0] = 0x04;
        byte[] c = new byte[48];
        byte[] t = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};

        ElcCryptogram original = new ElcCryptogram(EciesOids.BRAINPOOL_P256R1, po, c, t);
        ElcCryptogram reparsed = ElcCryptogram.parse(original.toAsn1());

        assertEquals(original, reparsed);
    }

    @Test
    void rejects_malformed_input() {
        assertThrows(IllegalArgumentException.class,
                () -> ElcCryptogram.parse(new byte[]{0x30, 0x00}));
    }

    @Test
    void exposes_defensive_copies() {
        ElcCryptogram cryptogram =
                new ElcCryptogram(EciesOids.BRAINPOOL_P256R1, new byte[65], new byte[48], new byte[8]);
        cryptogram.po()[0] = (byte) 0xFF;
        assertTrue(cryptogram.po()[0] == 0x00, "internal state must not be mutated via the accessor");
    }
}
