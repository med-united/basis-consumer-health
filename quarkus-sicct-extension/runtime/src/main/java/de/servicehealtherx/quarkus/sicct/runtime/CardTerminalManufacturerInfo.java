package de.servicehealtherx.quarkus.sicct.runtime;

import java.nio.charset.StandardCharsets;

/**
 * Parsed content of a SICCT <em>CardTerminal Manufacturer DO</em>
 * (SICCT-Spezifikation V1.2.3, §5.5.10.6; wire tag {@code '46'}).
 *
 * <p>
 * The DO value is <strong>not</strong> a plain string: it is a fixed-layout record of
 * three 5-byte ASCII fields followed by optional discretionary data. For an eHealth
 * card terminal that discretionary data is the {@code DO_KT_0001} object (tag
 * {@code 'D7'}, gemSpec_KT Tabelle 13) carrying the eHealth interface version (VER),
 * product type (PT), product-type version (PTV), model name (MODN), firmware version
 * (FWV), hardware version (HWV) and firmware group (FWG).
 *
 * <pre>
 *   [0 .. 5)   manufacturer  (RID, ASCII, space padded)
 *   [5 .. 10)  SICCT version (ASCII)
 *   [10 .. 15) software version (ASCII)
 *   [15 .. ]   discretionary data: 'D7' LEN { VER(9) PT(2) PTV(9) MODN(8) FWV(9) HWV(9) FWG(5) }
 * </pre>
 *
 * <p>
 * Each version sub-field (VER/PTV/FWV/HWV) is encoded as three right-aligned,
 * space-padded 3-byte ASCII groups, e.g. {@code 20 20 31 20 20 30 20 20 30} → "1.0.0".
 * Parsing is defensive: any field that is absent or truncated yields {@code null}
 * rather than throwing, so a partial/foreign DO never breaks protocol handling.
 */
public record CardTerminalManufacturerInfo(
        String manufacturer,
        String sicctVersion,
        String softwareVersion,
        String ehealthInterfaceVersion,
        String productType,
        String productTypeVersion,
        String modelName,
        String firmwareVersion,
        String hardwareVersion,
        String firmwareGroup) {

    /** Discretionary Data Data Object tag (DO_KT_0001), gemSpec_KT Tabelle 13. */
    private static final int DISCRETIONARY_DATA_TAG = 0xD7;

    public static CardTerminalManufacturerInfo parse(byte[] value) {
        if (value == null) {
            return new CardTerminalManufacturerInfo(null, null, null, null, null, null, null, null, null, null);
        }
        String manufacturer = ascii(value, 0, 5);
        String sicctVersion = ascii(value, 5, 5);
        String softwareVersion = ascii(value, 10, 5);

        String ver = null;
        String pt = null;
        String ptv = null;
        String modn = null;
        String fwv = null;
        String hwv = null;
        String fwg = null;

        // Discretionary data DO ('D7' LEN content) immediately after the 15 fixed bytes.
        if (value.length >= 17 && (value[15] & 0xFF) == DISCRETIONARY_DATA_TAG) {
            int len = value[16] & 0xFF;
            int off = 17;
            if (off + len <= value.length) {
                ver = triplet(value, off);
                pt = ascii(value, off + 9, 2);
                ptv = triplet(value, off + 11);
                modn = ascii(value, off + 20, 8);
                fwv = triplet(value, off + 28);
                hwv = triplet(value, off + 37);
                fwg = ascii(value, off + 46, 5);
            }
        }
        return new CardTerminalManufacturerInfo(manufacturer, sicctVersion, softwareVersion,
                ver, pt, ptv, modn, fwv, hwv, fwg);
    }

    /** Human-readable summary suitable for {@code CardTerminal.productInformation}. */
    public String displayString() {
        return "manufacturer=" + manufacturer
                + " sicctVersion=" + sicctVersion
                + " softwareVersion=" + softwareVersion
                + " eHealthInterfaceVersion=" + ehealthInterfaceVersion
                + " productType=" + productType
                + " productTypeVersion=" + productTypeVersion
                + " model=" + modelName
                + " firmware=" + firmwareVersion
                + " hardware=" + hardwareVersion
                + " fwGroup=" + firmwareGroup;
    }

    /** Trimmed ASCII slice {@code [off, off+len)}, or {@code null} if out of range. */
    private static String ascii(byte[] d, int off, int len) {
        if (off < 0 || off + len > d.length) {
            return null;
        }
        String s = new String(d, off, len, StandardCharsets.US_ASCII).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Decodes a 9-byte version field (three space-padded 3-byte ASCII groups) into a
     * dotted "major.minor.patch" string, e.g. {@code 20 20 31 ...} → "1.0.0".
     */
    private static String triplet(byte[] d, int off) {
        if (off + 9 > d.length) {
            return null;
        }
        String major = new String(d, off, 3, StandardCharsets.US_ASCII).trim();
        String minor = new String(d, off + 3, 3, StandardCharsets.US_ASCII).trim();
        String patch = new String(d, off + 6, 3, StandardCharsets.US_ASCII).trim();
        return major + "." + minor + "." + patch;
    }
}
