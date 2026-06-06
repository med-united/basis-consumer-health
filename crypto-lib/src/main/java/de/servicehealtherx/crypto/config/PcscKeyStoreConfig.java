package de.servicehealtherx.crypto.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "quarkus.crypto.pcsc")
public interface PcscKeyStoreConfig {

    @WithParentName
    List<PcscEntry> readers();

    interface PcscEntry {
        String alias();
        Optional<String> readerName();
    }
}
