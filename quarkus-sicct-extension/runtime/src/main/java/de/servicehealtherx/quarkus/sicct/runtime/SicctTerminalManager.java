package de.servicehealtherx.quarkus.sicct.runtime;

import de.servicehealtherx.cetp.EventSeverity;
import de.servicehealtherx.cetp.EventType;
import de.servicehealtherx.cetp.KonnektorSystemEvent;
import de.servicehealtherx.crypto.GSMCKtTrustManager;
import de.servicehealtherx.crypto.TrustManagerProducer;
import de.servicehealtherx.crypto.TslDownloader;
import de.servicehealtherx.quarkus.sicct.runtime.tls.SICCTKonnektorTLSChannelInitializer;
import de.servicehealtherx.quarkus.sicct.runtime.tls.SmkCSAKAut;
import de.servicehealtherx.quarkus.sicct.runtime.tls.SmkCSAKAutProvider;
import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.netty.bootstrap.Bootstrap;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.TrustManager;

/**
 * Manages SICCT card terminal TCP/TLS connections.
 * Loads CardTerminal JPA records at startup; initiates connections via shared
 * NioEventLoopGroup.
 * One terminal failure MUST NOT prevent others per FR-023, FR-097.
 */
@Startup
@Priority(10) // Ensure this starts before CardTerminalDiscovery which depends on it, first
              // load from the database then do discovery
@ApplicationScoped
public class SicctTerminalManager {

    private static final Logger LOG = Logger.getLogger(SicctTerminalManager.class);

    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    /** TUC_KON_053: how long to wait for the pairing TLS connection to become usable. */
    private static final long TLS_CONNECT_TIMEOUT_MS = 15_000;
    /** TUC_KON_053: how long to wait for the EHEALTH TERMINAL AUTHENTICATE CREATE response. */
    private static final long PAIRING_TIMEOUT_MS = 30_000;
    /** TUC_KON_053: poll cadence while waiting for the async connection to come up. */
    private static final long PAIRING_POLL_INTERVAL_MS = 100;
    /** TUC_KON_053: hard budget for the whole interactive request/confirm pairing process. */
    private static final long PAIRING_PROCESS_TIMEOUT_MS = 30_000;

    /**
     * eHealth interface versions (CardTerminal Manufacturer DO, VER field) this konnektor
     * accepts. A terminal whose reported version is not on this list fails the
     * TUC_KON_254 version check, leaving CT.VALID_VERSION = false (TUC_KON_053 rejects it).
     */
    @Inject
    @ConfigProperty(name = "sicct.supported-ehealth-interface-versions", defaultValue = "1.0.0")
    List<String> supportedEhealthInterfaceVersions;

    @Inject
    TpmSealer tpmSealer;

    @Inject
    SmkCSAKAut smkCSAKAut;

    @Inject
    @GSMCKtTrustManager
    TrustManager gSMCKtTrustManager;

    /** CDI bus for konnektor system events (TUC_KON_256); observed by lib-cetp's CetpClient. */
    @Inject
    Event<KonnektorSystemEvent> konnektorEventBus;

    /**
     * Turns SICCT slot-status into card handles once a terminal is connected and validly paired.
     * {@code Instance<>} so this manager (and its tests) start even when the discovery bean is
     * absent from a thin deployment; resolved lazily into {@link #cardDiscovery}.
     */
    @Inject
    jakarta.enterprise.inject.Instance<SicctCardDiscovery> cardDiscoveryInstance;

    List<CardTerminal> terminals = null;

    private EventLoopGroup eventLoopGroup;
    private final Map<String, SicctTerminalConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectScheduler = Executors.newScheduledThreadPool(2);

    /** Runs the blocking TUC_KON_053 flow off the caller (JMX) thread. */
    private final ExecutorService pairingExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tuc-kon-053-pairing");
        t.setDaemon(true);
        return t;
    });
    /** Interactive pairings awaiting an administrator decision, keyed by the presented fingerprint. */
    private final Map<String, PendingPairing> pendingByFingerprint = new ConcurrentHashMap<>();
    /** Guards against more than one in-flight pairing per terminal. */
    private final Map<UUID, PendingPairing> pendingByCtId = new ConcurrentHashMap<>();

    @PostConstruct
    @Transactional
    void initialize() {
        eventLoopGroup = new NioEventLoopGroup(4);

        if (tpmSealer != null) {
            tpmSealer.initialize();
        } else {
            LOG.warnf("[SicctTerminalManager] TPM Sealer not available");
        }

        if (terminals == null) {
            terminals = CardTerminal.listAll();
            LOG.infof("[SicctTerminalManager] loaded %d terminal(s) from DB", terminals.size());
        } else {
            LOG.infof("[SicctTerminalManager] using pre-loaded terminals: %d terminal(s)", terminals.size());
        }

        for (CardTerminal terminal : terminals) {
            try {
                connectTerminalAsync(terminal);
            } catch (Exception e) {
                // One terminal failure MUST NOT prevent others per FR-023
                LOG.errorf(e, "[SicctTerminalManager] failed to initiate connection for hostname=%s, continuing",
                        terminal.hostname);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        reconnectScheduler.shutdown();
        pairingExecutor.shutdownNow();
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
        connections.clear();
        LOG.infof("[SicctTerminalManager] shutdown complete");
    }

    public void connectTerminalAsync(CardTerminal terminal) {
        SicctTerminalConnection conn = connections.computeIfAbsent(terminal.macAddress,
                id -> new SicctTerminalConnection(terminal, this));
        // This path always (re-)establishes the TLS connection, so TUC_KON_050 step 10
        // (CT/CONNECTED) must fire once the session is authenticated.
        conn.setTlsFreshlyEstablished(true);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new SICCTKonnektorTLSChannelInitializer(this, conn));

        ChannelFuture future = bootstrap.connect(terminal.ipAddress, terminal.tcpPort);

        future.addListener(f -> {
            if (f.isSuccess()) {
                LOG.infof("[SICCT] connected to terminal=%s at %s:%d", terminal.hostname, terminal.ipAddress,
                        terminal.tcpPort);
            } else {
                LOG.warnf(f.cause(), "[SICCT] failed to connect to terminal=%s at %s:%d, scheduling reconnect",
                        terminal.hostname, terminal.ipAddress, terminal.tcpPort);
                scheduleReconnect(terminal, conn, INITIAL_BACKOFF_MS);
            }
        });
    }

    private void scheduleReconnect(CardTerminal terminal, SicctTerminalConnection conn, long delayMs) {
        if (conn.isReconnectSuppressed()) {
            LOG.infof("[SICCT] terminal=%s auto-reconnect suppressed (administratively disconnected)", terminal.hostname);
            return;
        }
        long cappedDelay = Math.min(delayMs, MAX_BACKOFF_MS);
        if (cappedDelay >= MAX_BACKOFF_MS) {
            LOG.warnf("[SICCT][ALERT] terminal=%s at maximum reconnect backoff %dms", terminal.hostname, cappedDelay);
        }
        reconnectScheduler.schedule(() -> {
            // Re-check: an administrator may have disconnected during the backoff delay.
            if (!conn.isReconnectSuppressed()) {
                connectTerminalAsync(terminal);
            }
        }, cappedDelay, TimeUnit.MILLISECONDS);
    }

    void onTerminalDisconnected(String macAddress) {
        SicctTerminalConnection conn = connections.get(macAddress);
        if (conn != null) {
            // The card handles for this terminal are no longer reachable; drop its reader port so
            // card-handle addressed crypto operations fail fast until it reconnects and re-enumerates.
            SicctCardDiscovery discovery = cardDiscovery();
            if (discovery != null) {
                discovery.removeTerminal(conn.getTerminal().ctid);
            }
            // conn.onDisconnected() has already been invoked by the channel handler;
            // here we only decide whether to schedule an automatic reconnect.
            scheduleReconnect(conn.getTerminal(), conn, INITIAL_BACKOFF_MS * 2);
        }
    }

    /**
     * Enumerates the inserted ICCs of a connected, validly-paired terminal and builds correct card
     * handles for them (TUC_KON_001) via {@link SicctCardDiscovery}. Invoked from the SICCT channel
     * (Netty) thread after GET STATUS ALL ICC; the discovery itself runs off the event loop.
     */
    public void discoverCards(SicctTerminalConnection connection,
            List<de.servicehealtherx.sicct.codec.IccStatusDecoder.IccStatusValue> iccStatus) {
        SicctCardDiscovery discovery = cardDiscovery();
        if (discovery != null) {
            discovery.discoverCards(connection, iccStatus);
        }
    }

    /** Resolves the optional {@link SicctCardDiscovery} bean, or {@code null} in thin deployments. */
    private SicctCardDiscovery cardDiscovery() {
        return cardDiscoveryInstance != null && cardDiscoveryInstance.isResolvable()
                ? cardDiscoveryInstance.get()
                : null;
    }

    /**
     * Administrative connect (JMX): (re-)establish the TCP/TLS connection to a
     * terminal and re-enable auto-reconnect. No-op beyond a status string if the
     * terminal is already connected.
     */
    public String connectTerminal(CardTerminal terminal) {
        SicctTerminalConnection conn = connections.get(terminal.macAddress);
        if (conn != null) {
            conn.clearReconnectSuppressed();
            if (conn.getConnectionState() == SicctTerminalConnection.ConnectionState.CONNECTED) {
                return "CONNECTED";
            }
        }
        LOG.infof("[SICCT] administrative connect for terminal=%s at %s:%d", terminal.hostname,
                terminal.ipAddress, terminal.tcpPort);
        connectTerminalAsync(terminal);
        return "CONNECTING";
    }

    /**
     * Administrative disconnect (JMX): close the live channel and suppress
     * auto-reconnect until the next explicit connect.
     */
    public String disconnectTerminal(CardTerminal terminal) {
        SicctTerminalConnection conn = connections.get(terminal.macAddress);
        if (conn == null) {
            return "NOT_CONNECTED";
        }
        LOG.infof("[SICCT] administrative disconnect for terminal=%s", terminal.hostname);
        // Mark first so the channelInactive callback (fired by close()) sees the
        // suppression flag and does not schedule a reconnect.
        conn.markAdminDisconnected();
        Channel channel = conn.getChannel();
        if (channel != null && channel.isActive()) {
            channel.close();
        }
        return "DISCONNECTED";
    }

    void pairTerminal(String macAddress) {
        SicctTerminalConnection conn = connections.get(macAddress);
        if (conn != null) {
            conn.pairTerminal();
        }
    }

    /**
     * TUC_KON_050 „Beginne Kartenterminalsitzung".
     *
     * <p>
     * Implements the gemSpec_Kon sequence. The steps that exchange APDUs with the
     * terminal are inherently asynchronous (TLS handshake and SICCT command
     * responses arrive on the Netty event loop), so this method performs the
     * synchronous decision logic (steps 1, 1-IS_PHYSICAL and 2) and, where a
     * (re-)connection or re-authentication is required, hands off to the
     * asynchronous TLS → INIT CT SESSION → EHEALTH TERMINAL AUTHENTICATE VALIDATE
     * flow that {@link SicctChannelHandler} drives to completion (steps 3–11).
     *
     * @param ctId the terminal's CTID ({@code CTM_CT_LIST(ctId)})
     * @param role the operator role the session is opened for
     * @return a short status token describing the outcome / what was initiated
     */
    @Transactional
    public String TUC_KON_050_startCardTerminalSession(UUID ctId, Role role) {
        if (role == null) {
            role = Role.USER;
        }

        // Step 1: Setze CT = CTM_CT_LIST(ctId)
        CardTerminal ct = CardTerminal.findByCtid(ctId);
        if (ct == null) {
            return "TERMINAL_NOT_FOUND";
        }
        SicctTerminalConnection conn = connections.get(ct.macAddress);

        // Step 1: Wenn CT.IS_PHYSICAL = Nein (virtuelles KT / HSM in Slot 1)
        if (!ct.physical) {
            if (role != Role.USER) {
                return "HSM_REQUIRES_USER_ROLE"; // only the User role is defined for the HSM path
            }
            if (ct.connected) {
                return "CONNECTED"; // TUC ends successfully
            }
            // Verbindung zu HSM in Slot 1 aufbauen, weiter mit Schritt 9
            LOG.infof("[TUC_KON_050] virtual terminal=%s — connecting HSM in slot 1", ct.hostname);
            finalizeVirtualSession(ct, role);
            return "CONNECTED";
        }

        // Step 2: Wenn CT.CONNECTED = Ja
        if (ct.connected && conn != null
                && conn.getConnectionState() == SicctTerminalConnection.ConnectionState.CONNECTED) {
            if (role.name().equals(ct.activeRole)) {
                return "CONNECTED"; // CT.ACTIVEROLE == role → TUC ends successfully
            }
            // Different role: close the CT session (SICCT CLOSE CT SESSION) and switch
            // session, keeping the TLS connection — continue from step 6.
            SicctChannelHandler handler = conn.getSicctChannelHandler();
            if (handler != null) {
                LOG.infof("[TUC_KON_050] terminal=%s role switch %s→%s: CLOSE CT SESSION + re-authenticate",
                        ct.hostname, ct.activeRole, role);
                conn.setDesiredRole(role);
                conn.setTlsFreshlyEstablished(false); // TLS is kept; only the session changes
                handler.switchSessionRole(role);
                return "SESSION_SWITCHING";
            }
            // No live channel despite CONNECTED flag: fall through to a full reconnect.
        }

        // Step 3: Aufbau einer TLS-Verbindung mit dem Kartenterminal unter Verwendung
        // von ID.SAK.AUT. The channel handler runs the certificate check (step 5),
        // the correlation gate (step 4) and the authentication (steps 6–11) once the
        // handshake completes.
        SicctTerminalConnection target = connections.computeIfAbsent(ct.macAddress,
                id -> new SicctTerminalConnection(ct, this));
        target.setDesiredRole(role);
        target.setTlsFreshlyEstablished(true);
        target.clearReconnectSuppressed();
        if (target.getConnectionState() == SicctTerminalConnection.ConnectionState.CONNECTED
                && target.getSicctChannelHandler() != null) {
            // Transport already up (e.g. low-correlation connection) but no usable
            // session yet: run the authenticated sequence over the existing channel.
            target.getSicctChannelHandler().beginCardTerminalSession();
            return "AUTHENTICATING";
        }
        LOG.infof("[TUC_KON_050] terminal=%s — establishing TLS connection for role=%s", ct.hostname, role);
        connectTerminalAsync(ct);
        return "CONNECTING";
    }

    /**
     * TUC_KON_050 step 9 for a virtual terminal (HSM in slot 1): no TLS/authentication
     * is required, so the session is marked usable directly.
     */
    private void finalizeVirtualSession(CardTerminal ct, Role role) {
        ct.activeRole = role.name();
        ct.connected = true;
        persistLifecycleState(ct);
        TUC_KON_256("CT/CONNECTED", EventType.Operation, EventSeverity.Info,
                Map.of("CtID", String.valueOf(ct.ctid), "Hostname", String.valueOf(ct.hostname)), true, true);
    }

    /**
     * TUC_KON_256 „Systemereignis absetzen". Raises a {@link KonnektorSystemEvent} on
     * the CDI bus ({@code fireAsync}) — the konnektor-internal event distribution —
     * which lib-cetp's {@code CetpClient} observes with {@code @ObservesAsync} and, after
     * applying the per-subscription topic/access/XPath filters, delivers to subscribed
     * client systems. Optionally also writes a protocol log entry.
     *
     * <p>
     * The event is always raised on the bus; the gemSpec {@code doDisp} flag is honoured
     * downstream by the subscription filter chain in lib-cetp rather than by suppressing
     * the event at the source. {@code doLog} controls only the protocol log entry.
     */
    public void TUC_KON_256(String topic, EventType eventType, EventSeverity severity,
            Map<String, String> parameters, boolean doLog, boolean doDisp) {
        if (doLog) {
            LOG.infof("[TUC_KON_256] topic=%s type=%s severity=%s params=%s", topic, eventType, severity, parameters);
        } else {
            LOG.debugf("[TUC_KON_256] topic=%s type=%s severity=%s params=%s", topic, eventType, severity, parameters);
        }
        if (konnektorEventBus != null) {
            konnektorEventBus.fireAsync(new KonnektorSystemEvent(topic, eventType, severity, parameters));
        }
    }

    /**
     * Administrator dialog callback for the KT-certificate fingerprint shown by
     * TUC_KON_053 step 3. The implementation presents {@code fingerprint} (and, for
     * context, the {@code terminal}) on the management interface and returns whether
     * the administrator accepted (step 4) or rejected it.
     */
    @FunctionalInterface
    public interface FingerprintConfirmation {
        boolean confirm(String fingerprint, CardTerminal terminal);
    }

    /**
     * TUC_KON_053 „Kartenterminal pairen“ (gemSpec_Kon).
     *
     * <p>
     * Orchestrates the pairing of the card terminal identified by {@code ctId}:
     * <ol>
     * <li>resolves CT from CTM_CT_LIST and checks CT.VALID_VERSION;</li>
     * <li>establishes the ID.SAK.AUT TLS connection (the {@code KonnektorSslHandler}
     * offers exactly the [gemSpec_Krypt] ciphersuites for which ID.SAK.AUT key
     * material exists), stores the presented KT-certificate in CT.SMKT_AUT and
     * validates it during the handshake against the gSMC-KT trust manager
     * (TUC_KON_037, validationMode = NONE);</li>
     * <li>presents the certificate fingerprint to the administrator;</li>
     * <li>on confirmation generates ShS.KT.AUT, opens the CT session and</li>
     * <li>sends EHEALTH TERMINAL AUTHENTICATE CREATE with the shared secret and the
     * pairing display message;</li>
     * <li>verifies the returned signature and (7) advances CT.CORRELATION to
     * GEPAIRT;</li>
     * <li>closes the pairing TLS connection after a SICCT CLOSE CT SESSION;</li>
     * <li>performs the implicit GEPAIRT → AKTIV transition; and</li>
     * <li>re-establishes the working connection via TUC_KON_050 {role = User}.</li>
     * </ol>
     *
     * @param ctId         CTID of the terminal to pair (key into CTM_CT_LIST)
     * @param confirmation administrator fingerprint dialog (steps 3/4)
     * @return a short status string describing the outcome
     */
    public String TUC_KON_053_pairCardTerminal(UUID ctId, FingerprintConfirmation confirmation) {
        // Setze CT = CTM_CT_LIST(ctId). The DB read runs in its own short transaction so
        // the (potentially many-second) blocking pairing exchange does not hold one open.
        CardTerminal ct = QuarkusTransaction.requiringNew().call(() -> CardTerminal.findByCtid(ctId));
        if (ct == null) {
            return "TERMINAL_NOT_FOUND";
        }

        try {
            // 2. Aufbau der TLS-Verbindung mit ID.SAK.AUT. Schritt 2.a (Speichern des
            //    KT-Zertifikats in CT.SMKT_AUT) und 2.b (TUC_KON_037-Prüfung gegen den
            //    gSMC-KT-TrustManager) erfolgen im TLS-Handshake des KonnektorSslHandler.
            //    Die Verbindung liest außerdem das CardTerminal Manufacturer DO, aus dem
            //    CT.VALID_VERSION abgeleitet wird (Schritt 1 wird daher erst danach geprüft).
            SicctTerminalConnection conn = establishPairingConnection(ct);
            if (conn == null) {
                return "TLS_CONNECT_FAILED";
            }

            // 1. Prüfe CT.VALID_VERSION = true. Die eHealth-Interface-Version ist erst
            //    bekannt, nachdem das Kartenterminal sein Manufacturer DO über die soeben
            //    aufgebaute Verbindung gemeldet hat (TUC_KON_254).
            if (!conn.getTerminal().validVersion) {
                LOG.warnf("[TUC_KON_053] terminal=%s rejected: VALID_VERSION=false (eHealthInterfaceVersion=%s)",
                        ct.hostname, conn.getTerminal().ehealthInterfaceVersion);
                disconnectTerminal(conn.getTerminal());
                return "INVALID_VERSION";
            }

            // 3. Fingerprint dem KT-Zertifikat entnehmen und dem Administrator darstellen.
            byte[] smktAut = conn.getTerminal().smktAutCertificate;
            if (smktAut == null) {
                return "NO_KT_CERTIFICATE";
            }
            String fingerprint = sha256Fingerprint(smktAut);

            // 4. Wenn der Administrator den Fingerprint bestätigt …
            boolean accepted = confirmation != null && confirmation.confirm(fingerprint, conn.getTerminal());
            if (!accepted) {
                LOG.infof("[TUC_KON_053] administrator rejected fingerprint %s for terminal=%s",
                        fingerprint, ct.hostname);
                disconnectTerminal(conn.getTerminal());
                return "FINGERPRINT_REJECTED";
            }

            // 4.a ShS.KT.AUT erzeugen + in CT.SHARED_SECRET ablegen, 4.b INIT CT SESSION
            //     (bereits bei channelActive eröffnet), 5. EHEALTH TERMINAL AUTHENTICATE
            //     CREATE, 6. Signaturprüfung und 7. CT.CORRELATION = „gepairt“ laufen in
            //     ehealthTerminalAuthenticateCreate(); die Zukunft signalisiert das Ergebnis.
            Boolean paired = conn.pairTerminal().get(PAIRING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!Boolean.TRUE.equals(paired)) {
                LOG.warnf("[TUC_KON_053] signature verification failed for terminal=%s", ct.hostname);
                disconnectTerminal(conn.getTerminal());
                return "PAIRING_FAILED";
            }

            // 8. TLS-Verbindung, die zum Pairen diente, beenden — zuvor SICCT CLOSE CT
            //    SESSION mit ctId als Adressat senden.
            conn.closeCtSession();
            disconnectTerminal(conn.getTerminal());

            // 9. Automatischer Zustandsübergang CT.CORRELATION „gepairt“ → „aktiv“.
            conn.onAktiv();

            // 10. „Arbeits“-TLS-Verbindung neu aufbauen durch Aufruf TUC_KON_050
            //     { ctId; role = „User“ }. TUC_KON_050 ist noch nicht implementiert; bis
            //     dahin wird die Arbeitsverbindung über connectTerminal() wiederhergestellt.
            CardTerminal pairedTerminal = conn.getTerminal();
            pairedTerminal.activeRole = "User";
            connectTerminal(pairedTerminal);

            LOG.infof("[TUC_KON_053] terminal=%s paired successfully (fingerprint=%s)",
                    ct.hostname, fingerprint);
            return "PAIRED";
        } catch (Exception e) {
            LOG.errorf(e, "[TUC_KON_053] pairing failed for terminal=%s", ct.hostname);
            return "PAIRING_ERROR: " + e.getMessage();
        }
    }

    /**
     * Outcome of {@link #requestPairTerminal(UUID)}: either the KT-certificate
     * fingerprint to present to the administrator (then continued via
     * {@link #confirmFingerprint(String)} / {@link #rejectFingerprint(String)}), or a
     * terminal status when the flow could not reach the confirmation step.
     */
    public record PairingRequestResult(boolean awaitingConfirmation, String fingerprint, String status) {
    }

    /** Coordination state for one interactive TUC_KON_053 pairing in progress. */
    private static final class PendingPairing {
        final CompletableFuture<String> fingerprint = new CompletableFuture<>();
        final CompletableFuture<Boolean> decision = new CompletableFuture<>();
        final CompletableFuture<String> result = new CompletableFuture<>();
        volatile ScheduledFuture<?> timeoutTask;
    }

    /**
     * TUC_KON_053 steps 2–4 (request phase): establishes the pairing TLS connection and
     * returns the KT-certificate fingerprint for the administrator to verify. The flow
     * then pauses until {@link #confirmFingerprint(String)} or
     * {@link #rejectFingerprint(String)} is called with that fingerprint; if no decision
     * arrives within {@value #PAIRING_PROCESS_TIMEOUT_MS} ms the whole process is
     * abandoned.
     *
     * @return {@link PairingRequestResult#awaitingConfirmation()} = {@code true} with the
     *         fingerprint on the happy path, or {@code false} with a status when the flow
     *         ended early (e.g. {@code INVALID_VERSION}, {@code TLS_CONNECT_FAILED},
     *         {@code PAIRING_ALREADY_IN_PROGRESS}, {@code PAIRING_TIMEOUT}).
     */
    public PairingRequestResult requestPairTerminal(UUID ctId) {
        PendingPairing pending = new PendingPairing();
        if (pendingByCtId.putIfAbsent(ctId, pending) != null) {
            return new PairingRequestResult(false, null, "PAIRING_ALREADY_IN_PROGRESS");
        }

        // Centralised cleanup once the flow concludes (success, rejection, error, timeout).
        pending.result.whenComplete((status, ex) -> {
            pendingByCtId.remove(ctId, pending);
            String fp = pending.fingerprint.getNow(null);
            if (fp != null) {
                pendingByFingerprint.remove(fp, pending);
            }
            ScheduledFuture<?> task = pending.timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        });

        // Step 3/4 bridge: publish the fingerprint, then block the flow thread until an
        // administrator confirms or rejects (or the process budget elapses).
        FingerprintConfirmation confirmation = (fp, terminal) -> {
            pendingByFingerprint.put(fp, pending);
            pending.fingerprint.complete(fp);
            try {
                return pending.decision.get(PAIRING_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return false;
            }
        };

        // Run the blocking TUC_KON_053 flow off the caller (JMX) thread.
        pairingExecutor.submit(() -> {
            String outcome;
            try {
                outcome = TUC_KON_053_pairCardTerminal(ctId, confirmation);
            } catch (Exception e) {
                outcome = "PAIRING_ERROR: " + e.getMessage();
            }
            pending.result.complete(outcome);
        });

        // Whole-process budget: abandon if no confirm/reject decision arrives in time.
        pending.timeoutTask = reconnectScheduler.schedule(() -> {
            if (pending.result.complete("PAIRING_TIMEOUT")) {
                pending.decision.complete(false);
                LOG.warnf("[TUC_KON_053] pairing for ctId=%s timed out after %dms",
                        ctId, PAIRING_PROCESS_TIMEOUT_MS);
            }
        }, PAIRING_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Return as soon as the fingerprint is available (happy path) or the flow ends early.
        try {
            CompletableFuture.anyOf(pending.fingerprint, pending.result)
                    .get(PAIRING_PROCESS_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pending.result.complete("PAIRING_TIMEOUT");
            return new PairingRequestResult(false, null, "PAIRING_TIMEOUT");
        }

        if (pending.fingerprint.isDone() && !pending.fingerprint.isCompletedExceptionally()) {
            return new PairingRequestResult(true, pending.fingerprint.getNow(null), "AWAITING_CONFIRMATION");
        }
        // Flow concluded before producing a fingerprint (version/TLS failure, timeout …).
        return new PairingRequestResult(false, null, pending.result.getNow("PAIRING_FAILED"));
    }

    /**
     * TUC_KON_053 step 4 (accept): the administrator confirms the previously presented
     * {@code fingerprint}; the paused pairing flow continues through to AKTIV.
     *
     * @return the final pairing status (e.g. {@code PAIRED}), or {@code NO_PENDING_PAIRING}
     *         if no pairing is awaiting this fingerprint.
     */
    public String confirmFingerprint(String fingerprint) {
        PendingPairing pending = pendingByFingerprint.remove(fingerprint);
        if (pending == null) {
            return "NO_PENDING_PAIRING";
        }
        ScheduledFuture<?> task = pending.timeoutTask;
        if (task != null) {
            task.cancel(false);
        }
        pending.decision.complete(true);
        try {
            return pending.result.get(PAIRING_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "PAIRING_ERROR: " + e.getMessage();
        }
    }

    /**
     * TUC_KON_053 step 4 (reject): the administrator rejects the presented
     * {@code fingerprint}; the paused pairing flow is cancelled and the terminal
     * disconnected.
     *
     * @return {@code FINGERPRINT_REJECTED}, or {@code NO_PENDING_PAIRING} if no pairing
     *         is awaiting this fingerprint.
     */
    public String rejectFingerprint(String fingerprint) {
        PendingPairing pending = pendingByFingerprint.remove(fingerprint);
        if (pending == null) {
            return "NO_PENDING_PAIRING";
        }
        ScheduledFuture<?> task = pending.timeoutTask;
        if (task != null) {
            task.cancel(false);
        }
        pending.decision.complete(false);
        try {
            return pending.result.get(PAIRING_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "PAIRING_ERROR: " + e.getMessage();
        }
    }

    /**
     * TUC_KON_053 step 2: ensures a usable ID.SAK.AUT TLS connection to {@code ct} —
     * (re-)initiating the connection if needed and waiting until the SICCT channel is
     * up, the KT-certificate (CT.SMKT_AUT) has been captured from the handshake and the
     * CardTerminal Manufacturer DO has been processed (so CT.VALID_VERSION is known).
     *
     * @return the live connection, or {@code null} if it did not become usable within
     *         {@link #TLS_CONNECT_TIMEOUT_MS}.
     */
    private SicctTerminalConnection establishPairingConnection(CardTerminal ct) throws InterruptedException {
        SicctTerminalConnection conn = connections.get(ct.macAddress);
        if (conn == null || conn.getConnectionState() != SicctTerminalConnection.ConnectionState.CONNECTED) {
            connectTerminal(ct);
        }
        long deadline = System.currentTimeMillis() + TLS_CONNECT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            conn = connections.get(ct.macAddress);
            if (conn != null
                    && conn.getConnectionState() == SicctTerminalConnection.ConnectionState.CONNECTED
                    && conn.getSicctChannelHandler() != null
                    && conn.getTerminal().smktAutCertificate != null
                    && conn.getSicctChannelHandler().isManufacturerInfoReceived()) {
                return conn;
            }
            Thread.sleep(PAIRING_POLL_INTERVAL_MS);
        }
        LOG.warnf("[TUC_KON_053] terminal=%s did not reach a usable TLS state within %dms",
                ct.hostname, TLS_CONNECT_TIMEOUT_MS);
        return null;
    }

    /**
     * TUC_KON_053 step 3: SHA-256 fingerprint of the KT-certificate (CT.SMKT_AUT),
     * formatted as colon-separated upper-case hex byte pairs for display.
     */
    static String sha256Fingerprint(byte[] certBytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(certBytes);
            HexFormat hex = HexFormat.of().withUpperCase();
            StringBuilder sb = new StringBuilder(hash.length * 3);
            for (int i = 0; i < hash.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(hex.toHexDigits(hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public Map<String, SicctTerminalConnection> getConnections() {
        return connections;
    }

    /**
     * Persists a terminal's lifecycle state (correlation + connected flag) so the
     * database row matches the live in-memory entity. Invoked from the SICCT channel
     * (Netty) thread when the lifecycle advances; best-effort, so a persistence
     * failure is logged but never propagated into the protocol handling.
     */
    @Transactional
    public void persistLifecycleState(CardTerminal terminal) {
        if (terminal.ctid == null) {
            // Not a managed/persisted terminal (e.g. standalone test fixture).
            return;
        }
        try {
            CardTerminal managed = CardTerminal.findById(terminal.ctid);
            if (managed != null) {
                managed.correlation = terminal.correlation;
                managed.connected = terminal.connected;
                managed.activeRole = terminal.activeRole;
                managed.slotsUsed = terminal.slotsUsed;
                // The pairing secret (ShS.KT.AUT) cannot be re-derived on a later connection,
                // so it MUST survive a restart for the reconnect VALIDATE to succeed. Stored as
                // produced by TpmSealer.protect() (TPM-sealed when available, else unsealed).
                managed.sealedSharedSecret = terminal.sealedSharedSecret;
            }
        } catch (Exception e) {
            LOG.warnf(e, "[SicctTerminalManager] failed to persist lifecycle state (correlation=%s connected=%s) for terminal=%s",
                    terminal.correlation, terminal.connected, terminal.hostname);
        }
    }

    /**
     * Persists the CardTerminal Manufacturer DO fields (product information, eHealth
     * interface version and the derived VALID_VERSION flag). Invoked from the SICCT
     * channel (Netty) thread when the GET STATUS MANUFACTURER response is processed;
     * best-effort, so a persistence failure is logged but never propagated.
     */
    @Transactional
    public void persistManufacturerInfo(CardTerminal terminal) {
        if (terminal.ctid == null) {
            // Not a managed/persisted terminal (e.g. standalone test fixture).
            return;
        }
        try {
            CardTerminal managed = CardTerminal.findById(terminal.ctid);
            if (managed != null) {
                managed.productInformation = terminal.productInformation;
                managed.ehealthInterfaceVersion = terminal.ehealthInterfaceVersion;
                managed.validVersion = terminal.validVersion;
            }
        } catch (Exception e) {
            LOG.warnf(e, "[SicctTerminalManager] failed to persist manufacturer info (validVersion=%s) for terminal=%s",
                    terminal.validVersion, terminal.hostname);
        }
    }

    /**
     * TUC_KON_254 version check: whether the given eHealth interface version (VER field
     * of the CardTerminal Manufacturer DO) is on this konnektor's supported list.
     */
    public boolean isEhealthInterfaceVersionSupported(String version) {
        if (version == null || version.isBlank() || supportedEhealthInterfaceVersions == null) {
            return false;
        }
        String trimmed = version.trim();
        return supportedEhealthInterfaceVersions.stream().anyMatch(v -> v.trim().equals(trimmed));
    }

    @Transactional
    public List<CardTerminal> listAllTerminals() {
        return CardTerminal.listAll();
    }

    public SmkCSAKAut getSmkCSAKAut() {
        return smkCSAKAut;
    }

    public TrustManager getGSMCKtTrustManager() {
        return gSMCKtTrustManager;
    }

    /** The TPM 2.0 sealer used to protect/recover the pairing secret; may be {@code null} in tests. */
    public TpmSealer getTpmSealer() {
        return tpmSealer;
    }

    public static void main(String[] args) throws InterruptedException {

        // 2. Root-Logger mit Handler konfigurieren
        org.jboss.logmanager.Logger rootLogger = org.jboss.logmanager.Logger.getLogger("");
        rootLogger.setLevel(java.util.logging.Level.INFO);

        // 3. Console-Handler explizit hinzufügen
        java.util.logging.ConsoleHandler consoleHandler = new java.util.logging.ConsoleHandler();
        consoleHandler.setLevel(java.util.logging.Level.FINEST);
        rootLogger.addHandler(consoleHandler);

        // 4. Spezifischen Logger setzen
        org.jboss.logmanager.Logger logger = org.jboss.logmanager.Logger.getLogger(SicctDecoder.class.getName());
        logger.setLevel(java.util.logging.Level.FINEST);

        logger = org.jboss.logmanager.Logger
                .getLogger(SicctChannelHandler.class.getName());
        logger.setLevel(java.util.logging.Level.FINEST);

        logger = org.jboss.logmanager.Logger
                .getLogger(SslHandler.class.getName());
        logger.setLevel(java.util.logging.Level.FINEST);

        // For standalone testing without Quarkus; in production, Quarkus will call
        // @PostConstruct
        SicctTerminalManager manager = new SicctTerminalManager();
        var t = new CardTerminal();
        t.ctid = UUID.fromString("6f831776-2c0e-41da-a889-7f0827c88a19");

        // t.hostname = "ORGA6100-01410000021FB1";
        // t.ipAddress = "192.168.100.90";
        // t.macAddress = "00:0D:F8:05:D3:0E";

        t.hostname = "192-168-100-1";
        t.ipAddress = "192.168.100.1";
        t.macAddress = "00:E0:4C:68:01:D2";

        t.tcpPort = 4742;
        manager.terminals = List.of(t);
        manager.smkCSAKAut = new SmkCSAKAutProvider().createSmkCSAKAut();
        TrustManagerProducer trustManagerProducer = new TrustManagerProducer();
        TslDownloader tslDownloader = new TslDownloader();
        tslDownloader.init(); // Manually initialize TslDownloader to load TSP services before producing the
                              // TrustManager
        trustManagerProducer.setTslDownloader(tslDownloader);
        manager.gSMCKtTrustManager = trustManagerProducer.produceGSMCKtTrustManager();
        manager.initialize();
        Thread.sleep(3_000); // Wait for connection attempt and potential pairing
        manager.pairTerminal(t.macAddress);
    }

    public String getHostname() {
        // Search for a non-null hostname in the network interfaces of the machine
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            String hostname = localHost.getHostName();
            if (hostname != null && !hostname.isEmpty()) {
                return hostname;
            }
        } catch (Exception e) {
            LOG.warn("Failed to get local hostname, falling back to IP address", e);
        }
        return "unknown";
    }
}
