package de.servicehealtherx.quarkus.sicct.runtime.tls;

import de.servicehealtherx.quarkus.sicct.runtime.SicctApduChannelHandler;
import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalConnection;
import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

public class SICCTKonnektorTLSChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final InternalLogger logger = InternalLoggerFactory
            .getInstance(SICCTKonnektorTLSChannelInitializer.class);

    private final SicctTerminalManager manager;
    private final SicctTerminalConnection conn;

    public SICCTKonnektorTLSChannelInitializer(SicctTerminalManager manager, SicctTerminalConnection conn) {
        this.manager = manager;
        this.conn = conn;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addFirst(new LoggingHandler(LogLevel.DEBUG));
        // Pipeline: TLS → SICCT framing → APDU handler
        // Full TLS mutual auth implementation per FR-133 (ECC/RSA, DES MUST NOT)
        ch.pipeline().addFirst("ssl",
                new KonnektorSslHandler(manager.getSmkCSAKAut(), manager.getGSMCKtTrustManager(), ch.alloc()));
        ch.pipeline().addLast(new SicctApduChannelHandler(conn, manager));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.warn("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
        ctx.close();
    }
}
