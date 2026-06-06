package de.servicehealtherx.crypto.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;

import java.util.List;

@ConfigMapping(prefix = "quarkus.crypto.pkcs11")
public interface Pkcs11KeyStoreConfig {

    @WithParentName
    List<Pkcs11Entry> keystores();

    interface Pkcs11Entry {
        String alias();
        String name();
        String libraryPath();
        String tokenPin();
        int slotListIndex();
    }
}
