package de.servicehealtherx.crypto.adapter;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAdapter;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.SourceType;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stub adapter for SICCT card terminals.
 * Remains UNAVAILABLE until quarkus-sicct-extension registers a live terminal via CDI event.
 * Full implementation in US8 (Phase 9).
 */
public class SicctKeyStoreAdapter extends KeyStoreAdapter {

    private static final Logger LOG = Logger.getLogger(SicctKeyStoreAdapter.class);

    private final KeyAlias alias;
    private final KeyStoreDescriptor descriptor;

    public SicctKeyStoreAdapter(String aliasValue) {
        this.alias = new KeyAlias(aliasValue);
        this.descriptor = new KeyStoreDescriptor(this.alias, SourceType.SICCT);
    }

    @Override
    public SourceType sourceType() {
        return SourceType.SICCT;
    }

    @Override
    public KeyStoreDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException {
        // No-op — SICCT availability is set externally by quarkus-sicct-extension when a terminal connects
        LOG.infof("[SICCT] stub adapter registered for alias=%s; waiting for terminal connection", alias);
    }

    public void onTerminalConnected() {
        descriptor.markAvailable();
        LOG.infof("[SICCT] terminal connected, alias=%s AVAILABLE", alias);
    }

    public void onTerminalDisconnected() {
        descriptor.markUnavailable();
        LOG.infof("[SICCT] terminal disconnected, alias=%s UNAVAILABLE", alias);
    }

    @Override
    public CryptoOperationResult sign(CryptoOperationRequest request) {
        ensureAvailable();
        // Delegates to SicctTerminalManager via CDI event in full implementation (US8)
        throw new UnsupportedOperationException("SICCT sign: full implementation pending (US8)");
    }

    @Override
    public boolean verify(CryptoOperationRequest request, byte[] signatureBytes) {
        throw new UnsupportedOperationException("SICCT verify: full implementation pending (US8)");
    }

    @Override
    public CryptoOperationResult encrypt(CryptoOperationRequest request) {
        throw new UnsupportedOperationException("Use EncryptionService for hybrid encryption");
    }

    @Override
    public CryptoOperationResult decrypt(CryptoOperationRequest request) {
        ensureAvailable();
        throw new UnsupportedOperationException("SICCT decrypt: full implementation pending (US8)");
    }

    private void ensureAvailable() {
        if (descriptor.getAvailability() != de.servicehealtherx.crypto.KeyStoreAvailability.AVAILABLE) {
            throw new IllegalStateException("SICCT terminal not connected for alias: " + alias);
        }
    }
}
