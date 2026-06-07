package de.servicehealtherx.quarkus.sicct.runtime;

import de.servicehealtherx.crypto.GSMCKtTrustManager;
import de.servicehealtherx.crypto.TrustManagerProducer;
import de.servicehealtherx.crypto.TslDownloader;
import de.servicehealtherx.quarkus.sicct.runtime.tls.SICCTKonnektorTLSChannelInitializer;
import de.servicehealtherx.quarkus.sicct.runtime.tls.SmkCSAKAut;
import de.servicehealtherx.quarkus.sicct.runtime.tls.SmkCSAKAutProvider;
import de.servicehealtherx.sicct.EhealthAuthenticator;
import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

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
    EhealthAuthenticator ehealthAuthenticator;

    @Inject
    TpmSealer tpmSealer;

    @Inject
    SmkCSAKAut smkCSAKAut;

    @Inject
    @GSMCKtTrustManager
    TrustManager gSMCKtTrustManager;

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
        SicctTerminalConnection conn = connections.computeIfAbsent(terminal.hostname,
                id -> new SicctTerminalConnection(terminal));

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new SICCTKonnektorTLSChannelInitializer(this, conn));

        ChannelFuture future = bootstrap.connect(terminal.ipAddress, terminal.tcpPort);

        future.addListener(f -> {
            if (f.isSuccess()) {
                conn.onConnected(future.channel());
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
        long cappedDelay = Math.min(delayMs, MAX_BACKOFF_MS);
        if (cappedDelay >= MAX_BACKOFF_MS) {
            LOG.warnf("[SICCT][ALERT] terminal=%s at maximum reconnect backoff %dms", terminal.hostname, cappedDelay);
        }
        reconnectScheduler.schedule(() -> connectTerminalAsync(terminal), cappedDelay, TimeUnit.MILLISECONDS);
    }

    void onTerminalDisconnected(String hostname) {
        SicctTerminalConnection conn = connections.get(hostname);
        if (conn != null) {
            conn.onDisconnected();
            CardTerminal terminal = conn.getTerminal();
            scheduleReconnect(terminal, conn, INITIAL_BACKOFF_MS * 2);
        }
    }

    public Map<String, SicctTerminalConnection> getConnections() {
        return connections;
    }

    public List<CardTerminal> listAllTerminals() {
        return CardTerminal.listAll();
    }

    public SmkCSAKAut getSmkCSAKAut() {
        return smkCSAKAut;
    }

    public TrustManager getGSMCKtTrustManager() {
        return gSMCKtTrustManager;
    }

    public static void main(String[] args) {
        // For standalone testing without Quarkus; in production, Quarkus will call
        // @PostConstruct
        SicctTerminalManager manager = new SicctTerminalManager();
        var t = new CardTerminal();
        t.ctid = UUID.fromString("6f831776-2c0e-41da-a889-7f0827c88a19");
        t.hostname = "ORGA6100-01410000021FB1";
        t.ipAddress = "192.168.100.90";
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
    }
}
