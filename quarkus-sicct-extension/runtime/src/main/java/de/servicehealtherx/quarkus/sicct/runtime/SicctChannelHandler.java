package de.servicehealtherx.quarkus.sicct.runtime;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import sicct.protocol._1._3._0.CTSESSDO;
import sicct.protocol._1._3._0.CommandAPDU;
import sicct.protocol._1._3._0.CommandAPDU.CommandData;
import sicct.protocol._1._3._0.CommandHeader;
import sicct.protocol._1._3._0.SicctClass;
import sicct.protocol._1._3._0.SicctDataObject;
import sicct.protocol._1._3._0.SicctEnvelope;
import sicct.protocol._1._3._0.SicctInstruction;
import sicct.protocol._1._3._0.SicctPayload;
import sicct.protocol._1._3._0.SicctSequenceNumber;

import java.io.IOException;

import org.jboss.logging.Logger;

import com.beanit.asn1bean.ber.types.BerInteger;
import com.beanit.asn1bean.ber.types.BerOctetString;

import de.servicehealtherx.sicct.EhealthTerminalAuthenticate;
import de.servicehealtherx.sicct.ISO7816;
import de.servicehealtherx.sicct.SICCT;
import de.servicehealtherx.sicct.codec.SicctCodec;

/**
 * Netty channel handler for SICCT APDU framing and command dispatch.
 * Manages CT session lifecycle (INIT CT SESSION / CLOSE CT SESSION per FR-092).
 */
public class SicctChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = Logger.getLogger(SicctChannelHandler.class);

    private final SicctTerminalConnection connection;
    private final SicctTerminalManager manager;

    private ChannelHandlerContext ctx; // Store context for sending messages from connection methods

    private int sequenceNumber = 1; // For correlating requests/responses per FR-023, FR-092

    public SicctChannelHandler(SicctTerminalConnection connection, SicctTerminalManager manager) {
        this.connection = connection;
        this.connection.setSicctChannelHandler(this);
        this.manager = manager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        connection.onConnected(ctx.channel());
        LOG.infof("[SICCT] channel active for terminal=%s", connection.getTerminalId());
        // Send INIT CT SESSION to begin correlation per FR-092
        initCtSession();

        // SICCT_GET_STATUS_ALL_ICC
        getStatusAllIcc();

        // SICCT_GET_STATUS_CARD_TERMINAL_MANUFACTURER
        getStatusCardTerminalManufacturer();

        // SICCT_GET_STATUS_ALL_ICC
    }

    private void getStatusAllIcc() {
        SicctEnvelope sicctEnvelop = createSicctEnvelop();
        byte ins = SICCT.INS_GET_STATUS;
        int p1 = SICCT.P1_CARD_TERMINAL;
        // ICC Status Data Object (all ICC Interfaces)
        int p2 = SICCT.P2_GET_STATUS_ALL_ICC;
        assembleAndSendEnvelop(sicctEnvelop, ins, null, p1, p2);
    }

    private void getStatusCardTerminalManufacturer() {
        SicctEnvelope sicctEnvelop = createSicctEnvelop();
        byte ins = SICCT.INS_GET_STATUS;
        int p1 = SICCT.P1_CARD_TERMINAL;
        // ICC Status Data Object (all ICC Interfaces)
        int p2 = 0x46;
        assembleAndSendEnvelop(sicctEnvelop, ins, null, p1, p2);
    }

    void ehealthTerminalAuthenticateCreate() {
        SicctEnvelope sicctEnvelop = createSicctEnvelop();
        byte cla = EhealthTerminalAuthenticate.CLA;
        byte ins = EhealthTerminalAuthenticate.INS;
        int p1 = EhealthTerminalAuthenticate.P1_DIRECT;
        // ICC Status Data Object (all ICC Interfaces)
        int p2 = EhealthTerminalAuthenticate.P2_CREATE;

        byte[] sharedSecret = EhealthTerminalAuthenticate.generateSharedSecret();
        connection.getTerminal().sealedSharedSecret = sharedSecret;
        byte[] createApdu = EhealthTerminalAuthenticate.buildCreate(sharedSecret, "Mit Basis Consumer Health pairen?");

        assembleAndSendEnvelop(cla, sicctEnvelop, ins, createApdu, p1, p2);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connection.onDisconnected();
        manager.onTerminalDisconnected(connection.getTerminalId());
        LOG.infof("[SICCT] channel inactive for terminal=%s", connection.getTerminalId());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // Process SICCT response APDUs
        LOG.debugf("[SICCT] received APDU from terminal=%s", connection.getTerminalId());

        // Handle APDU response, update correlation state, trigger pending operations as
        // needed

        // Events

        // Responses

        // SICCT INIT CT SESSION

        // SICCT REQUEST ICC

        // SICCT EJECT ICC

        // SICCT GET STATUS CARD TERMINAL

        // SICCT GET STATUS ALL ICC

        // SICCT GET STATUS CARD TERMINAL MANUFACTURER

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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.errorf(cause, "[SICCT] channel exception for terminal=%s", connection.getTerminalId());
        ctx.close();
    }

    private void initCtSession() {
        // INIT CT SESSION APDU per FR-092

        sendInitCtSessionApdu();
        // After successful INIT, advance correlation state to ZUGEWIESEN
        connection.onCtSessionInit();
        LOG.infof("[SICCT] CT session initialized for terminal=%s", connection.getTerminalId());
    }

    /**
     * Sends the INIT CT SESSION APDU to the terminal to establish correlation and
     * begin
     * example:
     * 6B 0000 0001 00 0000000E 80 28 00 00 08 690613001300130000
     * - 6B: C-APDU
     * - 0000: wSrcOrDesAddr (0 for messages from Konnektor to terminal)
     * - 0001: Sequence number (incremented for each message)
     * - 00 : reserved for future use
     * - 0000000E: Payload length (14 bytes for the APDU header, no data)
     * - 80 : CLA byte (proprietary class)
     * - 28 : INS byte for INIT CT SESSION
     * - 00 : P1 parameter
     * - 00 : P2 parameter
     * - 08 : Lc byte (length of command data)
     * - 69 : Tag for CTSESSDO in command data
     * - 06 : Length of CTSESSDO (6 bytes for empty username/password/session)
     * - 13 : Tag for BerOctetString (username)
     * - 00 : Length of username (0 for no username)
     * - 13 : Tag for BerOctetString (password)
     * - 00 : Length of password (0 for no password)
     * - 13 : Tag for BerOctetString (Session)
     * - 00 : Length of session (0 for no session)
     * - 00 : No Le byte since this is a command without expected response data
     * 
     * @param ctx
     */
    private void sendInitCtSessionApdu() {
        SicctEnvelope sicctEnvelop = createSicctEnvelop();

        // 0x28 for INIT CT SESSION per FR-092
        byte ins = SICCT.INS_INIT_CT_SESSION;
        CTSESSDO ctSessDO = getCtSessDO();
        int p1 = SICCT.P1_CARD_TERMINAL;
        int p2 = 0x00;

        assembleAndSendEnvelop(sicctEnvelop, ins, ctSessDO, p1, p2);

    }

    public void assembleAndSendEnvelop(SicctEnvelope sicctEnvelop, byte ins,
            Object dataObject, int p1,
            int p2) {
        assembleAndSendEnvelop(ISO7816.CLA_PROPRIETARY, sicctEnvelop, ins, dataObject, p1, p2);
    }

    private void assembleAndSendEnvelop(int cla, SicctEnvelope sicctEnvelop, byte ins,
            Object dataObject, int p1,
            int p2) {

        CommandHeader commandHeader = new CommandHeader();
        // 0x80
        commandHeader.setCla(new SicctClass(cla));
        commandHeader.setIns(new SicctInstruction(ins));

        commandHeader.setP1(new BerInteger((byte) p1)); // No parameters for INIT CT SESSION
        commandHeader.setP2(new BerInteger((byte) p2));

        SicctDataObject sicctDataObject = new SicctDataObject();
        if (dataObject instanceof CTSESSDO) {
            sicctDataObject.setCtsess((CTSESSDO) dataObject);
        } else if (dataObject instanceof byte[]) {
            sicctDataObject = new SicctDataObject((byte[]) dataObject);
        }

        CommandData commandData = new CommandData();
        commandData.getSicctDataObject().add(sicctDataObject);

        CommandAPDU commandAPDU = new CommandAPDU();
        commandAPDU.setHeader(commandHeader);
        commandAPDU.setCommandData(commandData);

        SicctPayload payload = new SicctPayload();
        payload.setCommandApdu(commandAPDU);

        sicctEnvelop.setAbCmd(payload);

        if (dataObject != null) {
            sicctEnvelop.setDwLength(SICCT.length(payload));
        } else {
            // For commands without data objects, length is just the APDU header (4 bytes)
            sicctEnvelop.setDwLength(new BerInteger(0));
        }

        sendSicctEnvelope(sicctEnvelop);
    }

    private CTSESSDO getCtSessDO() {
        CTSESSDO ctSessDO = new CTSESSDO();
        ctSessDO.setSessionId(new BerOctetString(new byte[] {}));
        ctSessDO.setUsername(new BerOctetString(new byte[] {}));
        ctSessDO.setPassword(new BerOctetString(new byte[] {}));
        if (connection.getTerminal() != null) {
            if (connection.getTerminal().adminUsername != null) {
                ctSessDO.setUsername(new BerOctetString(connection.getTerminal().adminUsername.getBytes()));
            }
            if (connection.getTerminal().adminPassword != null) {
                ctSessDO.setPassword(new BerOctetString(connection.getTerminal().adminPassword.getBytes()));
            }
        } else {
            LOG.warnf(
                    "[SICCT] No terminal information available for terminal=%s, sending INIT CT SESSION with empty credentials",
                    connection.getTerminalId());
        }
        return ctSessDO;
    }

    private SicctEnvelope createSicctEnvelop() {
        SicctEnvelope sicctEnvelop = new SicctEnvelope();

        sicctEnvelop.setBMessageType(SICCT.C_COMMAND);
        sicctEnvelop.setWSrcOrDesAddr(SICCT.TERMINAL_ADDRESS); // Arbitrary message ID for INIT CT SESSION
        sicctEnvelop.setWSeq(new SicctSequenceNumber(sequenceNumber));
        sequenceNumber++; // Increment for next message
        sicctEnvelop.setAbRFU(new BerOctetString(new byte[] {}));
        return sicctEnvelop;
    }

    private void sendSicctEnvelope(SicctEnvelope sicctEnvelop) {
        ByteBuf in = SicctCodec.encode(sicctEnvelop);
        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        LOG.debugf("[SICCT] sending %s to terminal=%s", bytesToHex(bytes), connection.getTerminalId());
        in.resetReaderIndex();
        ctx.writeAndFlush(in);

    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
