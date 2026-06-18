package de.servicehealtherx.cetp.delivery;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Frame format per gemSpec_Kon TIP1-A_4596. */
class CetpFrameCodecTest {

    @Test
    void test_TIP1_A_4596_frame_starts_with_CETP_and_big_endian_length() {
        byte[] body = "<Event/>".getBytes(StandardCharsets.UTF_8);

        byte[] frame = CetpFrameCodec.encode(body);

        // Magic "CETP"
        assertEquals('C', frame[0]);
        assertEquals('E', frame[1]);
        assertEquals('T', frame[2]);
        assertEquals('P', frame[3]);
        // 4-byte big-endian length of the body
        int len = ((frame[4] & 0xFF) << 24) | ((frame[5] & 0xFF) << 16)
                | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
        assertEquals(body.length, len);
        // Body follows verbatim
        byte[] carried = new byte[body.length];
        System.arraycopy(frame, 8, carried, 0, body.length);
        assertArrayEquals(body, carried);
    }

    @Test
    void test_TIP1_A_4596_length_is_big_endian_for_large_body() {
        byte[] body = new byte[258]; // 258 = 0x0102
        byte[] frame = CetpFrameCodec.encode(body);
        assertEquals(0x00, frame[4] & 0xFF);
        assertEquals(0x00, frame[5] & 0xFF);
        assertEquals(0x01, frame[6] & 0xFF); // high byte of 258 before low byte
        assertEquals(0x02, frame[7] & 0xFF);
    }
}
