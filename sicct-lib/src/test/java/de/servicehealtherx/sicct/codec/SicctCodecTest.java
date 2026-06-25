package de.servicehealtherx.sicct.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import sicct.protocol._1._3._0.CTMDO;
import sicct.protocol._1._3._0.CTSESSDO;
import sicct.protocol._1._3._0.ICCSDO;
import sicct.protocol._1._3._0.ResponseAPDU;
import sicct.protocol._1._3._0.ResponseAPDU.ResponseData;
import sicct.protocol._1._3._0.SicctDataObject;
import sicct.protocol._1._3._0.SicctEnvelope;

public class SicctCodecTest {

    @Test
    public void test_decode_receive_init_ct_session() throws Exception {
        // SICCT INIT CT SESSION
        SicctEnvelope sicctEnvelope = SicctCodec
                .decode(toByteBuf(
                        "83000000010000000023691F130561646D696E130870617373776F7264130C3030303030303030303033319000"));
        assertEquals(0x83L, sicctEnvelope.getBMessageType().longValue());
        assertEquals(0x0000L, sicctEnvelope.getWSrcOrDesAddr().longValue());
        assertEquals(0x0001L, sicctEnvelope.getWSeq().longValue());
        assertEquals(0x23L, sicctEnvelope.getDwLength().longValue());
        assertTrue(sicctEnvelope.getAbCmd() != null);

        CTSESSDO ctsess = sicctEnvelope.getAbCmd().getResponseApdu().getResponseData().getSicctDataObject().get(0)
                .getCtsess();
        assertEquals("admin", new String(ctsess.getUsername().value));
        assertEquals("password", new String(ctsess.getPassword().value));
        assertEquals("000000000031", new String(ctsess.getSessionId().value));
    }

    @Test
    public void test_get_staus_all_icc() throws Exception {
        // SICCT REQUEST ICC

        // SICCT EJECT ICC

        // SICCT GET STATUS CARD TERMINAL

        // SICCT GET STATUS ALL ICC
        SicctEnvelope sicctEnvelope = SicctCodec.decode(toByteBuf("8300000002000000000C8008000103050D1580FF9000"));
        assertEquals(0x83L, sicctEnvelope.getBMessageType().longValue());
        assertEquals(0x0000L, sicctEnvelope.getWSrcOrDesAddr().longValue());
        assertEquals(0x0002L, sicctEnvelope.getWSeq().longValue());
        assertEquals(0x0CL, sicctEnvelope.getDwLength().longValue());
        assertTrue(sicctEnvelope.getAbCmd() != null);

        ICCSDO o = sicctEnvelope.getAbCmd().getResponseApdu().getResponseData().getSicctDataObject()
                .get(0).getIccs();
        List<IccStatusDecoder.IccStatusValue> iccStatusList = IccStatusDecoder.decode(o.value);
        assertEquals(8, iccStatusList.size());
        assertEquals(IccStatusDecoder.IccStatusValue.CC_ABSENT, iccStatusList.get(0));
        assertEquals(IccStatusDecoder.IccStatusValue.CC_PRESENT, iccStatusList.get(1));
        assertEquals(IccStatusDecoder.IccStatusValue.CC_SWALLOWED, iccStatusList.get(2));
        assertEquals(IccStatusDecoder.IccStatusValue.CC_POWERED, iccStatusList.get(3));
        assertEquals(IccStatusDecoder.IccStatusValue.CC_NEGOTIABLE, iccStatusList.get(4));
        assertEquals(IccStatusDecoder.IccStatusValue.CC_SPECIFIC, iccStatusList.get(5));
        assertEquals(IccStatusDecoder.IccStatusValue.CC_UNKNOWN, iccStatusList.get(6));
        assertEquals(IccStatusDecoder.IccStatusValue.CC_UNKNOWN, iccStatusList.get(7));
    }

    @Test
    public void test_get_status_card_terminal_manufacturer() throws Exception {
        // SICCT GET STATUS CARD TERMINAL MANUFACTURER

        SicctEnvelope sicctEnvelope = SicctCodec.decode(toByteBuf(
                "83000000030000000081467D494E47484330313230203033303932D76C2020312020302020304B542020312020382020304F52474136313030202033202039202032202031202032202030303030323732342E30372E323032352D202020202020202020202020202020202020202020202020202020202020203031343130303030303231464231209000"));

        assertEquals(0x83L, sicctEnvelope.getBMessageType().longValue());
        assertEquals(0x0000L, sicctEnvelope.getWSrcOrDesAddr().longValue());
        assertEquals(0x0003L, sicctEnvelope.getWSeq().longValue());
        assertEquals(0x81L, sicctEnvelope.getDwLength().longValue());
        assertTrue(sicctEnvelope.getAbCmd() != null);

        // Does not work because tag byte is not correctly read
        // CTMDO o =
        // sicctEnvelope.getAbCmd().getResponseApdu().getResponseData().getSicctDataObject()
        // .get(0).getCtm();
        // assertEquals("INGHCHC", new String(o.getCtm().value));
        // assertEquals("0120", new String(o.getCtt().value));
        // assertEquals("03092", new String(o.getCtsv().value));

        // assertEquals("03092", new String(o.getDd().value));

    }

    // SICCT Interface Capabilities Data Object For Display

    // SICCT Interface Capabilities Data Object For Slot

    // SICCT ICC STATUS

    // SICCT Interface Capabilities Functional Unit Data Object

    // SICCT CLOSE CT SESSION

    @Test
    public void test_EHEALTH_TERMINAL_AUTHENTICATE_CREATE() throws Exception {
        // EHEALTH TERMINAL AUTHENTICATE CREATE

        SicctEnvelope sicctEnvelope = SicctCodec.decode(toByteBuf(
                "830000000400000000425587F367213E3F285D7C909E1424F88331C797AA17BEC7676EC851DA7464FCF308381AE9E261E2BC40D34615FA58A3CF0EE6802DDD3DEFA45BE2CE984F9FC6A29000"));

        assertEquals(0x83L, sicctEnvelope.getBMessageType().longValue());
        assertEquals(0x0000L, sicctEnvelope.getWSrcOrDesAddr().longValue());
        assertEquals(0x0004L, sicctEnvelope.getWSeq().longValue());
        assertEquals(0x42L, sicctEnvelope.getDwLength().longValue());
        assertTrue(sicctEnvelope.getAbCmd() != null);

        ResponseData responseData = sicctEnvelope.getAbCmd().getResponseApdu().getResponseData();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        responseData.encode(baos, false);

        assertEquals(
                "5587F367213E3F285D7C909E1424F88331C797AA17BEC7676EC851DA7464FCF308381AE9E261E2BC40D34615FA58A3CF0EE6802DDD3DEFA45BE2CE984F9FC6A2",
                HexFormat.of().formatHex(baos.toByteArray()).toUpperCase());
        // EHEALTH TERMINAL AUTHENTICATE VALIDATE

        // EHEALTH TERMINAL AUTHENTICATE ADD NOT EXPECTING

        // EHEALTH TERMINAL AUTHENTICATE ADD EXPECTING

        // SICCT RESET CT

        // SICCT OUTPUT

        // SICCT PERFORM VERIFICATION

        // SICCT APDU Response
    }

    @Test
    public void test_create_signature_reconstructs_with_and_without_status_word_trailer() throws Exception {
        // The codec splits the last two payload bytes into the status word. The channel handler
        // relies on  data field + SW1 SW2  reconstructing the original payload exactly, so it can
        // recover the full gSMC-KT signature whether the terminal terminates the response with
        // SW 9000 (conformant) or returns the bare signature (last two bytes mis-read as the SW).
        String signatureHex =
                "5587F367213E3F285D7C909E1424F88331C797AA17BEC7676EC851DA7464FCF30"
                        + "8381AE9E261E2BC40D34615FA58A3CF0EE6802DDD3DEFA45BE2CE984F9FC6A2";
        for (String payloadHex : List.of(signatureHex + "9000", signatureHex)) {
            SicctEnvelope env = SicctCodec.decode(toByteBuf(frame(payloadHex)));
            ResponseAPDU apdu = env.getAbCmd().getResponseApdu();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            apdu.getResponseData().encode(body, false);
            body.write(apdu.getTrailer().getSw1().intValue() & 0xFF);
            body.write(apdu.getTrailer().getSw2().intValue() & 0xFF);

            assertEquals(payloadHex.toUpperCase(),
                    HexFormat.of().formatHex(body.toByteArray()).toUpperCase());
        }
    }

    /** Wrap a Response-APDU payload in a 10-byte SICCT response envelope (type 83, seq 4). */
    private static String frame(String payloadHex) {
        return "830000000400" + String.format("%08X", payloadHex.length() / 2) + payloadHex;
    }

    public static ByteBuf toByteBuf(String hex) {
        byte[] bytes = HexFormat.of().parseHex(hex); // Java 17+
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        return buf;
    }
}
