package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Boots the Netty server socket for the Basis-Consumer LDAP-Proxy (gemSpec_Basis_Consumer 4.2 / 6.4).
 *
 * <p>Accepts LDAPv3 client connections and proxies the supported operations to the VZD. The server
 * is started only when {@code ldap.proxy.enabled=true}.</p>
 */
@Startup
@ApplicationScoped
public class LdapProxyServer {

    private static final Logger LOG = Logger.getLogger(LdapProxyServer.class);

    @Inject
    LdapProxyConfig config;

    @Inject
    VzdConnectionFactory connectionFactory;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ExecutorService upstreamExecutor;
    private Channel serverChannel;

    @PostConstruct
    void start() {
        if (!config.enabled()) {
            LOG.info("[LDAP-Proxy] disabled (set ldap.proxy.enabled=true to start)");
            return;
        }

        bossGroup = new NioEventLoopGroup(1, namedThreadFactory("ldap-proxy-boss"));
        workerGroup = new NioEventLoopGroup(namedThreadFactory("ldap-proxy-worker"));
        upstreamExecutor = Executors.newFixedThreadPool(
                Math.max(1, config.upstreamThreads()), namedThreadFactory("ldap-proxy-vzd"));

        try {
            final ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new LdapProxyChannelInitializer(
                            connectionFactory, upstreamExecutor, config.maxMessageSize()));

            serverChannel = bootstrap.bind(config.bindAddress(), config.port()).sync().channel();

            LOG.infof("[LDAP-Proxy] listening on %s:%d → VZD %s:%d (ssl=%s)",
                    config.bindAddress(), config.port(),
                    config.vzd().host().orElse("<unconfigured>"), config.vzd().port(), config.vzd().useSsl());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new IllegalStateException("Interrupted while binding the LDAP-Proxy server socket", e);
        } catch (RuntimeException e) {
            shutdownGroups();
            throw e;
        }
    }

    @PreDestroy
    void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        shutdownGroups();
        if (upstreamExecutor != null) {
            upstreamExecutor.shutdown();
            upstreamExecutor = null;
        }
        LOG.info("[LDAP-Proxy] stopped");
    }

    private void shutdownGroups() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
