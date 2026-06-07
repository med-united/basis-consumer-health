package de.servicehealtherx.quarkus.sicct.runtime.tls;

import javax.net.ssl.TrustManager;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;

public class KonnektorSslHandler extends SslHandler {

    public KonnektorSslHandler(SmkCSAKAut smkCSAKAut, TrustManager trustManager, ByteBufAllocator alloc) {
        super(createSslContext(smkCSAKAut, trustManager).newEngine(alloc));
    }

    private static SslContext createSslContext(SmkCSAKAut smkCSAKAut, TrustManager trustManager) {
        try {
            // Build SslContext for mutual TLS (client authentication)
            return SslContextBuilder.forClient()
                    .keyManager(smkCSAKAut.getKeyManagerFactory())
                    .clientAuth(ClientAuth.REQUIRE)
                    .trustManager(trustManager)
                    .ciphers(GematikSSLConfig.CIPHER_SUITE_LIST)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL context for Konnektor TLS", e);
        }
    }
}
