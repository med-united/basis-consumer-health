package de.servicehealtherx.crypto;

import de.servicehealtherx.crypto.adapter.P12KeyStoreAdapter;
import de.servicehealtherx.crypto.adapter.PcscKeyStoreAdapter;
import de.servicehealtherx.crypto.adapter.Pkcs11KeyStoreAdapter;
import de.servicehealtherx.crypto.adapter.SicctKeyStoreAdapter;
import de.servicehealtherx.crypto.config.P12KeyStoreConfig;
import de.servicehealtherx.crypto.config.Pkcs11KeyStoreConfig;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class CryptoProviderRouter implements CryptoProvider {

    private static final Logger LOG = Logger.getLogger(CryptoProviderRouter.class);

    @Inject
    Optional<P12KeyStoreConfig> p12Config;

    @Inject
    Optional<Pkcs11KeyStoreConfig> pkcs11Config;

    private final Map<String, KeyStoreAdapter> adapters = new ConcurrentHashMap<>();

    @PostConstruct
    void initialize() {
        List<String> registeredAliases = new ArrayList<>();

        p12Config.ifPresent(cfg -> cfg.keystores().forEach(entry -> {
            checkDuplicateAlias(entry.alias(), registeredAliases);
            P12KeyStoreAdapter adapter = new P12KeyStoreAdapter(
                entry.alias(), entry.path(), entry.keystorePassword(), entry.entryPassword());
            try {
                adapter.engineLoad(null, null);
            } catch (IOException e) {
                LOG.warnf("[CryptoProviderRouter] P12 load failed for alias=%s, marking UNAVAILABLE", entry.alias());
            }
            adapters.put(entry.alias(), adapter);
            registeredAliases.add(entry.alias());
        }));

        pkcs11Config.ifPresent(cfg -> cfg.keystores().forEach(entry -> {
            checkDuplicateAlias(entry.alias(), registeredAliases);
            Pkcs11KeyStoreAdapter adapter = new Pkcs11KeyStoreAdapter(
                entry.alias(), entry.name(), entry.libraryPath(), entry.tokenPin(), entry.slotListIndex());
            try {
                adapter.engineLoad(null, null);
            } catch (IOException e) {
                LOG.warnf("[CryptoProviderRouter] PKCS11 load failed for alias=%s, marking UNAVAILABLE", entry.alias());
            }
            adapters.put(entry.alias(), adapter);
            registeredAliases.add(entry.alias());
        }));

        LOG.infof("[CryptoProviderRouter] initialized with %d adapter(s): %s", adapters.size(),
            String.join(", ", adapters.keySet()));
    }

    private void checkDuplicateAlias(String alias, List<String> existing) {
        if (existing.contains(alias)) {
            throw new IllegalStateException(
                "Duplicate alias detected at startup: '" + alias + "'. Each alias must be globally unique.");
        }
    }

    public void registerAdapter(KeyStoreAdapter adapter) {
        String aliasValue = adapter.descriptor().alias.value();
        if (adapters.containsKey(aliasValue)) {
            throw new IllegalStateException("Cannot register adapter: alias '" + aliasValue + "' already registered");
        }
        adapters.put(aliasValue, adapter);
        LOG.infof("[CryptoProviderRouter] dynamically registered adapter alias=%s type=%s", aliasValue, adapter.sourceType());
    }

    @Override
    public CryptoOperationResult sign(CryptoOperationRequest request) {
        return route(request).sign(request);
    }

    @Override
    public boolean verify(CryptoOperationRequest request, byte[] signature) {
        return route(request).verify(request, signature);
    }

    @Override
    public CryptoOperationResult encrypt(CryptoOperationRequest request) {
        return route(request).encrypt(request);
    }

    @Override
    public CryptoOperationResult decrypt(CryptoOperationRequest request) {
        return route(request).decrypt(request);
    }

    @Override
    public List<KeyStoreDescriptor> listKeyStores() {
        return new ArrayList<>(adapters.values().stream()
            .map(KeyStoreAdapter::descriptor)
            .collect(Collectors.toList()));
    }

    @Override
    public KeyStoreAvailability getAvailability(KeyAlias alias) {
        KeyStoreAdapter adapter = adapters.get(alias.value());
        if (adapter == null) {
            return KeyStoreAvailability.UNAVAILABLE;
        }
        return adapter.descriptor().getAvailability();
    }

    @Override
    public Map<String, KeyStoreAvailability> getAvailabilities() {
        Map<String, KeyStoreAvailability> result = new HashMap<>();
        adapters.forEach((alias, adapter) -> result.put(alias, adapter.descriptor().getAvailability()));
        return result;
    }

    private KeyStoreAdapter route(CryptoOperationRequest request) {
        KeyStoreAdapter adapter = adapters.get(request.alias.value());
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter registered for alias: " + request.alias);
        }
        if (adapter.descriptor().getAvailability() != KeyStoreAvailability.AVAILABLE) {
            throw new IllegalStateException("Key source UNAVAILABLE for alias: " + request.alias +
                " (status: " + adapter.descriptor().getAvailability() + ")");
        }
        return adapter;
    }
}
