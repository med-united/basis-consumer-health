package de.servicehealtherx.crypto.signer.card;

import java.io.ByteArrayOutputStream;

/**
 * Converts a raw ECDSA signature {@code R||S} (as gematik smart cards emit it — 64 bytes for
 * brainpoolP256r1) into its ASN.1 DER form {@code SEQUENCE { INTEGER r, INTEGER s }} (X9.62 / BSI
 * TR-03111). JCE's {@code SHA256withECDSA} and the CMS/XMLDSig layers built on it expect the
 * DER-encoded form; the raw concatenation parses as garbage (e.g. "header too long").
 */
public final class EcdsaDerEncoder {

    private EcdsaDerEncoder() {
    }

    /** Wrap a raw {@code R||S} signature into {@code SEQUENCE { INTEGER r, INTEGER s }}. */
    public static byte[] rawToDer(byte[] rawSignature) {
        if (rawSignature == null || rawSignature.length == 0 || rawSignature.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Raw ECDSA signature must be a non-empty even-length R||S concatenation");
        }
        int half = rawSignature.length / 2;
        byte[] rInt = derInteger(rawSignature, 0, half);
        byte[] sInt = derInteger(rawSignature, half, half);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30); // SEQUENCE
        writeLength(out, rInt.length + sInt.length);
        out.write(rInt, 0, rInt.length);
        out.write(sInt, 0, sInt.length);
        return out.toByteArray();
    }

    /** Encode {@code value[offset..offset+len)} as a minimal, non-negative DER INTEGER (tag 0x02). */
    private static byte[] derInteger(byte[] value, int offset, int len) {
        int start = offset;
        int end = offset + len;
        while (start < end - 1 && value[start] == 0) {
            start++; // strip superfluous leading zero bytes
        }
        boolean prependZero = (value[start] & 0x80) != 0; // keep the integer positive
        int magnitudeLength = (end - start) + (prependZero ? 1 : 0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x02); // INTEGER
        writeLength(out, magnitudeLength);
        if (prependZero) {
            out.write(0x00);
        }
        out.write(value, start, end - start);
        return out.toByteArray();
    }

    /** Write a DER definite length (long form for lengths ≥ 0x80, e.g. P-521 signatures). */
    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        byte[] octets = new byte[4];
        int n = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            byte b = (byte) (length >>> shift);
            if (n > 0 || b != 0) {
                octets[n++] = b;
            }
        }
        out.write(0x80 | n);
        out.write(octets, 0, n);
    }
}
