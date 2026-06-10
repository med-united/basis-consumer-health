package de.servicehealtherx.crypto.p12;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "quarkus.crypto.p12")
public interface P12CryptoConfig {

    String certsDir();
}
