package de.servicehealtherx.apdu.c2c;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

class CvcChainParserTest {

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (tag > 0xFF) {
            out.write(tag >> 8);
        }
        out.write(tag & 0xFF);
        out.write(value.length);
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    @Test
    void parses_car_chr_chat_and_public_point() {
        byte[] car = tlv(0x42, "CAR".getBytes());
        byte[] chr = tlv(0x5F20, "CHR".getBytes());
        byte[] point = tlv(0x86, new byte[]{(byte) 0xAB, (byte) 0xCD});
        byte[] pub = tlv(0x7F49, point);
        byte[] chat = tlv(0x7F4C, new byte[]{0x00, 0x03});
        byte[] cvc = tlv(0x7F21, tlv(0x7F4E, concat(car, chr, pub, chat)));

        CvCertificate parsed = new CvcChainParser().parse(cvc);

        assertArrayEquals("CAR".getBytes(), parsed.car());
        assertArrayEquals("CHR".getBytes(), parsed.chr());
        assertArrayEquals(new byte[]{0x00, 0x03}, parsed.chat());
        assertArrayEquals(new byte[]{(byte) 0xAB, (byte) 0xCD}, parsed.publicPoint());
        assertTrue(parsed.isRootAnchor(), "CHAT bits b0,b1=11 → root anchor");
    }

    @Test
    void rejects_input_that_is_not_a_cvc() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvcChainParser().parse(new byte[]{0x30, 0x01, 0x00}));
    }

    @Test
    void rejects_cvc_without_a_body() {
        byte[] cvc = tlv(0x7F21, tlv(0x42, "CAR".getBytes())); // 7F21 but no 7F4E body
        assertThrows(IllegalArgumentException.class, () -> new CvcChainParser().parse(cvc));
    }
}
