package de.servicehealtherx.konnektor.vsdm;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Converts the raw EF.StatusVD container into a {@link VsdStatus} per gemSpec_FM_VSDM Tab_FM_VSDM_21
 * (VSDM-A_2708 / A_3063): the update Status, the last-update Timestamp (ASCII → dateTime), and the
 * schema Version (BCD → dotted). An unknown storage-structure version aborts (VSDM-A_2979).
 *
 * <p>Binary layout per gemSpec_eGK_Fach_VSDM Tab_eGK_Fach_VSDM_04 (25 octets, transparent EF):
 * <pre>
 *   [0]        Status                    (ASCII '0' = consistent, '1' = open transactions)
 *   [1..14]    Timestamp                 (14 ASCII chars "YYYYMMDDHHMMSS", UTC)
 *   [15..19]   Version_XML               (5 BCD bytes → 3+3+4 digits → "7.3.1")
 *   [20..24]   Version_Speicherstruktur  (5 BCD bytes; must be a known structure version)
 * </pre>
 *
 * <p>Status and Timestamp are <strong>alphanumeric (ASCII)</strong> on the card — only the two
 * Version fields are BCD-encoded.
 */
public final class StatusVdConverter {

    /**
     * Storage-structure versions this converter understands (VSDM-A_2979), as decoded BCD digit
     * strings. eGK G2/G2.1 carry the fixed value {@code 0x0030000004} ("3.0.4").
     */
    private static final Set<String> KNOWN_STORAGE_STRUCTURE_VERSIONS = Set.of("0030000004");

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final int OFF_STATUS = 0;
    private static final int OFF_TIMESTAMP = 1;
    private static final int LEN_TIMESTAMP = 14;   // 14 ASCII chars "YYYYMMDDHHMMSS"
    private static final int OFF_VERSION_XML = 15;
    private static final int LEN_VERSION_XML = 5;   // 10 BCD digits → 3 + 3 + 4
    private static final int OFF_STORAGE_VERSION = 20;
    private static final int LEN_STORAGE_VERSION = 5; // 10 BCD digits

    private static final int MIN_LENGTH = OFF_STORAGE_VERSION + LEN_STORAGE_VERSION; // 25

    public VsdStatus convert(byte[] statusVd) {
        if (statusVd == null || statusVd.length < MIN_LENGTH) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "EF.StatusVD too short");
        }

        String storageVersion = bcd(statusVd, OFF_STORAGE_VERSION, LEN_STORAGE_VERSION);
        if (!KNOWN_STORAGE_STRUCTURE_VERSIONS.contains(storageVersion)) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED,
                    "unknown EF.StatusVD storage-structure version " + formatVersion(storageVersion));
        }

        // Status / Timestamp are alphanumeric (ASCII) per Tab_FM_VSDM_21.
        String status = String.valueOf((char) (statusVd[OFF_STATUS] & 0xFF));
        String tsDigits = ascii(statusVd, OFF_TIMESTAMP, LEN_TIMESTAMP);
        String version = formatVersion(bcd(statusVd, OFF_VERSION_XML, LEN_VERSION_XML));

        LocalDateTime local;
        try {
            local = LocalDateTime.parse(tsDigits, TS_FORMAT);
        } catch (RuntimeException e) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "invalid EF.StatusVD timestamp");
        }
        return new VsdStatus(status, local.atOffset(ZoneOffset.UTC), version);
    }

    /** Read {@code len} ASCII bytes starting at {@code off} as a {@link String}. */
    private static String ascii(byte[] src, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (src[off + i] & 0xFF));
        }
        return sb.toString();
    }

    /** Decode {@code len} BCD bytes starting at {@code off} into {@code 2*len} decimal digits. */
    private static String bcd(byte[] src, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int b = src[off + i] & 0xFF;
            sb.append((char) ('0' + (b >> 4)));
            sb.append((char) ('0' + (b & 0x0F)));
        }
        return sb.toString();
    }

    /** "0070030001" → major(3)=7, minor(3)=3, patch(4)=1 → "7.3.1". */
    private static String formatVersion(String tenDigits) {
        int major = Integer.parseInt(tenDigits.substring(0, 3));
        int minor = Integer.parseInt(tenDigits.substring(3, 6));
        int patch = Integer.parseInt(tenDigits.substring(6, 10));
        return major + "." + minor + "." + patch;
    }
}
