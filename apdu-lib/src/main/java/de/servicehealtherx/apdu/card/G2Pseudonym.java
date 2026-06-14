package de.servicehealtherx.apdu.card;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Computes the G2.0 admin-log pseudonym (A_25801): the first 10 hex characters of
 * {@code SHA-256(ICCSN ‖ expirationDate)}. Transport-neutral and deterministic.
 *
 * <p>Used for the admin-visible log entry maintained for inserted G2.0 SMC-B / HBA cards
 * (FR-050) so a card can be recognised across insertions without storing identifying data.
 */
public final class G2Pseudonym {

    /** Length of the pseudonym in hex characters (data-model: String(10)). */
    public static final int LENGTH = 10;

    private G2Pseudonym() {
    }

    /**
     * @param iccsn          the card's ICCSN
     * @param expirationDate the AUT certificate expiry
     * @return the first {@value #LENGTH} hex chars of SHA-256(ICCSN ‖ expirationDate)
     */
    public static String compute(String iccsn, LocalDate expirationDate) {
        Objects.requireNonNull(iccsn, "iccsn");
        Objects.requireNonNull(expirationDate, "expirationDate");
        byte[] input = (iccsn + expirationDate).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            return HexFormat.of().formatHex(digest).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }
}
