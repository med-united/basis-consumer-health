package de.servicehealtherx.cetp.delivery;

import java.nio.charset.StandardCharsets;

/**
 * Frames a CETP event message per gemSpec_Kon TIP1-A_4596: the ASCII marker {@code CETP}, a 4-byte
 * big-endian length of the following XML body, then the UTF-8 XML {@code Event} document.
 */
public final class CetpFrameCodec {

    /** ASCII marker {@code CETP} that every event message begins with. */
    public static final byte[] MAGIC = "CETP".getBytes(StandardCharsets.US_ASCII);

    private CetpFrameCodec() {
    }

    /**
     * @param xmlUtf8 the UTF-8-encoded XML {@code Event} document
     * @return {@code "CETP"} + 4-byte big-endian length + body
     */
    public static byte[] encode(byte[] xmlUtf8) {
        int len = xmlUtf8.length;
        byte[] frame = new byte[MAGIC.length + 4 + len];
        System.arraycopy(MAGIC, 0, frame, 0, MAGIC.length);
        frame[4] = (byte) (len >>> 24);
        frame[5] = (byte) (len >>> 16);
        frame[6] = (byte) (len >>> 8);
        frame[7] = (byte) len;
        System.arraycopy(xmlUtf8, 0, frame, 8, len);
        return frame;
    }
}
