package de.servicehealtherx.apdu.c2c;

import java.util.Arrays;

/**
 * Parses a gematik card-verifiable certificate (CVC) into its {@link CvCertificate} fields by walking
 * the BER-TLV structure (gemSpec_COS §6 / Tab_267): outer {@code 7F21}, body {@code 7F4E}, with
 * {@code 42}=CAR, {@code 5F20}=CHR, {@code 7F49/86}=public point, {@code 7F4C}=CHAT.
 *
 * <p>Host-side parsing only orders the chain and selects key references; the on-card
 * {@code PSO VERIFY CERTIFICATE} re-verifies every signature. Implementation is unit-tested against
 * canned TLV; it is not validated against real card output (see research.md C2C risk note).
 */
public final class CvcChainParser {

    private static final int TAG_CVC = 0x7F21;
    private static final int TAG_BODY = 0x7F4E;
    private static final int TAG_CAR = 0x42;
    private static final int TAG_CHR = 0x5F20;
    private static final int TAG_PUBKEY = 0x7F49;
    private static final int TAG_POINT = 0x86;
    private static final int TAG_CHAT = 0x7F4C;

    public CvCertificate parse(byte[] cvc) {
        Tlv root = readTlv(cvc, 0);
        if (root == null || root.tag != TAG_CVC) {
            throw new IllegalArgumentException("not a CVC (expected 7F21)");
        }
        Tlv body = findChild(root, TAG_BODY);
        if (body == null) {
            throw new IllegalArgumentException("CVC missing body 7F4E");
        }
        byte[] car = childValue(body, TAG_CAR);
        byte[] chr = childValue(body, TAG_CHR);
        byte[] chat = nestedChatValue(body);
        byte[] point = nestedPointValue(body);
        return new CvCertificate(cvc.clone(), car, chr, chat, point);
    }

    private static byte[] nestedChatValue(Tlv body) {
        Tlv chat = findChild(body, TAG_CHAT);
        return chat == null ? null : chat.value;
    }

    private static byte[] nestedPointValue(Tlv body) {
        Tlv pub = findChild(body, TAG_PUBKEY);
        if (pub == null) {
            return null;
        }
        Tlv point = findChild(pub, TAG_POINT);
        return point == null ? null : point.value;
    }

    private static byte[] childValue(Tlv parent, int tag) {
        Tlv child = findChild(parent, tag);
        return child == null ? null : child.value;
    }

    private static Tlv findChild(Tlv parent, int tag) {
        int pos = 0;
        while (pos < parent.value.length) {
            Tlv child = readTlv(parent.value, pos);
            if (child == null) {
                return null;
            }
            if (child.tag == tag) {
                return child;
            }
            pos = child.end;
        }
        return null;
    }

    private static final class Tlv {
        final int tag;
        final byte[] value;
        final int end;

        Tlv(int tag, byte[] value, int end) {
            this.tag = tag;
            this.value = value;
            this.end = end;
        }
    }

    /** Read one BER-TLV at {@code off}; supports 1–2 byte tags and short/long-form lengths. */
    private static Tlv readTlv(byte[] data, int off) {
        if (off >= data.length) {
            return null;
        }
        int p = off;
        int tag = data[p++] & 0xFF;
        if ((tag & 0x1F) == 0x1F) { // multi-byte tag
            tag = (tag << 8) | (data[p++] & 0xFF);
        }
        int len = data[p++] & 0xFF;
        if (len > 0x80) {
            int n = len & 0x7F;
            len = 0;
            for (int i = 0; i < n; i++) {
                len = (len << 8) | (data[p++] & 0xFF);
            }
        }
        int valueStart = p;
        int valueEnd = p + len;
        if (valueEnd > data.length) {
            return null;
        }
        return new Tlv(tag, Arrays.copyOfRange(data, valueStart, valueEnd), valueEnd);
    }
}
