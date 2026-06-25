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
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    List<CardTerminal> terminals = null;

    private EventLoopGroup eventLoopGroup;
    private final Map<String, SicctTerminalConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectScheduler = Executors.newScheduledThreadPool(2);

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
            // conn.onDisconnected() has already been invoked by the channel handler;
            // here we only decide whether to schedule an automatic reconnect.
            scheduleReconnect(conn.getTerminal(), conn, INITIAL_BACKOFF_MS * 2);
        }
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
            }
        } catch (Exception e) {
            LOG.warnf(e, "[SicctTerminalManager] failed to persist lifecycle state (correlation=%s connected=%s) for terminal=%s",
                    terminal.correlation, terminal.connected, terminal.hostname);
        }
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
