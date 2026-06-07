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
/*
    @Test
    public void test_decode() throws Exception {

        SicctDecoder decoder = new SicctDecoder();
        // This message should be send but not received
        // SICCT INIT CT SESSION
        List<Object> out = new ArrayList<>();
        int i = 1;
        decoder.decode(null, toByteBuf("83000000010000000016691213001300130C3030303030303030303033319000"), out);
        assertEquals(out.size(), i + 1);
        assertTrue(out.get(i) instanceof SicctEnvelope);

        // SICCT REQUEST ICC

        decoder.decode(null, toByteBuf("830000000200000000088004030303039000"), out);
        assertEquals(out.size(), i + 1);
        assertTrue(out.get(i) instanceof SicctEnvelope);

        // SICCT EJECT ICC

        // SICCT GET STATUS CARD TERMINAL

        // SICCT GET STATUS ALL ICC

        // SICCT GET STATUS CARD TERMINAL MANUFACTURER

        decoder.decode(null, toByteBuf(
                "83000000030000000081467D494E47484330313230203033303932D76C2020312020302020304B542020312020382020304F52474136313030202033202039202032202031202032202030303030323732342E30372E323032352D202020202020202020202020202020202020202020202020202020202020203031343130303030303231464231209000"),
                out);
        assertEquals(out.size(), i + 1);
        assertTrue(out.get(i) instanceof SicctEnvelope);

        // SICCT Interface Capabilities Data Object For Display

        // SICCT Interface Capabilities Data Object For Slot

        // SICCT ICC STATUS

        // SICCT Interface Capabilities Functional Unit Data Object

        // SICCT CLOSE CT SESSION

        // EHEALTH TERMINAL AUTHENTICATE CREATE

        // EHEALTH TERMINAL AUTHENTICATE VALIDATE

        // EHEALTH TERMINAL AUTHENTICATE ADD NOT EXPECTING

        // EHEALTH TERMINAL AUTHENTICATE ADD EXPECTING

        // SICCT RESET CT

        // SICCT OUTPUT

        // SICCT PERFORM VERIFICATION

        // SICCT APDU Response
    }
 */
}
