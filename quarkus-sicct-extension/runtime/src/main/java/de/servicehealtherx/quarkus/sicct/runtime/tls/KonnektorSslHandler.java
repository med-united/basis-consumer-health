package de.servicehealtherx.quarkus.sicct.runtime.tls;

import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;

public class KonnektorSslHandler extends SslHandler {

    static KeyStore keyStore;
    static KeyManagerFactory kmf;

    public KonnektorSslHandler(ByteBufAllocator alloc) {
        super(createSslContext().newEngine(alloc));
    }

    private static SslContext createSslContext() {
        try {
            // Build SslContext for mutual TLS (client authentication)
            return SslContextBuilder.forClient()
                    .keyManager(kmf)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL context for Konnektor TLS", e);
        }
    }
}
