package de.servicehealtherx.crypto.adapter;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAdapter;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.SourceType;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import org.jboss.logging.Logger;

import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.TerminalFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class PcscKeyStoreAdapter extends KeyStoreAdapter {

    private static final Logger LOG = Logger.getLogger(PcscKeyStoreAdapter.class);

    private final KeyAlias alias;
    private final KeyStoreDescriptor descriptor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread monitorThread;

    public PcscKeyStoreAdapter(String aliasValue) {
        this.alias = new KeyAlias(aliasValue);
        this.descriptor = new KeyStoreDescriptor(this.alias, SourceType.PCSC);
    }

    @Override
    public SourceType sourceType() {
        return SourceType.PCSC;
    }

    @Override
    public KeyStoreDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException {
        try {
            TerminalFactory factory = TerminalFactory.getDefault();
            CardTerminals terminals = factory.terminals();

            running.set(true);
            monitorThread = new Thread(() -> monitorCardEvents(terminals), "pcsc-monitor-" + alias);
            monitorThread.setDaemon(true);
            monitorThread.start();

            LOG.infof("[PCSC] monitoring started for alias=%s", alias);
        } catch (Exception e) {
            descriptor.markError(e.getMessage());
            LOG.errorf("[PCSC] load failed for alias=%s: %s", alias, e.getMessage());
            throw new IOException("PCSC load failed for alias " + alias, e);
        }
    }

    private void monitorCardEvents(CardTerminals terminals) {
        while (running.get()) {
            try {
                boolean changed = terminals.waitForChange(10_000);
                if (changed) {
                    boolean hasCard = terminals.list(CardTerminals.State.CARD_PRESENT).stream()
                        .anyMatch(t -> {
                            try { return t.isCardPresent(); } catch (Exception e) { return false; }
                        });
                    if (hasCard) {
                        descriptor.markAvailable();
                        LOG.infof("[PCSC] card inserted for alias=%s", alias);
                    } else {
                        descriptor.markUnavailable();
                        LOG.infof("[PCSC] card removed for alias=%s", alias);
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    LOG.warnf("[PCSC] card monitor error for alias=%s: %s", alias, e.getMessage());
                }
            }
        }
    }

    @Override
    public CryptoOperationResult sign(CryptoOperationRequest request) {
        if (descriptor.getAvailability() != KeyStoreAvailability.AVAILABLE) {
            throw new IllegalStateException("PC/SC card not present for alias: " + alias);
        }
        // APDU sign via javax.smartcardio — card-specific implementation
        throw new UnsupportedOperationException("PC/SC APDU sign not yet implemented — requires card-specific APDU profile");
    }

    @Override
    public boolean verify(CryptoOperationRequest request, byte[] signatureBytes) {
        throw new UnsupportedOperationException("PC/SC verify delegates to public key in certificate — use CertificateService");
    }

    @Override
    public CryptoOperationResult encrypt(CryptoOperationRequest request) {
        throw new UnsupportedOperationException("Use EncryptionService for hybrid encryption");
    }

    @Override
    public CryptoOperationResult decrypt(CryptoOperationRequest request) {
        if (descriptor.getAvailability() != KeyStoreAvailability.AVAILABLE) {
            throw new IllegalStateException("PC/SC card not present for alias: " + alias);
        }
        throw new UnsupportedOperationException("PC/SC APDU decrypt not yet implemented — requires card-specific APDU profile");
    }
}
