package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.util.concurrent.Executor;

/**
 * Builds the per-connection LDAP-Proxy pipeline: BER frame decoder → LDAP message encoder →
 * proxy handler.
 */
public class LdapProxyChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final VzdConnectionFactory connectionFactory;
    private final Executor upstreamExecutor;
    private final int maxMessageSize;

    public LdapProxyChannelInitializer(VzdConnectionFactory connectionFactory, Executor upstreamExecutor,
            int maxMessageSize) {
        this.connectionFactory = connectionFactory;
        this.upstreamExecutor = upstreamExecutor;
        this.maxMessageSize = maxMessageSize;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast("logging", new LoggingHandler(LogLevel.TRACE))
                .addLast("ldapDecoder", new LdapMessageDecoder(maxMessageSize))
                .addLast("ldapEncoder", new LdapMessageEncoder())
                .addLast("proxy", new LdapProxyFrontendHandler(connectionFactory, upstreamExecutor));
    }
}
