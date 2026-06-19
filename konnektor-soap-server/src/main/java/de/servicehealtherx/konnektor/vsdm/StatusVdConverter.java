package de.servicehealtherx.konnektor.vsdm;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Converts the raw EF.StatusVD container into a {@link VsdStatus} per gemSpec_FM_VSDM Tab_FM_VSDM_21
 * (VSDM-A_2708 / A_3063): the update Status, the last-update Timestamp (BCD → dateTime), and the
 * schema Version (BCD → dotted). An unknown storage-structure version aborts (VSDM-A_2979).
 *
 * <p>Assumed binary layout (confirm byte-exact against gemSpec_eGK_Fach_VSDM EF.StatusVD before
 * certification — analogous to the DF.HCA-AID open item in research.md):
 * <pre>
 *   [0]       Version_Speicherstruktur (storage-structure version; must be known)
 *   [1]       Status                    (0x00 → "0", 0x01 → "1")
 *   [2..8]    Timestamp                 (7 BCD bytes → "YYYYMMDDHHMMSS")
 *   [9..13]   Version                   (5 BCD bytes → 3+3+4 digits → "7.3.1")
 * </pre>
 */
public final class StatusVdConverter {

    /** Storage-structure versions this converter understands (VSDM-A_2979). */
    private static final Set<Integer> KNOWN_STORAGE_STRUCTURE_VERSIONS = Set.of(0x01);

    private static final ZoneId CARD_ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final int OFF_STORAGE_VERSION = 0;
    private static final int OFF_STATUS = 1;
    private static final int OFF_TIMESTAMP = 2;
    private static final int LEN_TIMESTAMP = 7;   // 14 BCD digits
    private static final int OFF_VERSION = 9;
    private static final int LEN_VERSION = 5;      // 10 BCD digits → 3 + 3 + 4

    public VsdStatus convert(byte[] statusVd) {
        if (statusVd == null || statusVd.length < OFF_VERSION + LEN_VERSION) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "EF.StatusVD too short");
        }
        int storageVersion = statusVd[OFF_STORAGE_VERSION] & 0xFF;
        if (!KNOWN_STORAGE_STRUCTURE_VERSIONS.contains(storageVersion)) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED,
                    "unknown EF.StatusVD storage-structure version 0x" + Integer.toHexString(storageVersion));
        }

        String status = (statusVd[OFF_STATUS] & 0xFF) == 0 ? "0" : "1";
        String tsDigits = bcd(statusVd, OFF_TIMESTAMP, LEN_TIMESTAMP);
        String version = formatVersion(bcd(statusVd, OFF_VERSION, LEN_VERSION));

        LocalDateTime local;
        try {
            local = LocalDateTime.parse(tsDigits, TS_FORMAT);
        } catch (RuntimeException e) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "invalid EF.StatusVD timestamp");
        }
        return new VsdStatus(status, local.atZone(CARD_ZONE).toOffsetDateTime(), version);
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
