package de.servicehealtherx.quarkus.sicct.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.calls;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import sicct.protocol._1._3._0.SicctEnvelope;

public class SicctDecoderHandlerTest {

    public static ByteBuf toByteBuf(String hex) {
        byte[] bytes = HexFormat.of().parseHex(hex); // Java 17+
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        return buf;
    }

    @Test
    public void test_decode_init_ct_session() throws Exception {
        SicctDecoder decoder = new SicctDecoder();
        List<Object> out = new ArrayList<>();
        decoder.decode(null, toByteBuf("83000000010000000016691213001300130C3030303030303030303033319000"), out);
        assertEquals(1, out.size());
        assertTrue(out.get(0) instanceof SicctEnvelope);
        SicctEnvelope envelope = (SicctEnvelope) out.get(0);
        assertEquals(0x83L, envelope.getBMessageType().longValue());
        assertEquals(0x0000L, envelope.getWSrcOrDesAddr().longValue());
        assertEquals(0x0001L, envelope.getWSeq().longValue());
        assertEquals(0x16L, envelope.getDwLength().longValue());
    }

}
