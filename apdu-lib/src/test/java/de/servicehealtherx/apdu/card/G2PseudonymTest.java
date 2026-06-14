package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link G2Pseudonym} (Phase 6 task T041; A_25801 / FR-050).
 */
class G2PseudonymTest {

    @Test
    void test_FR_050_pseudonym_is_first_10_hex_chars_of_sha256() throws Exception {
        String iccsn = "80276001011234567890";
        LocalDate expiry = LocalDate.of(2030, 1, 1);

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((iccsn + expiry).getBytes(StandardCharsets.UTF_8));
        String expected = HexFormat.of().formatHex(digest).substring(0, 10);

        assertEquals(expected, G2Pseudonym.compute(iccsn, expiry));
        assertEquals(10, G2Pseudonym.compute(iccsn, expiry).length());
    }

    @Test
    void test_pseudonym_is_deterministic_and_input_sensitive() {
        LocalDate expiry = LocalDate.of(2030, 1, 1);
        String a = G2Pseudonym.compute("ICCSN-A", expiry);

        assertEquals(a, G2Pseudonym.compute("ICCSN-A", expiry), "deterministic");
        assertNotEquals(a, G2Pseudonym.compute("ICCSN-B", expiry), "differs by ICCSN");
        assertNotEquals(a, G2Pseudonym.compute("ICCSN-A", expiry.plusDays(1)), "differs by expiry");
        assertTrue(a.chars().allMatch(c -> "0123456789abcdef".indexOf(c) >= 0), "lowercase hex");
    }
}
