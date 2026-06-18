package de.servicehealtherx.quarkus.sicct.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.beanit.asn1bean.ber.types.BerInteger;
import com.beanit.asn1bean.ber.types.BerOctetString;

import de.servicehealtherx.sicct.EhealthTerminalAuthenticate;
import de.servicehealtherx.sicct.SICCT;
import de.servicehealtherx.sicct.codec.IccStatusDecoder.IccStatusValue;
import de.servicehealtherx.sicct.codec.SicctCodec;
import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import sicct.protocol._1._3._0.ResponseAPDU;
import sicct.protocol._1._3._0.SicctEnvelope;
import sicct.protocol._1._3._0.SicctPayload;
import sicct.protocol._1._3._0.SicctSequenceNumber;
import sicct.protocol._1._3._0.StatusWord;

/**
 * Deterministic, socket-free coverage of {@link SicctChannelHandler#channelRead}. Each test records
 * a sent command (so the response can be correlated by sequence number) and then feeds the matching
 * Response-APDU/event into the handler, asserting it is processed by the correct branch.
 */
public class SicctChannelHandlerResponseTest {

    // Captured Cherry ST-1506 answers (see virtual-nfc-card-terminal CherryTest).
    private static final String INIT_CT_SESSION_RESPONSE =
            "83000000010000000016691213001300130C306C374569654A76534176439000";
    private static final String GET_STATUS_ALL_ICC_RESPONSE =
            "830000000300000000088004000015009000";
    private static final String GET_STATUS_MANUFACTURER_RESPONSE =
            "830000000400000000484644444543485930313230203031303020D7332020312020302"
                    + "020304B54202031202037202031202053543135303620203235303033303920203420203020203030303130399000";
    private static final String RESET_CT_RESPONSE =
            "830000000800000000105F410B3BD097FF81B1FE451FC7EB9001";
    private static final String SELECT_EGK_RESPONSE =
            "830001000A0000000017621383023F008A01058201788407D27600014480009000";
    private static final String CARD_INSERTED_EVENT = "5000000009000000000484020001";

    private CardTerminal terminal;
    private SicctTerminalConnection connection;
    private SicctChannelHandler handler;
    private ChannelHandlerContext ctx;

    @BeforeEach
    public void setUp() {
        terminal = new CardTerminal();
        terminal.hostname = "unit-terminal";
        terminal.macAddress = "aa:bb:cc:dd:ee:ff";
        connection = new SicctTerminalConnection(terminal);
        handler = new SicctChannelHandler(connection, null);
        ctx = mock(ChannelHandlerContext.class);
        // channelActive stores the context and emits INIT CT SESSION + GET STATUS (recorded as seq 1-3).
        handler.channelActive(ctx);
    }

    @Test
    public void test_init_ct_session_response_extracts_session_id() throws IOException {
        handler.channelRead(ctx, decode(INIT_CT_SESSION_RESPONSE)); // seq 1 == INIT CT SESSION
        assertEquals("0l7EieJvSAvC", handler.getSessionId());
    }

    @Test
    public void test_get_status_all_icc_response_decodes_status() throws IOException {
        // Canned all-ICC answer carries sequence 0x0003; record the matching GET STATUS command.
        sendCommand(3, SICCT.INS_GET_STATUS, SICCT.P1_CARD_TERMINAL, SICCT.P2_GET_STATUS_ALL_ICC);
        handler.channelRead(ctx, decode(GET_STATUS_ALL_ICC_RESPONSE));
        assertEquals(4, handler.getLastIccStatus().size());
        assertTrue(handler.getLastIccStatus().contains(IccStatusValue.CC_SPECIFIC));
    }

    @Test
    public void test_get_status_manufacturer_response_persists_product_info() throws IOException {
        // Canned manufacturer answer carries sequence 0x0004; record the matching GET STATUS command.
        sendCommand(4, SICCT.INS_GET_STATUS, SICCT.P1_CARD_TERMINAL,
                SICCT.P2_GET_STATUS_CARD_TERMINAL_MANUFACTURER);
        handler.channelRead(ctx, decode(GET_STATUS_MANUFACTURER_RESPONSE));
        assertNotNull(handler.getManufacturerInfo());
        assertTrue(handler.getManufacturerInfo().startsWith("DECHY0120"), handler.getManufacturerInfo());
        assertEquals(handler.getManufacturerInfo(), terminal.productInformation);
    }

    @Test
    public void test_reset_ct_response_extracts_atr() throws IOException {
        // Reset response carries sequence number 0x0008; record a RESET command under the same seq.
        sendCommand(8, SICCT.INS_RESET_CT_ICC, 0x03, 0x00);
        handler.channelRead(ctx, decode(RESET_CT_RESPONSE));
        assertNotNull(handler.getLastAtr());
        assertEquals("3bd097ff81b1fe451fc7eb", HexFormat.of().formatHex(handler.getLastAtr()));
    }

    @Test
    public void test_ehealth_validate_success_marks_terminal_active() {
        sendCommand(20, SICCT.INS_EHEALTH_TERMINAL_AUTHENTICATE, EhealthTerminalAuthenticate.P1_DIRECT,
                EhealthTerminalAuthenticate.P2_VALIDATE);
        handler.channelRead(ctx, statusResponse(20, 0x90, 0x00));
        assertTrue(connection.isAktiv(), "VALIDATE 9000 must drive the terminal to AKTIV");
    }

    @Test
    public void test_transparent_apdu_response_without_recorded_command_is_handled() throws IOException {
        // A card response (FCP template) with no correlated command must be handled, not throw.
        handler.channelRead(ctx, decode(SELECT_EGK_RESPONSE));
        // No state assertion: success is that channelRead processes it as a generic APDU response.
    }

    @Test
    public void test_card_inserted_event_is_processed() throws IOException {
        handler.channelRead(ctx, decode(CARD_INSERTED_EVENT));
        assertEquals("CARD_INSERTED:0001", handler.getLastEvent());
    }

    private void sendCommand(int seq, byte ins, int p1, int p2) {
        SicctEnvelope command = new SicctEnvelope();
        command.setBMessageType(SICCT.C_COMMAND);
        command.setWSrcOrDesAddr(SICCT.TERMINAL_ADDRESS);
        command.setWSeq(new SicctSequenceNumber(seq));
        command.setAbRFU(new BerOctetString(new byte[] {}));
        handler.assembleAndSendEnvelop(ins, p1, p2, command, null);
    }

    private SicctEnvelope statusResponse(int seq, int sw1, int sw2) {
        SicctEnvelope envelope = new SicctEnvelope();
        envelope.setBMessageType(SICCT.R_COMMAND);
        envelope.setWSrcOrDesAddr(SICCT.TERMINAL_ADDRESS);
        envelope.setWSeq(new SicctSequenceNumber(seq));
        ResponseAPDU responseApdu = new ResponseAPDU();
        StatusWord trailer = new StatusWord();
        trailer.setSw1(new BerInteger(sw1));
        trailer.setSw2(new BerInteger(sw2));
        responseApdu.setTrailer(trailer);
        responseApdu.setResponseData(new ResponseAPDU.ResponseData());
        SicctPayload payload = new SicctPayload();
        payload.setResponseApdu(responseApdu);
        envelope.setAbCmd(payload);
        return envelope;
    }

    private SicctEnvelope decode(String hex) throws IOException {
        ByteBuf buf = Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex));
        return SicctCodec.decode(buf);
    }
}
