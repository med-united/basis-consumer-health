package de.servicehealtherx.quarkus.sicct.runtime.tls;

import de.servicehealtherx.quarkus.sicct.runtime.SicctApduChannelHandler;
import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalConnection;
import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalManager;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class SICCTKonnektorTLSChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final SicctTerminalManager manager;
    private final SicctTerminalConnection conn;

    public SICCTKonnektorTLSChannelInitializer(SicctTerminalManager manager, SicctTerminalConnection conn) {
        this.manager = manager;
        this.conn = conn;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        // Pipeline: TLS → SICCT framing → APDU handler
        // Full TLS mutual auth implementation per FR-133 (ECC/RSA, DES MUST NOT)
        ch.pipeline().addFirst("ssl", new KonnektorSslHandler(ch.alloc()));
        ch.pipeline().addLast(new SicctApduChannelHandler(conn, manager));
    }
}
