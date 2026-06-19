package de.servicehealtherx.konnektor.vsdm;

import java.util.Arrays;

/**
 * Strips the eGK object-system framing off the VSDM elementary files so the
 * {@code ReadVSDResponse} carries the bare, gunzip-ready gzip streams the PVS expects
 * (base64(gzip(XML)), gemSpec_SST_PS_VSDM VSDM-A_2652/2691).
 *
 * <p>The eGK does <strong>not</strong> store bare gzip in EF.PD/EF.VD/EF.GVD — each file wraps the
 * payload in object-system framing (gemSpec_eGK_ObjSys_G2_1 §5.4):
 * <ul>
 *   <li><b>EF.PD</b>: {@code [2-byte big-endian length L][gzip(PD-XML), L bytes]}</li>
 *   <li><b>EF.VD</b>: {@code [2-byte start AVD][2-byte end AVD][2-byte start GVD][2-byte end GVD]…} —
 *       {@code AllgemeineVersicherungsdaten = bytes[startAVD, endAVD)}; the transitional GVD copy at
 *       {@code [startGVD, endGVD)} is deliberately never delivered (VSDM-A_2784)</li>
 *   <li><b>EF.GVD</b>: {@code [2-byte big-endian length L][gzip(GVD-XML), L bytes]}</li>
 * </ul>
 *
 * <p>Returning the raw EF bytes is exactly what makes a consumer's gunzip fail with
 * {@code java.util.zip.ZipException: Not in GZIP format}: byte 0 is the length/offset header
 * (e.g. {@code 0x01 0x85}), not the gzip magic {@code 0x1f 0x8b}.
 *
 * <p>Each method validates that the de-framed slice actually starts with the gzip magic, so a wrong
 * assumption fails loudly here rather than shipping corrupt data downstream. As a defensive measure,
 * a payload that already starts with the gzip magic is passed through unchanged.
 */
final class VsdmContainer {

    private static final int GZIP_MAGIC_0 = 0x1f;
    private static final int GZIP_MAGIC_1 = 0x8b;

    private VsdmContainer() {
    }

    /** EF.PD → {@code PersoenlicheVersichertendaten}: drop the 2-byte length prefix. */
    static byte[] personalData(byte[] efPd) {
        return stripLengthPrefix(efPd, "EF.PD");
    }

    /** EF.GVD → {@code GeschuetzteVersichertendaten}: drop the 2-byte length prefix. */
    static byte[] protectedData(byte[] efGvd) {
        return stripLengthPrefix(efGvd, "EF.GVD");
    }

    /**
     * EF.VD → {@code AllgemeineVersicherungsdaten}: slice the AVD gzip stream by the file's
     * start/end offsets (VSDM-A_2784 — the transitional GVD copy in EF.VD is never returned).
     */
    static byte[] generalData(byte[] efVd) {
        if (startsWithGzipMagic(efVd, 0)) {
            return efVd; // already a bare gzip stream
        }
        if (efVd == null || efVd.length < 4) {
            throw failed("EF.VD shorter than its offset header");
        }
        int start = u16(efVd, 0);
        int end = u16(efVd, 2);
        if (start < 4 || end <= start || end > efVd.length) {
            throw failed("EF.VD AVD offsets out of range (start=" + start + ", end=" + end
                    + ", len=" + efVd.length + ")");
        }
        byte[] avd = Arrays.copyOfRange(efVd, start, end);
        return requireGzip(avd, "EF.VD AVD slice");
    }

    private static byte[] stripLengthPrefix(byte[] ef, String name) {
        if (startsWithGzipMagic(ef, 0)) {
            return ef; // already a bare gzip stream
        }
        if (ef == null || ef.length < 3) {
            throw failed(name + " shorter than its 2-byte length prefix");
        }
        int declared = u16(ef, 0);
        int available = ef.length - 2;
        // The card may pad EF.PD/EF.GVD to their allocated size; trust the declared length but never
        // read past what was actually read off the card.
        int len = declared == 0 ? available : Math.min(declared, available);
        byte[] payload = Arrays.copyOfRange(ef, 2, 2 + len);
        return requireGzip(payload, name);
    }

    private static byte[] requireGzip(byte[] payload, String name) {
        if (!startsWithGzipMagic(payload, 0)) {
            throw failed(name + " is not a gzip stream after de-framing");
        }
        return payload;
    }

    private static boolean startsWithGzipMagic(byte[] b, int off) {
        return b != null && b.length >= off + 2
                && (b[off] & 0xFF) == GZIP_MAGIC_0 && (b[off + 1] & 0xFF) == GZIP_MAGIC_1;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static VsdmReadException failed(String detail) {
        return new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, detail);
    }
}
