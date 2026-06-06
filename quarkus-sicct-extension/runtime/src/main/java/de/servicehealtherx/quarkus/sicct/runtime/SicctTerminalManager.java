package de.servicehealtherx.quarkus.sicct.runtime;

import de.servicehealtherx.quarkus.sicct.runtime.tls.KonnektorSslHandler;
import de.servicehealtherx.quarkus.sicct.runtime.tls.SICCTKonnektorTLSChannelInitializer;
import de.servicehealtherx.sicct.EhealthAuthenticator;
import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages SICCT card terminal TCP/TLS connections.
 * Loads CardTerminal JPA records at startup; initiates connections via shared
 * NioEventLoopGroup.
 * One terminal failure MUST NOT prevent others per FR-023, FR-097.
 */
@ApplicationScoped
public class SicctTerminalManager {

    private static final Logger LOG = Logger.getLogger(SicctTerminalManager.class);

    @Inject
    EhealthAuthenticator ehealthAuthenticator;

    @Inject
    TpmSealer tpmSealer;

    private EventLoopGroup eventLoopGroup;
    private final Map<String, SicctTerminalConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectScheduler = Executors.newScheduledThreadPool(2);

    @PostConstruct
    @Transactional
    void initialize() {
        eventLoopGroup = new NioEventLoopGroup(4);
        tpmSealer.initialize();

        List<CardTerminal> terminals = CardTerminal.listAll();
        LOG.infof("[SicctTerminalManager] loaded %d terminal(s) from DB", terminals.size());

        for (CardTerminal terminal : terminals) {
            try {
                connectTerminalAsync(terminal);
            } catch (Exception e) {
                // One terminal failure MUST NOT prevent others per FR-023
                LOG.errorf(e, "[SicctTerminalManager] failed to initiate connection for terminalId=%s, continuing",
                        terminal.terminalId);
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

    public void connectTerminal(String terminalId, String host, int port) {
        CardTerminal terminal = CardTerminal.findByTerminalId(terminalId);
        if (terminal == null) {
            LOG.warnf("[SicctTerminalManager] connectTerminal: terminalId=%s not found in DB", terminalId);
            return;
        }
        connectTerminalAsync(terminal);
    }

    private void connectTerminalAsync(CardTerminal terminal) {
        SicctTerminalConnection conn = connections.computeIfAbsent(terminal.terminalId,
                id -> new SicctTerminalConnection(terminal));

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .handler(new SICCTKonnektorTLSChannelInitializer(this, conn));

        ChannelFuture future = bootstrap.connect(terminal.host, terminal.port);
        future.addListener(f -> {
            if (f.isSuccess()) {
                conn.onConnected(future.channel());
                LOG.infof("[SICCT] connected to terminal=%s at %s:%d", terminal.terminalId, terminal.host,
                        terminal.port);
            } else {
                LOG.warnf("[SICCT] failed to connect to terminal=%s at %s:%d, scheduling reconnect",
                        terminal.terminalId, terminal.host, terminal.port);
                scheduleReconnect(terminal, conn, terminal.initialBackoffMs);
            }
        });
    }

    private void scheduleReconnect(CardTerminal terminal, SicctTerminalConnection conn, long delayMs) {
        long cappedDelay = Math.min(delayMs, terminal.maxBackoffMs);
        if (cappedDelay >= terminal.maxBackoffMs) {
            LOG.warnf("[SICCT][ALERT] terminal=%s at maximum reconnect backoff %dms", terminal.terminalId, cappedDelay);
        }
        reconnectScheduler.schedule(() -> connectTerminalAsync(terminal), cappedDelay, TimeUnit.MILLISECONDS);
    }

    void onTerminalDisconnected(String terminalId) {
        SicctTerminalConnection conn = connections.get(terminalId);
        if (conn != null) {
            conn.onDisconnected();
            CardTerminal terminal = conn.getTerminal();
            scheduleReconnect(terminal, conn, (long) terminal.initialBackoffMs * 2);
        }
    }

    public Map<String, SicctTerminalConnection> getConnections() {
        return connections;
    }
}
