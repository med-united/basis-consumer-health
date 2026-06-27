package de.servicehealtherx.quarkus.sicct.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import com.beanit.asn1bean.ber.types.BerInteger;
import com.beanit.asn1bean.ber.types.BerOctetString;

import de.gematik.pki.gemlibpki.commons.utils.CertReader;
import de.servicehealtherx.cetp.EventSeverity;
import de.servicehealtherx.cetp.EventType;
import de.servicehealtherx.sicct.EhealthTerminalAuthenticate;
import de.servicehealtherx.sicct.ISO7816;
import de.servicehealtherx.sicct.jpa.CardTerminal;
import de.servicehealtherx.sicct.jpa.CorrelationState;
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
    /** Set once the CardTerminal Manufacturer DO has been received and processed on this connection. */
    private volatile boolean manufacturerInfoReceived;
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
            connection.onTlsEstablished(connection.getTerminal().smktAutCertificate != null);

            // TUC_KON_050 step 5: compare the TLS server certificate against the stored
            // reference data CT.SMKT_AUT and check the certificate validity window.
            checkServerCertificateReferenceData();

            // TUC_KON_050 steps 4 + 6–11 run once the secure channel is up.
            beginCardTerminalSession();
        }
    }

    /**
     * TUC_KON_050 step 5: verify the TLS server certificate against the reference data
     * stored for this terminal (CT.SMKT_AUT) and surface an upcoming expiry.
     */
    private void checkServerCertificateReferenceData() throws Exception {
        byte[] savedCertBytes = connection.getTerminal().smktAutCertificate;
        if (savedCertBytes == null) {
            LOG.warnf("[SICCT] TLS up for terminal=%s but no CT.SMKT_AUT stored — saving current certificate: %s",
                    connection.getTerminalId(), terminalTLSCertificate.getSubjectX500Principal().getName());
            connection.getTerminal().smktAutCertificate = terminalTLSCertificate.getEncoded();
        } else {
            X509Certificate savedCert = CertReader.readX509(savedCertBytes);
            if (!terminalTLSCertificate.equals(savedCert)) {
                // Step 5a: certificate mismatch. The full handling compares the ICCSN
                // from the certificate and, when equal, stores the new certificate and
                // performs a maintenance pairing. That ICCSN/maintenance-pairing path
                // is not yet wired here.
                LOG.warnf("[SICCT] terminal=%s CT.SMKT_AUT mismatch! stored=%s presented=%s "
                        + "(ICCSN comparison + maintenance pairing not yet implemented)",
                        connection.getTerminalId(),
                        savedCert.getSubjectX500Principal().getName(),
                        terminalTLSCertificate.getSubjectX500Principal().getName());
            } else {
                LOG.debugf("[SICCT] terminal=%s CT.SMKT_AUT matches stored reference", connection.getTerminalId());
            }
        }

        // Step 5b: certificate expires in less than 35 days → operational-state warning.
        long daysToExpiry = (terminalTLSCertificate.getNotAfter().getTime() - System.currentTimeMillis())
                / (24L * 60 * 60 * 1000);
        if (daysToExpiry < 35) {
            LOG.warnf("[SICCT] terminal=%s gSMC-KT certificate expires in %d day(s) "
                    + "→ EC_CardTerminal_gSMC-KT_Certificate_Expires_Soon(%s)",
                    connection.getTerminalId(), daysToExpiry, connection.getTerminal().ctid);
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

    /**
     * TUC_KON_053 steps 4.a–7: generates the ShS.KT.AUT shared secret, sends EHEALTH
     * TERMINAL AUTHENTICATE in the CREATE flavour with the shared secret DO and the
     * pairing display message, and — when the terminal's signature over the shared
     * secret verifies against CT.SMKT_AUT (step 6) — advances the terminal to GEPAIRT
     * (step 7).
     *
     * @return a future that completes {@code true} once a correct signature has been
     *         received, {@code false} if the signature is invalid, or completes
     *         exceptionally if the response could not be processed.
     */
    CompletableFuture<Boolean> ehealthTerminalAuthenticateCreate() {

        CompletableFuture<Boolean> pairingResult = new CompletableFuture<>();
        byte[] sharedSecret = EhealthTerminalAuthenticate.generateSharedSecret();

        // The build/register/send below mutates Netty handler state (sequenceNumber,
        // pendingOperations) and writes to the channel; all of it MUST run on the event
        // loop. Doing it on the calling (pairing executor) thread races the event loop
        // and can register the response consumer under a sequence number that no longer
        // matches the sent frame — the response would then never reach the consumer and
        // the pairing future would never complete (the caller's get() would hang).
        Runnable sendCreate = () -> {
            try {
                SicctEnvelope sicctEnvelop = createSicctEnvelop((sicctEnvelope) -> {
                    // Always settle pairingResult — including on an unexpected response shape
                    // (e.g. an error status word leaving the response APDU/data null). A
                    // dangling future would otherwise block the pairing thread (and the
                    // confirmFingerprint caller waiting on it) until the protocol timeout.
                    try {
                        byte[] responseBody = responseBodyWithTrailer(sicctEnvelope);
                        LOG.debugf("Shared secret: %s CREATE response body: %s",
                                HexFormat.of().formatHex(sharedSecret), HexFormat.of().formatHex(responseBody));
                        // Step 6: verify the terminal's signature of the shared secret with the key
                        // belonging to CT.SMKT_AUT (the certificate presented during TLS). A
                        // spec-conformant terminal terminates the response APDU with SW 9000, so the
                        // signature is the response body without its 2-byte trailer; a terminal that
                        // omits the trailer returns the bare signature (the codec then split its last
                        // two bytes into the status word). Accept either form.
                        if (!verifyPairingSignature(sharedSecret, terminalTLSCertificate, dropTrailer(responseBody))
                                && !verifyPairingSignature(sharedSecret, terminalTLSCertificate, responseBody)) {
                            throw new GeneralSecurityException("Signature validation failed");
                        }
                        // Step 4.a/7: protect ShS.KT.AUT (TPM-seal when available, else store
                        // unsealed) and place it in CT.SHARED_SECRET, then advance CORRELATION to
                        // „gepairt“. onPaired() persists the lifecycle state — including the
                        // protected secret — so it survives a restart and the reconnect VALIDATE
                        // can recover it (without persistence the secret is unrecoverable and every
                        // reconnect fails authentication, blocking card discovery).
                        connection.getTerminal().sealedSharedSecret = protectSharedSecret(sharedSecret);
                        connection.onPaired();
                        pairingResult.complete(true);
                    } catch (GeneralSecurityException e) {
                        LOG.errorf(e, "Error validating signature with sharedSecret");
                        pairingResult.complete(false);
                    } catch (Throwable t) {
                        LOG.errorf(t, "Error processing EHEALTH TERMINAL AUTHENTICATE CREATE response (sw=%s) for terminal=%s",
                                sw(sicctEnvelope), connection.getTerminalId());
                        pairingResult.completeExceptionally(t);
                    }
                });
                // The generated ShS.KT.AUT is stored (protected) into CT.SHARED_SECRET only after
                // the terminal's CREATE signature has been verified (see the response handler above).
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
            } catch (Throwable t) {
                // Building/sending failed before a response could ever arrive — settle the
                // future so the pairing thread (and confirmFingerprint) never blocks.
                LOG.errorf(t, "Failed to send EHEALTH TERMINAL AUTHENTICATE CREATE for terminal=%s",
                        connection.getTerminalId());
                pairingResult.completeExceptionally(t);
            }
        };

        if (ctx == null) {
            pairingResult.completeExceptionally(new IllegalStateException(
                    "No channel context for terminal=" + connection.getTerminalId()));
        } else if (ctx.executor().inEventLoop()) {
            sendCreate.run();
        } else {
            ctx.executor().execute(sendCreate);
        }

        return pairingResult;
    }

    /**
     * TUC_KON_053 step 8: sends the SICCT CLOSE CT SESSION command (INS 0x29) with the
     * card terminal as addressee, ending the cardterminal session opened for pairing
     * before the underlying TLS connection is torn down.
     */
    void closeCtSession() {
        SicctEnvelope sicctEnvelop = createSicctEnvelop();
        byte ins = SICCT.INS_CLOSE_CT_SESSION;
        int p1 = SICCT.P1_CARD_TERMINAL;
        int p2 = 0x00;
        assembleAndSendEnvelop(ins, p1, p2, sicctEnvelop, null);
    }

    // -------------------------------------------------------------------------
    // TUC_KON_050 — authenticated card-terminal session (steps 4, 6–11)
    // -------------------------------------------------------------------------

    /**
     * TUC_KON_050 continuation that runs once the TLS channel is up. INIT CT SESSION
     * has already been emitted from {@link #channelActive} with role-appropriate
     * credentials. This applies the correlation gate (step 4) and, for sufficiently
     * correlated terminals, runs the authentication (steps 6–9).
     */
    public void beginCardTerminalSession() {
        CorrelationState correlation = connection.getCorrelationState();

        // Step 4: Wenn CT.CORRELATION <= „zugewiesen": only a low-correlation session
        // is permitted. INIT CT SESSION (empty credentials) was already sent; mark the
        // terminal not usable (CT.CONNECTED = Nein) and end the TUC.
        if (correlation.ordinal() <= CorrelationState.ZUGEWIESEN.ordinal()) {
            LOG.infof("[TUC_KON_050] terminal=%s correlation=%s (<= ZUGEWIESEN): low-correlation session, CONNECTED=Nein",
                    connection.getTerminalId(), correlation);
            connection.markSessionNotUsable();
            return;
        }

        runAuthenticatedSession(connection.getDesiredRole(), connection.isTlsFreshlyEstablished());
    }

    /**
     * TUC_KON_050 step 2 (role switch over a kept TLS connection): close the current
     * card-terminal session, open a new one with the requested role and re-authenticate.
     */
    public void switchSessionRole(Role role) {
        sendCloseCtSession();
        connection.setDesiredRole(role);
        sendInitCtSessionApdu(); // new session with role-appropriate credentials
        runAuthenticatedSession(role, false);
    }

    /**
     * TUC_KON_050 steps 6–7: generate a challenge and send EHEALTH TERMINAL
     * AUTHENTICATE VALIDATE. The response is verified asynchronously in
     * {@link #handleValidateResult}.
     */
    private void runAuthenticatedSession(Role role, boolean tlsFreshlyEstablished) {
        // Step 6a: generate a random challenge of at least 16 bytes.
        byte[] challenge = EhealthTerminalAuthenticate.generateChallenge(EhealthTerminalAuthenticate.SSC_MIN_LENGTH);
        connection.setSessionChallenge(challenge);
        LOG.infof("[TUC_KON_050] terminal=%s sending EHEALTH TERMINAL AUTHENTICATE VALIDATE (role=%s)",
                connection.getTerminalId(), role);

        // Step 7: send the challenge in the Shared Secret Challenge DO.
        SicctEnvelope envelope = createSicctEnvelop(
                response -> handleValidateResult(response, challenge, role, tlsFreshlyEstablished));
        byte[] validateApdu = EhealthTerminalAuthenticate.buildValidate(challenge);
        envelope.setDwLength(new BerInteger(validateApdu.length));
        envelope.setAbCmd(new SicctPayload(validateApdu));
        rememberSentCommand(envelope, EhealthTerminalAuthenticate.CLA, EhealthTerminalAuthenticate.INS,
                EhealthTerminalAuthenticate.P1_DIRECT, EhealthTerminalAuthenticate.P2_VALIDATE);
        sendSicctEnvelope(envelope);
    }

    /**
     * TUC_KON_050 steps 8–11: verify the VALIDATE response hash and, on success, mark
     * the session usable, emit CT/CONNECTED and gather the inserted cards.
     */
    private void handleValidateResult(SicctEnvelope response, byte[] challenge, Role role,
            boolean tlsFreshlyEstablished) {
        // Step 8: the terminal must return SHA-256(challenge || CT.SHARED_SECRET). Recover the raw
        // ShS.KT.AUT from the stored (TPM-sealed when available) value; the recovered copy is zeroed
        // again below so the cleartext secret does not linger on the heap.
        byte[] storedSecret = connection.getTerminal().sealedSharedSecret;
        byte[] sharedSecret = recoverSharedSecret(storedSecret);
        boolean verified = false;
        try {
            if (sharedSecret != null) {
                byte[] expected = EhealthTerminalAuthenticate.computeExpectedValidateHash(challenge, sharedSecret);
                // Search the full Response-APDU body (data field + SW). A conformant terminal returns
                // <hash> 9000, one that omits the trailer returns the bare hash (its last two bytes
                // were split into the status word) — reconstructing data + SW recovers the hash in
                // both cases. A genuine error response carries no data, so its 2-byte body cannot
                // contain the 32-byte expected hash and verification correctly fails.
                byte[] responseBody = responseBodyWithTrailer(response);
                verified = indexOf(responseBody, expected) >= 0;
            }
        } finally {
            if (sharedSecret != null) {
                Arrays.fill(sharedSecret, (byte) 0);
            }
        }

        if (!verified) {
            LOG.warnf("[TUC_KON_050] terminal=%s EHEALTH TERMINAL AUTHENTICATE VALIDATE failed "
                    + "(sw=%s, sharedSecretPresent=%s): CONNECTED=Nein",
                    connection.getTerminalId(), sw(response), storedSecret != null);
            connection.markSessionNotUsable();
            return;
        }

        // Step 9: set CT.ACTIVEROLE = role and CT.CONNECTED = Ja.
        connection.markSessionEstablished(role);

        // Step 10: if the TLS connection had to be (re-)established, raise CT/CONNECTED.
        if (tlsFreshlyEstablished && manager != null) {
            manager.TUC_KON_256("CT/CONNECTED", EventType.Operation, EventSeverity.Info,
                    Map.of("CtID", String.valueOf(connection.getTerminal().ctid),
                            "Hostname", String.valueOf(connection.getTerminal().hostname)),
                    true, true);
        }

        // Step 11: determine the cards currently inserted and fill CT.SLOTS_USED.
        getStatusAllIcc();
    }

    /**
     * Protects the freshly generated ShS.KT.AUT for storage in {@code CT.SHARED_SECRET}. Delegates
     * to {@link TpmSealer#protect(byte[])} (TPM-sealed when available, else unsealed with a format
     * marker). When no manager/sealer is wired (standalone/test), the raw secret is stored as-is —
     * matching the legacy on-disk format that {@link #recoverSharedSecret(byte[])} reads back.
     */
    private byte[] protectSharedSecret(byte[] rawSecret) {
        TpmSealer sealer = manager != null ? manager.getTpmSealer() : null;
        if (sealer != null) {
            return sealer.protect(rawSecret);
        }
        return rawSecret;
    }

    /**
     * Recovers the raw ShS.KT.AUT from the value stored in {@code CT.SHARED_SECRET}. Always returns
     * a caller-owned copy that MUST be zeroed after use — never the stored array itself, so callers
     * can wipe the cleartext secret without clobbering the persisted value. Returns {@code null} for
     * a missing secret.
     */
    private byte[] recoverSharedSecret(byte[] storedSecret) {
        if (storedSecret == null) {
            return null;
        }
        TpmSealer sealer = manager != null ? manager.getTpmSealer() : null;
        if (sealer != null) {
            return sealer.recover(storedSecret);
        }
        // No sealer (standalone/test): the stored value is the raw secret. Clone it so the caller's
        // post-use zeroing does not wipe CT.SHARED_SECRET.
        return storedSecret.clone();
    }

    /** Encodes a Response-APDU's data field to raw bytes, or {@code null} if absent. */
    private byte[] extractResponseData(SicctEnvelope envelope) {
        if (envelope.getAbCmd() == null || envelope.getAbCmd().getResponseApdu() == null
                || envelope.getAbCmd().getResponseApdu().getResponseData() == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            envelope.getAbCmd().getResponseApdu().getResponseData().encode(out, false);
            return out.toByteArray();
        } catch (IOException e) {
            LOG.warnf(e, "[SICCT] could not encode response data for terminal=%s", connection.getTerminalId());
            return null;
        }
    }

    /** Returns the index of {@code needle} within {@code haystack}, or -1. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private void sendCloseCtSession() {
        SicctEnvelope envelope = createSicctEnvelop();
        assembleAndSendEnvelop(SICCT.INS_CLOSE_CT_SESSION, SICCT.P1_CARD_TERMINAL, 0x00, envelope, getCtSessDO());
        LOG.infof("[SICCT] CLOSE CT SESSION sent for terminal=%s", connection.getTerminalId());
    }

    private void rememberSentCommand(SicctEnvelope envelope, int cla, byte ins, int p1, int p2) {
        sentCommands.put(envelope.getWSeq().intValue(),
                new SentCommand(cla, ins, p1 & 0xFF, p2 & 0xFF));
    }

    /**
     * Reassembles the raw EHEALTH TERMINAL AUTHENTICATE response body — the terminal's
     * signature over the shared secret. {@link SicctCodec#decodeSicctPayload} splits every
     * response payload into data + a two-byte SW1SW2 trailer, but the CREATE response is a
     * bare signature with <em>no</em> status word (see the reference terminal), so those
     * "trailer" bytes are really the last two signature bytes and must be appended back —
     * otherwise verification runs against a signature truncated by two bytes.
     */
    /**
     * The full Response-APDU body the terminal sent: the data field followed by SW1 SW2. The codec
     * splits the trailing two bytes of the payload into {@code getTrailer()}, so {@code data + SW}
     * reconstructs the original payload exactly — regardless of whether those last two bytes are a
     * real status word or (for a terminal that omits the trailer) the tail of an opaque payload.
     */
    private byte[] responseBodyWithTrailer(SicctEnvelope envelope) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] data = extractResponseData(envelope);
        if (data != null) {
            body.writeBytes(data);
        }
        StatusWord trailer = trailer(envelope);
        if (trailer != null && trailer.getSw1() != null && trailer.getSw2() != null) {
            body.write(trailer.getSw1().intValue() & 0xFF);
            body.write(trailer.getSw2().intValue() & 0xFF);
        }
        return body.toByteArray();
    }

    /** {@code body} without its trailing 2-byte status word (the data field of a conformant APDU). */
    private static byte[] dropTrailer(byte[] body) {
        return body.length >= 2 ? Arrays.copyOf(body, body.length - 2) : body;
    }

    /**
     * Verify the gSMC-KT pairing signature over the shared secret with the key of the certificate
     * presented during TLS (CT.SMKT_AUT). Returns {@code false} if the signature does not verify or
     * the candidate bytes are malformed for the key's algorithm (e.g. wrong length / not plain
     * r||s); throws only for unrecoverable problems (unsupported key, provider/key errors).
     *
     * <p>The terminal signs SHA-256(sharedSecret) once. For ECC (gSMC-KT) it uses NoneWithECDSA and
     * returns r||s in TR-03111 plain format, so verify with the PLAIN-ECDSA variant — it applies
     * SHA-256 once internally (matching the single hash) and accepts the plain r||s encoding.
     * (SHA256withECDSA would hash a second time and expects DER, so it can never match.)
     */
    private boolean verifyPairingSignature(byte[] sharedSecret, X509Certificate terminalTLSCertificate,
            byte[] signatureBytes) throws GeneralSecurityException {
        PublicKey publicKey = terminalTLSCertificate.getPublicKey();
        String algorithm = switch (publicKey.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withPLAIN-ECDSA";
            default -> throw new GeneralSecurityException(
                    "Unsupported key algorithm: " + publicKey.getAlgorithm());
        };

        Signature signature = Signature.getInstance(algorithm, "BC");
        signature.initVerify(publicKey);
        // Feed the raw shared secret; the SHA256with* algorithm applies SHA-256 itself.
        signature.update(sharedSecret);
        try {
            return signature.verify(signatureBytes);
        } catch (SignatureException e) {
            // Malformed signature encoding for this candidate (e.g. the bytes are a truncated or
            // trailer-padded r||s). Treat as "did not verify" so the caller can try the other form.
            return false;
        }
    }

    static String formatMacAddressForDisplay(String macAddress) {
        return macAddress.toUpperCase().replaceAll("(.{2}):(.{2}):(.{2}):(.{2}):(.{2}):(.{2})", "$1$2$3:$4$5$6");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connection.onDisconnected();
        // The connections map is keyed by MAC address, so the reconnect bookkeeping
        // must be looked up by MAC — not by the hostname returned from getTerminalId().
        manager.onTerminalDisconnected(connection.getTerminal().macAddress);
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
            // Re-query ICC status so the per-slot view drives card-handle (re)creation; this
            // avoids decoding the event's FuNumber→slot mapping and reuses the GET STATUS path.
            if (connection.isReadyForCards()) {
                getStatusAllIcc();
            }
        } else if (event.getCardRemoved() != null) {
            lastEvent = "CARD_REMOVED:" + fu(event.getCardRemoved());
            LOG.infof("[SICCT] card removed from slot fu=%s on terminal=%s",
                    fu(event.getCardRemoved()), connection.getTerminalId());
            if (connection.isReadyForCards()) {
                getStatusAllIcc();
            }
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
            updateSlotsUsed(lastIccStatus);
            LOG.infof("[SICCT] GET STATUS ALL ICC for terminal=%s: %s (raw=%s) sw=%s",
                    connection.getTerminalId(), lastIccStatus, HexFormat.of().formatHex(iccs.value), sw(envelope));
        } else {
            LOG.infof("[SICCT] GET STATUS ALL ICC for terminal=%s sw=%s (no ICC status DO)",
                    connection.getTerminalId(), sw(envelope));
        }
    }

    /**
     * TUC_KON_050 step 11: derive CT.SLOTS_USED from the per-slot ICC status (a slot
     * is "used" when a card is present) and raise CT/SLOT_IN_USE for each occupied slot.
     */
    private void updateSlotsUsed(List<IccStatusValue> iccStatus) {
        StringBuilder slots = new StringBuilder();
        for (int slot = 0; slot < iccStatus.size(); slot++) {
            if (iccStatus.get(slot) != IccStatusValue.CC_ABSENT) {
                if (slots.length() > 0) {
                    slots.append(',');
                }
                slots.append(slot);
                if (manager != null) {
                    manager.TUC_KON_256("CT/SLOT_IN_USE", EventType.Operation, EventSeverity.Info,
                            Map.of("CtID", String.valueOf(connection.getTerminal().ctid),
                                    "SlotNo", String.valueOf(slot)),
                            false, false);
                }
            }
        }
        connection.setSlotsUsed(slots.toString());

        // Once the terminal is connected and validly paired, build correct card handles
        // (TUC_KON_001) for every inserted ICC so each card is addressable over SICCT exactly
        // as over PC/SC. Card reads block, so the manager dispatches this off the event loop.
        if (manager != null && connection.isReadyForCards()) {
            manager.discoverCards(connection, iccStatus);
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
        // SICCT GET STATUS CARD TERMINAL MANUFACTURER — the CardTerminal Manufacturer DO
        // (§5.5.10.6). NOTE on tag routing: the manufacturer DO is sent with tag '46'. Due
        // to the asn1bean codec deriving the constructed bit from the ASN.1 type (CTM-DO is a
        // SEQUENCE → '66', INTFC-DO is an OCTET STRING → '46'), the wire byte '46' decodes
        // into the INTFC slot, so getIntfc() — not getCtm() — yields the raw DO value here.
        INTFCDO manufacturer = findDataObject(envelope, SicctDataObject::getIntfc);
        CardTerminal terminal = connection.getTerminal();
        if (manufacturer != null && manufacturer.value != null && terminal != null) {
            CardTerminalManufacturerInfo info = CardTerminalManufacturerInfo.parse(manufacturer.value);
            manufacturerInfo = info.displayString();
            terminal.productInformation = manufacturerInfo;
            terminal.ehealthInterfaceVersion = info.ehealthInterfaceVersion();
            // CT.VALID_VERSION (TUC_KON_254): the konnektor accepts the terminal only if its
            // reported eHealth interface version is on the supported list.
            boolean supported = manager != null
                    && manager.isEhealthInterfaceVersionSupported(info.ehealthInterfaceVersion());
            terminal.validVersion = supported;
            if (manager != null) {
                manager.persistManufacturerInfo(terminal);
            }
            if (!supported) {
                LOG.warnf("[SICCT] terminal=%s reports unsupported eHealth interface version=%s",
                        connection.getTerminalId(), info.ehealthInterfaceVersion());
            }
            LOG.infof("[SICCT] GET STATUS MANUFACTURER for terminal=%s: %s validVersion=%s sw=%s",
                    connection.getTerminalId(), manufacturerInfo, supported, sw(envelope));
        } else {
            LOG.infof("[SICCT] GET STATUS MANUFACTURER for terminal=%s sw=%s (no manufacturer DO)",
                    connection.getTerminalId(), sw(envelope));
        }
        // Signal completion regardless of outcome so the pairing flow's wait for the
        // manufacturer DO (and thus CT.VALID_VERSION) does not block until timeout.
        manufacturerInfoReceived = true;
    }

    /** True once the CardTerminal Manufacturer DO response has been processed on this connection. */
    public boolean isManufacturerInfoReceived() {
        return manufacturerInfoReceived;
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
            case EhealthTerminalAuthenticate.P2_CREATE & 0xFF -> {
                LOG.infof(
                        "[SICCT] EHEALTH TERMINAL AUTHENTICATE CREATE response (signature over shared secret) sw=%s for terminal=%s",
                        sw, connection.getTerminalId());
                // Pairing CREATE acknowledged: the terminal now holds the shared secret →
                // advance the correlation to GEPAIRT (the signature itself is validated by
                // the response consumer registered in ehealthTerminalAuthenticateCreate()).
                if (isSuccess(envelope)) {
                    connection.onPaired();
                }
            }
            // VALIDATE is verified and finalised by the response consumer registered in
            // runAuthenticatedSession() (TUC_KON_050 steps 8–11); here we only log.
            case EhealthTerminalAuthenticate.P2_VALIDATE & 0xFF -> LOG.infof(
                    "[SICCT] EHEALTH TERMINAL AUTHENTICATE VALIDATE response sw=%s for terminal=%s",
                    sw, connection.getTerminalId());
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

    // -------------------------------------------------------------------------
    // Transparent card-APDU transmission (drives the SICCT CardReaderPort)
    // -------------------------------------------------------------------------

    /**
     * Sends a transparent card command-APDU to the card in {@code slotNo} and completes the
     * returned future with the raw card response-APDU (response data ‖ SW1 SW2). The send is
     * marshalled onto the Netty event loop so the {@code sequenceNumber} stays single-threaded;
     * the future is then completed from that same loop when the correlated response arrives.
     *
     * <p><strong>Callers MUST block on the future from a worker thread, never the event loop</strong>
     * — the response is decoded on the loop, so blocking it would dead-lock the round-trip.
     */
    public CompletableFuture<byte[]> transmitCardApdu(int slotNo, byte[] commandApdu) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        ChannelHandlerContext context = this.ctx;
        if (context == null) {
            result.completeExceptionally(new IllegalStateException(
                    "no active SICCT channel for terminal=" + connection.getTerminalId()));
            return result;
        }
        context.executor().execute(() -> {
            try {
                int seq = sequenceNumber++;
                // pendingOperations is dispatched before the INS-classification switch, so the
                // response routes here regardless of the card's INS byte (which is not recorded
                // in sentCommands for transparent APDUs).
                pendingOperations.put(seq, envelope -> result.complete(rawResponseApdu(envelope)));
                ByteBuf out = SicctCodec.encodeCardApdu(iccFunctionalUnitAddress(slotNo), seq, commandApdu);
                context.writeAndFlush(out);
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** Whether the last GET STATUS ALL ICC reported a card in {@code slotNo} (0-based). */
    public boolean isCardPresent(int slotNo) {
        List<IccStatusValue> status = lastIccStatus;
        return slotNo >= 0 && slotNo < status.size() && status.get(slotNo) != IccStatusValue.CC_ABSENT;
    }

    /**
     * Maps a 0-based ICC slot index (as reported by GET STATUS ALL ICC) to its SICCT destination
     * functional-unit address ({@code wSrcOrDesAddr}). FU 0 addresses the card terminal itself, so
     * the first ICC slot is FU 1. NOTE: validate this mapping against the real terminal — isolated
     * here so it is a one-line change if a terminal numbers its ICC functional units differently.
     */
    private static int iccFunctionalUnitAddress(int slotNo) {
        return slotNo + 1;
    }

    /**
     * Reconstructs the raw card response-APDU (response data ‖ SW1 SW2) from a decoded SICCT
     * response envelope. {@link SicctCodec#decodeSicctPayload} keeps the response data field
     * verbatim in {@code ResponseData}, so encoding it without a tag yields exactly those bytes.
     */
    private byte[] rawResponseApdu(SicctEnvelope envelope) {
        ResponseAPDU response = envelope.getAbCmd() != null ? envelope.getAbCmd().getResponseApdu() : null;
        if (response == null) {
            return new byte[] {(byte) 0x6F, 0x00}; // no response APDU — generic error SW
        }
        byte[] data = new byte[0];
        if (response.getResponseData() != null) {
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                response.getResponseData().encode(os, false);
                data = os.toByteArray();
            } catch (IOException ignored) {
                // Opaque/short data field — fall back to empty data; the SW still carries the outcome.
            }
        }
        StatusWord trailer = response.getTrailer();
        int sw1 = trailer != null ? trailer.getSw1().intValue() & 0xFF : 0x6F;
        int sw2 = trailer != null ? trailer.getSw2().intValue() & 0xFF : 0x00;
        byte[] full = Arrays.copyOf(data, data.length + 2);
        full[data.length] = (byte) sw1;
        full[data.length + 1] = (byte) sw2;
        return full;
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
        // TUC_KON_050 step 6b: empty Session ID; role-dependent username/password.
        // User → empty credentials; Admin → CT.ADMIN_USERNAME / CT.ADMIN_PASSWORD.
        CTSESSDO ctSessDO = new CTSESSDO();
        ctSessDO.setSessionId(new BerOctetString(new byte[] {}));
        ctSessDO.setUsername(new BerOctetString(new byte[] {}));
        ctSessDO.setPassword(new BerOctetString(new byte[] {}));
        CardTerminal terminal = connection.getTerminal();
        if (terminal == null) {
            LOG.warnf(
                    "[SICCT] No terminal information available for terminal=%s, sending INIT CT SESSION with empty credentials",
                    connection.getTerminalId());
            return ctSessDO;
        }
        if (connection.getDesiredRole() == Role.ADMIN) {
            if (terminal.adminUsername != null) {
                ctSessDO.setUsername(new BerOctetString(terminal.adminUsername.getBytes()));
            }
            if (terminal.adminPassword != null) {
                ctSessDO.setPassword(new BerOctetString(terminal.adminPassword.getBytes()));
            }
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
