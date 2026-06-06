package de.servicehealtherx.crypto.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "quarkus.crypto.p12")
public interface P12KeyStoreConfig {

    @WithParentName
    List<P12Entry> keystores();

    interface P12Entry {
        String alias();
        String path();
        String keystorePassword();
        Optional<String> entryPassword();
        Optional<String> entryAlias();

        default String entryPasswordOrKeystore() {
            return entryPassword().orElse(keystorePassword());
        }
    }
}
