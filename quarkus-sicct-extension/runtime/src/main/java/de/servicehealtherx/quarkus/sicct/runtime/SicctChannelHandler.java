package de.servicehealtherx.quarkus.sicct.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import com.beanit.asn1bean.ber.types.BerInteger;
import com.beanit.asn1bean.ber.types.BerOctetString;

import de.gematik.pki.gemlibpki.commons.utils.CertReader;
import de.servicehealtherx.sicct.EhealthTerminalAuthenticate;
import de.servicehealtherx.sicct.ISO7816;
import de.servicehealtherx.sicct.SICCT;
import de.servicehealtherx.sicct.codec.IccStatusDecoder;
import de.servicehealtherx.sicct.codec.IccStatusDecoder.IccStatusValue;
import de.servicehealtherx.sicct.codec.SicctCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import sicct.protocol._1._3._0.ATRDO;
import sicct.protocol._1._3._0.CTSDO;
import sicct.protocol._1._3._0.CTSESSDO;
import sicct.protocol._1._3._0.CommandAPDU;
import sicct.protocol._1._3._0.CommandAPDU.CommandData;
import sicct.protocol._1._3._0.CommandHeader;
import sicct.protocol._1._3._0.EventNotification;
import sicct.protocol._1._3._0.FuNumber;
import sicct.protocol._1._3._0.ICCSDO;
import sicct.protocol._1._3._0.INTFCDO;
import sicct.protocol._1._3._0.ResponseAPDU;
import sicct.protocol._1._3._0.SicctClass;
import sicct.protocol._1._3._0.SicctDataObject;
import sicct.protocol._1._3._0.SicctEnvelope;
import sicct.protocol._1._3._0.SicctInstruction;
import sicct.protocol._1._3._0.SicctMessageType;
import sicct.protocol._1._3._0.SicctPayload;
import sicct.protocol._1._3._0.SicctSequenceNumber;
import sicct.protocol._1._3._0.StatusWord;

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

    public Map<Integer, Consumer<SicctEnvelope>> pendingOperations = new ConcurrentHashMap<>();

    /**
     * Remembers the command (CLA/INS/P1/P2) that was sent under each sequence number so the
     * matching response can be classified. A SICCT Response-APDU does not echo the instruction
     * byte, so the only way to know which command it answers is the sequence number (FR-023).
     */
    private final Map<Integer, SentCommand> sentCommands = new ConcurrentHashMap<>();

    private X509Certificate terminalTLSCertificate;

    // Last-observed values extracted from terminal responses/events. Kept so callers (and tests)
    // can observe how the channel handler processed the messages.
    private volatile String sessionId;
    private volatile String manufacturerInfo;
    private volatile List<IccStatusValue> lastIccStatus = List.of();
    private volatile byte[] lastAtr;
    private volatile String lastEvent;

    /** A SICCT command-APDU header that was sent to the terminal, kept for response correlation. */
    record SentCommand(int cla, byte ins, int p1, int p2) {
    }

    public SicctChannelHandler(SicctTerminalConnection connection, SicctTerminalManager manager) {
        this.connection = connection;
        this.manager = manager;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        super.userEventTriggered(ctx, evt);
        // when the ssl handshake is successful
        if (evt == SslHandshakeCompletionEvent.SUCCESS) {
            SslHandler sslhandler = (SslHandler) ctx.channel().pipeline().get("ssl");
            terminalTLSCertificate = (X509Certificate) sslhandler.engine().getSession().getPeerCertificates()[0];
            byte[] savedCertBytes = connection.getTerminal().smktAutCertificate;

            if (savedCertBytes == null) {
                LOG.warnf(
                        "[SICCT] TLS handshake successful for terminal=%s, but no SMK Aut certificate was previously stored! Saving new certificate: %s",
                        connection.getTerminalId(),
                        terminalTLSCertificate.getSubjectX500Principal().getName());
                connection.getTerminal().smktAutCertificate = terminalTLSCertificate.getEncoded();
                return;
            }
            X509Certificate savedCert = CertReader.readX509(savedCertBytes);
            if (!terminalTLSCertificate.equals(savedCert)) {
                LOG.warnf(
                        "[SICCT] TLS handshake successful for terminal=%s, but SMK Aut certificate has changed! Previous: %s, New: %s",
                        connection.getTerminalId(),
                        savedCert.getSubjectX500Principal().getName(),
                        terminalTLSCertificate.getSubjectX500Principal().getName());
            } else {
                LOG.debugf(
                        "[SICCT] TLS handshake successful for terminal=%s, SMK Aut certificate matches previously stored certificate: %s",
                        connection.getTerminalId(),
                        terminalTLSCertificate.getSubjectX500Principal().getName());
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        connection.onConnected(this, ctx.channel());
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
        assembleAndSendEnvelop(ins, p1, p2, sicctEnvelop, null);
    }

    private void getStatusCardTerminalManufacturer() {
        SicctEnvelope sicctEnvelop = createSicctEnvelop();
        byte ins = SICCT.INS_GET_STATUS;
        int p1 = SICCT.P1_CARD_TERMINAL;
        // ICC Status Data Object (all ICC Interfaces)
        int p2 = 0x46;
        assembleAndSendEnvelop(ins, p1, p2, sicctEnvelop, null);
    }

    void ehealthTerminalAuthenticateCreate() {

        byte[] sharedSecret = EhealthTerminalAuthenticate.generateSharedSecret();
        SicctEnvelope sicctEnvelop = createSicctEnvelop((sicctEnvelope) -> {
            ByteArrayOutputStream signedData = new ByteArrayOutputStream();
            try {
                sicctEnvelope.getAbCmd().getResponseApdu().getResponseData().encode(signedData, false);
                byte[] signatureBytes = signedData.toByteArray();
                LOG.debugf("Shared secret: %s Signature: %s", HexFormat.of().formatHex(sharedSecret),
                        HexFormat.of().formatHex(signatureBytes));
                validateSignature(sharedSecret,
                        signatureBytes, terminalTLSCertificate);
            } catch (IOException | GeneralSecurityException e) {
                LOG.errorf(e, "Error validating signature with sharedSecret");

            }
        });
        connection.getTerminal().sealedSharedSecret = sharedSecret;
        // Display Message „KT:$CT.MAC_ADRESS MIT KON:$MGM_KONN_HOSTNAME PAIREN OK?“,
        // wobei die MAC-Adresse mit Trenner im folgenden Format dargestellt werden
        // MUSS: „AABBCC:DDEEFF“
        byte[] createApdu = EhealthTerminalAuthenticate.buildCreate(sharedSecret,
                "KT:" + formatMacAddressForDisplay(connection.getTerminal().macAddress) + " MIT\n" + //
                        "      KON:" + manager.getHostname() + " PAIREN OK?");
        sicctEnvelop.setDwLength(new BerInteger(createApdu.length));
        sicctEnvelop.setAbCmd(new SicctPayload(createApdu));
        rememberSentCommand(sicctEnvelop, EhealthTerminalAuthenticate.CLA,
                EhealthTerminalAuthenticate.INS, EhealthTerminalAuthenticate.P1_DIRECT,
                EhealthTerminalAuthenticate.P2_CREATE);
        sendSicctEnvelope(sicctEnvelop);

    }

    private void rememberSentCommand(SicctEnvelope envelope, int cla, byte ins, int p1, int p2) {
        sentCommands.put(envelope.getWSeq().intValue(),
                new SentCommand(cla, ins, p1 & 0xFF, p2 & 0xFF));
    }

    private void validateSignature(byte[] sharedSecret, byte[] signatureBytes,
            X509Certificate terminalTLSCertificate) throws GeneralSecurityException {

        // 1. SHA-256 über sharedSecret bilden
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sharedSecretHash = digest.digest(sharedSecret);

        // 4. Algorithmus je nach Zertifikatstyp bestimmen
        PublicKey publicKey = terminalTLSCertificate.getPublicKey();
        String algorithm = switch (publicKey.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> throw new GeneralSecurityException(
                    "Unsupported key algorithm: " + publicKey.getAlgorithm());
        };

        // 5. Signature-Objekt initialisieren
        Signature signature = Signature.getInstance(algorithm, "BC");
        signature.initVerify(publicKey);

        // 6. sharedSecretHash + Nutzdaten einspeisen
        signature.update(sharedSecretHash);

        // 7. Validieren
        boolean valid = signature.verify(signatureBytes);
        if (!valid) {
            throw new GeneralSecurityException("Signature validation failed");
        }
    }

    static String formatMacAddressForDisplay(String macAddress) {
        return macAddress.toUpperCase().replaceAll("(.{2}):(.{2}):(.{2}):(.{2}):(.{2}):(.{2})", "$1$2$3:$4$5$6");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connection.onDisconnected();
        manager.onTerminalDisconnected(connection.getTerminalId());
        LOG.infof("[SICCT] channel inactive for terminal=%s", connection.getTerminalId());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        LOG.debugf("[SICCT] received message from terminal=%s", connection.getTerminalId());

        if (!(msg instanceof SicctEnvelope)) {
            LOG.warnf("[SICCT] ignoring non-SICCT message from terminal=%s: %s",
                    connection.getTerminalId(), msg);
            return;
        }

        SicctEnvelope sicctEnvelope = (SicctEnvelope) msg;
        int seq = sicctEnvelope.getWSeq().intValue();

        // Unsolicited EVENT notifications (bMessageType = 0x50) are not correlated to a command.
        if (isEvent(sicctEnvelope)) {
            handleEvent(sicctEnvelope);
            return;
        }

        // Dispatch to any operation that registered a response handler for this sequence number
        // (e.g. EHEALTH TERMINAL AUTHENTICATE CREATE validates the returned signature).
        Consumer<SicctEnvelope> operation = pendingOperations.remove(seq);
        if (operation != null) {
            LOG.debugf("[SICCT] dispatching response seq=%d to pending operation for terminal=%s",
                    seq, connection.getTerminalId());
            operation.accept(sicctEnvelope);
        }

        // Classify the response by the command that was sent under the same sequence number.
        SentCommand command = sentCommands.remove(seq);
        if (command == null) {
            LOG.debugf("[SICCT] response seq=%d has no recorded command, treating as APDU response", seq);
            handleApduResponse(sicctEnvelope, null);
            return;
        }

        switch (command.ins()) {
            case SICCT.INS_INIT_CT_SESSION -> handleInitCtSessionResponse(sicctEnvelope);
            case SICCT.INS_REQUEST_ICC -> handleRequestIccResponse(sicctEnvelope, command);
            case SICCT.INS_EJECT_ICC -> handleEjectIccResponse(sicctEnvelope, command);
            case SICCT.INS_GET_STATUS -> handleGetStatusResponse(sicctEnvelope, command);
            case SICCT.INS_CLOSE_CT_SESSION -> handleCloseCtSessionResponse(sicctEnvelope);
            case SICCT.INS_RESET_CT_ICC -> handleResetCtResponse(sicctEnvelope, command);
            case SICCT.INS_OUTPUT -> handleOutputResponse(sicctEnvelope);
            case SICCT.INS_PERFORM_VERIFICATION -> handlePerformVerificationResponse(sicctEnvelope);
            case SICCT.INS_EHEALTH_TERMINAL_AUTHENTICATE -> handleEhealthAuthenticateResponse(sicctEnvelope, command);
            default -> handleApduResponse(sicctEnvelope, command);
        }
    }

    // -------------------------------------------------------------------------
    // EVENT processing (SICCT 5.5 EventNotification)
    // -------------------------------------------------------------------------

    private void handleEvent(SicctEnvelope envelope) {
        EventNotification event = envelope.getAbCmd() != null ? envelope.getAbCmd().getEventData() : null;
        if (event == null) {
            LOG.warnf("[SICCT] received EVENT message without decodable EventNotification from terminal=%s",
                    connection.getTerminalId());
            return;
        }

        if (event.getKeepAlive() != null) {
            lastEvent = "KEEP_ALIVE";
            LOG.debugf("[SICCT] keep-alive from terminal=%s fu=%s",
                    connection.getTerminalId(), fu(event.getKeepAlive()));
        } else if (event.getTerminalSignOff() != null) {
            lastEvent = "TERMINAL_SIGN_OFF";
            LOG.warnf("[SICCT] terminal sign-off from terminal=%s fu=%s — connection will be closed",
                    connection.getTerminalId(), fu(event.getTerminalSignOff()));
        } else if (event.getProtocolError() != null) {
            lastEvent = "PROTOCOL_ERROR:" + event.getProtocolError().intValue();
            LOG.errorf("[SICCT] protocol error event code=%d from terminal=%s",
                    event.getProtocolError().intValue(), connection.getTerminalId());
        } else if (event.getFuAdded() != null) {
            lastEvent = "FU_ADDED:" + fu(event.getFuAdded());
            LOG.infof("[SICCT] functional unit added fu=%s on terminal=%s",
                    fu(event.getFuAdded()), connection.getTerminalId());
        } else if (event.getFuRemoved() != null) {
            lastEvent = "FU_REMOVED:" + fu(event.getFuRemoved());
            LOG.infof("[SICCT] functional unit removed fu=%s on terminal=%s",
                    fu(event.getFuRemoved()), connection.getTerminalId());
        } else if (event.getCardInserted() != null) {
            lastEvent = "CARD_INSERTED:" + fu(event.getCardInserted());
            LOG.infof("[SICCT] card inserted in slot fu=%s on terminal=%s",
                    fu(event.getCardInserted()), connection.getTerminalId());
        } else if (event.getCardRemoved() != null) {
            lastEvent = "CARD_REMOVED:" + fu(event.getCardRemoved());
            LOG.infof("[SICCT] card removed from slot fu=%s on terminal=%s",
                    fu(event.getCardRemoved()), connection.getTerminalId());
        } else if (event.getKeypadEvent() != null) {
            int keyCode = event.getKeypadEvent().getKeyCode().intValue();
            lastEvent = "KEYPAD:" + keyCode;
            LOG.debugf("[SICCT] keypad event keyCode=%d fu=%s on terminal=%s",
                    keyCode, fu(event.getKeypadEvent().getFuNumber()), connection.getTerminalId());
        } else {
            lastEvent = "UNKNOWN";
            LOG.warnf("[SICCT] received unknown EventNotification from terminal=%s", connection.getTerminalId());
        }
    }

    // -------------------------------------------------------------------------
    // Response processing
    // -------------------------------------------------------------------------

    private void handleInitCtSessionResponse(SicctEnvelope envelope) {
        CTSESSDO ctsess = findDataObject(envelope, SicctDataObject::getCtsess);
        if (ctsess != null && ctsess.getSessionId() != null) {
            byte[] sessionIdBytes = ctsess.getSessionId().value;
            sessionId = sessionIdBytes != null ? new String(sessionIdBytes, java.nio.charset.StandardCharsets.UTF_8) : "";
            LOG.infof("[SICCT] INIT CT SESSION ack from terminal=%s, sessionId=%s sw=%s",
                    connection.getTerminalId(), sessionId, sw(envelope));
        } else {
            LOG.infof("[SICCT] INIT CT SESSION ack from terminal=%s sw=%s",
                    connection.getTerminalId(), sw(envelope));
        }
    }

    private void handleRequestIccResponse(SicctEnvelope envelope, SentCommand command) {
        ATRDO atr = findDataObject(envelope, SicctDataObject::getAtr);
        if (atr != null && atr.value != null) {
            lastAtr = atr.value;
            LOG.infof("[SICCT] REQUEST ICC on slot=%d returned ATR=%s sw=%s for terminal=%s",
                    command.p1(), HexFormat.of().formatHex(atr.value), sw(envelope), connection.getTerminalId());
        } else {
            LOG.infof("[SICCT] REQUEST ICC on slot=%d sw=%s for terminal=%s",
                    command.p1(), sw(envelope), connection.getTerminalId());
        }
    }

    private void handleEjectIccResponse(SicctEnvelope envelope, SentCommand command) {
        LOG.infof("[SICCT] EJECT ICC on slot=%d sw=%s for terminal=%s",
                command.p1(), sw(envelope), connection.getTerminalId());
    }

    private void handleGetStatusResponse(SicctEnvelope envelope, SentCommand command) {
        switch (command.p2()) {
            case SICCT.P2_GET_STATUS_ALL_ICC & 0xFF -> handleGetStatusAllIcc(envelope);
            case SICCT.P2_GET_STATUS_CARD_TERMINAL_STATUS & 0xFF -> handleGetStatusCardTerminal(envelope);
            case SICCT.P2_GET_STATUS_CARD_TERMINAL_MANUFACTURER & 0xFF -> handleGetStatusManufacturer(envelope);
            case SICCT.P2_GET_STATUS_FUNCTIONAL_UNIT_DATA & 0xFF -> handleFunctionalUnitData(envelope);
            case 0x66 -> handleInterfaceCapabilities(envelope, command);
            default -> LOG.infof("[SICCT] GET STATUS p1=%d p2=0x%02X sw=%s for terminal=%s",
                    command.p1(), command.p2(), sw(envelope), connection.getTerminalId());
        }
    }

    private void handleGetStatusAllIcc(SicctEnvelope envelope) {
        // SICCT ICC STATUS — one status byte per ICC interface (slot)
        ICCSDO iccs = findDataObject(envelope, SicctDataObject::getIccs);
        if (iccs != null && iccs.value != null) {
            lastIccStatus = IccStatusDecoder.decode(iccs.value);
            LOG.infof("[SICCT] GET STATUS ALL ICC for terminal=%s: %s (raw=%s) sw=%s",
                    connection.getTerminalId(), lastIccStatus, HexFormat.of().formatHex(iccs.value), sw(envelope));
        } else {
            LOG.infof("[SICCT] GET STATUS ALL ICC for terminal=%s sw=%s (no ICC status DO)",
                    connection.getTerminalId(), sw(envelope));
        }
    }

    private void handleGetStatusCardTerminal(SicctEnvelope envelope) {
        // SICCT GET STATUS CARD TERMINAL — CTS-DO bundles functional-unit + ICC status info
        CTSDO cts = findDataObject(envelope, SicctDataObject::getCts);
        if (cts != null && cts.getIccs() != null && cts.getIccs().value != null) {
            lastIccStatus = IccStatusDecoder.decode(cts.getIccs().value);
            LOG.infof("[SICCT] GET STATUS CARD TERMINAL for terminal=%s: iccStatus=%s sw=%s",
                    connection.getTerminalId(), lastIccStatus, sw(envelope));
        } else {
            LOG.infof("[SICCT] GET STATUS CARD TERMINAL for terminal=%s sw=%s",
                    connection.getTerminalId(), sw(envelope));
        }
    }

    private void handleGetStatusManufacturer(SicctEnvelope envelope) {
        // SICCT GET STATUS CARD TERMINAL MANUFACTURER — product/manufacturer info string
        INTFCDO manufacturer = findDataObject(envelope, SicctDataObject::getIntfc);
        if (manufacturer != null && manufacturer.value != null) {
            manufacturerInfo = new String(manufacturer.value, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (connection.getTerminal() != null) {
                connection.getTerminal().productInformation = manufacturerInfo;
            }
            LOG.infof("[SICCT] GET STATUS MANUFACTURER for terminal=%s: %s sw=%s",
                    connection.getTerminalId(), manufacturerInfo, sw(envelope));
        } else {
            LOG.infof("[SICCT] GET STATUS MANUFACTURER for terminal=%s sw=%s",
                    connection.getTerminalId(), sw(envelope));
        }
    }

    private void handleInterfaceCapabilities(SicctEnvelope envelope, SentCommand command) {
        // SICCT Interface Capabilities Data Object — P1 distinguishes display (0x40xx) from a slot
        boolean display = (command.p1() & 0x40) != 0;
        LOG.infof("[SICCT] Interface Capabilities for %s (p1=0x%02X) on terminal=%s sw=%s",
                display ? "DISPLAY" : "SLOT", command.p1(), connection.getTerminalId(), sw(envelope));
    }

    private void handleFunctionalUnitData(SicctEnvelope envelope) {
        // SICCT Interface Capabilities Functional Unit Data Object
        LOG.infof("[SICCT] Functional Unit Data Object for terminal=%s sw=%s",
                connection.getTerminalId(), sw(envelope));
    }

    private void handleCloseCtSessionResponse(SicctEnvelope envelope) {
        LOG.infof("[SICCT] CLOSE CT SESSION ack from terminal=%s sw=%s",
                connection.getTerminalId(), sw(envelope));
    }

    private void handleResetCtResponse(SicctEnvelope envelope, SentCommand command) {
        // SICCT RESET CT — terminal returns the freshly powered card's ATR
        ATRDO atr = findDataObject(envelope, SicctDataObject::getAtr);
        if (atr != null && atr.value != null) {
            lastAtr = atr.value;
            LOG.infof("[SICCT] RESET CT slot=%d returned ATR=%s sw=%s for terminal=%s",
                    command.p1(), HexFormat.of().formatHex(atr.value), sw(envelope), connection.getTerminalId());
        } else {
            LOG.infof("[SICCT] RESET CT slot=%d sw=%s for terminal=%s",
                    command.p1(), sw(envelope), connection.getTerminalId());
        }
    }

    private void handleOutputResponse(SicctEnvelope envelope) {
        LOG.infof("[SICCT] OUTPUT ack from terminal=%s sw=%s", connection.getTerminalId(), sw(envelope));
    }

    private void handlePerformVerificationResponse(SicctEnvelope envelope) {
        LOG.infof("[SICCT] PERFORM VERIFICATION result sw=%s for terminal=%s",
                sw(envelope), connection.getTerminalId());
    }

    private void handleEhealthAuthenticateResponse(SicctEnvelope envelope, SentCommand command) {
        // EHEALTH TERMINAL AUTHENTICATE — sub-variant is determined by the P2 qualifier of the
        // command that was sent (CREATE / VALIDATE / ADD generate-challenge / ADD response).
        String sw = sw(envelope);
        switch (command.p2()) {
            case EhealthTerminalAuthenticate.P2_CREATE & 0xFF -> LOG.infof(
                    "[SICCT] EHEALTH TERMINAL AUTHENTICATE CREATE response (signature over shared secret) sw=%s for terminal=%s",
                    sw, connection.getTerminalId());
            case EhealthTerminalAuthenticate.P2_VALIDATE & 0xFF -> {
                LOG.infof("[SICCT] EHEALTH TERMINAL AUTHENTICATE VALIDATE response sw=%s for terminal=%s",
                        sw, connection.getTerminalId());
                if (isSuccess(envelope)) {
                    connection.onPaired();
                    connection.onAktiv();
                }
            }
            // P2 ADD generate-challenge (0x03): terminal returns a challenge (EHEALTH ... ADD EXPECTING)
            case EhealthTerminalAuthenticate.P2_ADD_PH1 & 0xFF -> LOG.infof(
                    "[SICCT] EHEALTH TERMINAL AUTHENTICATE ADD (challenge requested, EXPECTING response) sw=%s for terminal=%s",
                    sw, connection.getTerminalId());
            // P2 ADD response (0x04): terminal not expecting further challenge (ADD NOT EXPECTING)
            case EhealthTerminalAuthenticate.P2_ADD_PH2 & 0xFF -> LOG.infof(
                    "[SICCT] EHEALTH TERMINAL AUTHENTICATE ADD (challenge-response delivered, NOT EXPECTING) sw=%s for terminal=%s",
                    sw, connection.getTerminalId());
            default -> LOG.infof("[SICCT] EHEALTH TERMINAL AUTHENTICATE p2=0x%02X sw=%s for terminal=%s",
                    command.p2(), sw, connection.getTerminalId());
        }
    }

    private void handleApduResponse(SicctEnvelope envelope, SentCommand command) {
        // SICCT APDU Response — a transparent card response forwarded by the terminal
        String slot = command != null ? " slot=" + command.p1() : "";
        LOG.infof("[SICCT] APDU response%s sw=%s for terminal=%s",
                slot, sw(envelope), connection.getTerminalId());
    }

    // -------------------------------------------------------------------------
    // Response helpers
    // -------------------------------------------------------------------------

    private boolean isEvent(SicctEnvelope envelope) {
        SicctMessageType type = envelope.getBMessageType();
        return type != null && type.intValue() == SICCT.EVENT.intValue();
    }

    private boolean isSuccess(SicctEnvelope envelope) {
        StatusWord trailer = trailer(envelope);
        return trailer != null && trailer.getSw1().intValue() == 0x90 && trailer.getSw2().intValue() == 0x00;
    }

    private StatusWord trailer(SicctEnvelope envelope) {
        if (envelope.getAbCmd() == null || envelope.getAbCmd().getResponseApdu() == null) {
            return null;
        }
        return envelope.getAbCmd().getResponseApdu().getTrailer();
    }

    private String sw(SicctEnvelope envelope) {
        StatusWord trailer = trailer(envelope);
        if (trailer == null) {
            return "<none>";
        }
        return String.format("%02X%02X", trailer.getSw1().intValue() & 0xFF, trailer.getSw2().intValue() & 0xFF);
    }

    /**
     * Returns the first SICCT data object of the requested kind in a Response-APDU, or {@code null}.
     */
    private <T> T findDataObject(SicctEnvelope envelope, java.util.function.Function<SicctDataObject, T> extractor) {
        if (envelope.getAbCmd() == null) {
            return null;
        }
        ResponseAPDU responseApdu = envelope.getAbCmd().getResponseApdu();
        if (responseApdu == null || responseApdu.getResponseData() == null) {
            return null;
        }
        for (SicctDataObject dataObject : responseApdu.getResponseData().getSicctDataObject()) {
            T value = extractor.apply(dataObject);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String fu(FuNumber fuNumber) {
        return fuNumber == null || fuNumber.value == null ? "?" : HexFormat.of().formatHex(fuNumber.value);
    }

    // Accessors for observing how the handler processed terminal messages (used by tests/monitoring).

    public String getSessionId() {
        return sessionId;
    }

    public String getManufacturerInfo() {
        return manufacturerInfo;
    }

    public List<IccStatusValue> getLastIccStatus() {
        return lastIccStatus;
    }

    public byte[] getLastAtr() {
        return lastAtr;
    }

    public String getLastEvent() {
        return lastEvent;
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

        assembleAndSendEnvelop(ins, p1, p2, sicctEnvelop, ctSessDO);

    }

    public void assembleAndSendEnvelop(byte ins, int p1,
            int p2, SicctEnvelope sicctEnvelop,
            Object dataObject) {
        assembleAndSendEnvelop(ISO7816.CLA_PROPRIETARY, ins, p1, p2, sicctEnvelop, dataObject);
    }

    private void assembleAndSendEnvelop(int cla, byte ins, int p1,
            int p2, SicctEnvelope sicctEnvelop,
            Object dataObject) {

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

        rememberSentCommand(sicctEnvelop, cla, ins, p1, p2);

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
        return createSicctEnvelop(null);
    }

    private SicctEnvelope createSicctEnvelop(Consumer<SicctEnvelope> responseHandler) {
        SicctEnvelope sicctEnvelop = new SicctEnvelope();

        sicctEnvelop.setBMessageType(SICCT.C_COMMAND);
        sicctEnvelop.setWSrcOrDesAddr(SICCT.TERMINAL_ADDRESS); // Arbitrary message ID for INIT CT SESSION
        sicctEnvelop.setWSeq(new SicctSequenceNumber(sequenceNumber));

        if (responseHandler != null) {
            pendingOperations.put(sequenceNumber, responseHandler);
        }

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
