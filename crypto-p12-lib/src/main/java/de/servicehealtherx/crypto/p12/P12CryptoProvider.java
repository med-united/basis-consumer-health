package de.servicehealtherx.crypto.p12;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.SourceType;
import de.servicehealtherx.crypto.adapter.P12KeyStoreAdapter;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jboss.logging.Logger;

import java.security.Security;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@ApplicationScoped
public class P12CryptoProvider implements CryptoProvider {

    private static final Logger LOG = Logger.getLogger(P12CryptoProvider.class);

    @Inject
    P12CryptoConfig config;

    @Inject
    P12CertScanner scanner;

    private final CopyOnWriteArrayList<P12KeyStoreAdapter> adapters = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, P12KeyStoreAdapter> byAlias = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();

    @PostConstruct
    void init() {
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            LOG.info("[P12Provider] registered BouncyCastle JCE provider for brainpool curve support");
        }
        List<P12KeyStoreAdapter> loaded = scanner.scan(config.certsDir());
        for (P12KeyStoreAdapter adapter : loaded) {
            adapters.add(adapter);
            byAlias.put(adapter.descriptor().alias.value(), adapter);
        }
        LOG.infof("[P12Provider] initialised with %d keystore(s) from %s", adapters.size(), config.certsDir());
    }

    @Override
    public CryptoOperationResult sign(CryptoOperationRequest request) {
        P12KeyStoreAdapter adapter = resolve(request.alias);
        return adapter.sign(request);
    }

    @Override
    public boolean verify(CryptoOperationRequest request, byte[] signature) {
        P12KeyStoreAdapter adapter = resolve(request.alias);
        return adapter.verify(request, signature);
    }

    @Override
    public CryptoOperationResult encrypt(CryptoOperationRequest request) {
        throw new UnsupportedOperationException(
                "P12CryptoProvider does not support encrypt — ECIES hybrid encryption is tracked in a dedicated feature");
    }

    @Override
    public CryptoOperationResult decrypt(CryptoOperationRequest request) {
        throw new UnsupportedOperationException(
                "P12CryptoProvider does not support decrypt — ECIES hybrid decryption is tracked in a dedicated feature");
    }

    @Override
    public List<KeyStoreDescriptor> listKeyStores() {
        return adapters.stream()
                .map(P12KeyStoreAdapter::descriptor)
                .collect(Collectors.toList());
    }

    @Override
    public KeyStoreAvailability getAvailability(KeyAlias alias) {
        if (alias.sourceType() != SourceType.P12) {
            return KeyStoreAvailability.UNAVAILABLE;
        }
        P12KeyStoreAdapter adapter = byAlias.get(alias.value());
        return adapter == null ? KeyStoreAvailability.UNAVAILABLE : adapter.descriptor().getAvailability();
    }

    @Override
    public Map<String, KeyStoreAvailability> getAvailabilities() {
        return adapters.stream().collect(Collectors.toMap(
                a -> a.descriptor().alias.value(),
                a -> a.descriptor().getAvailability()));
    }

    void registerAdapter(P12KeyStoreAdapter adapter) {
        writeLock.lock();
        try {
            String aliasValue = adapter.descriptor().alias.value();
            P12KeyStoreAdapter existing = byAlias.put(aliasValue, adapter);
            if (existing != null) {
                adapters.remove(existing);
            }
            adapters.add(adapter);
            LOG.infof("[P12Provider] registered adapter alias=%s availability=%s",
                    aliasValue, adapter.descriptor().getAvailability());
        } finally {
            writeLock.unlock();
        }
    }

    private P12KeyStoreAdapter resolve(KeyAlias alias) {
        if (alias.sourceType() != SourceType.P12) {
            throw new IllegalArgumentException(
                    "P12CryptoProvider cannot handle alias with source type " + alias.sourceType() + ": "
                            + alias.value());
        }
        P12KeyStoreAdapter adapter = byAlias.get(alias.value());
        if (adapter == null) {
            throw new IllegalArgumentException("No P12 keystore registered for alias: " + alias.value());
        }
        if (adapter.descriptor().getAvailability() != KeyStoreAvailability.AVAILABLE) {
            throw new IllegalStateException(
                    "P12 keystore for alias " + alias.value() + " is not available: "
                            + adapter.descriptor().getAvailability()
                            + " — " + adapter.descriptor().getErrorMessage());
        }
        return adapter;
    }
}
