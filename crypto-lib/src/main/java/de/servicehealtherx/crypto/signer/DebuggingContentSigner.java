package de.servicehealtherx.crypto.signer;

import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.operator.ContentSigner;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Thin decorator around a {@link ContentSigner} that logs the produced signature bytes
 * at DEBUG level. Useful for diagnosing card-signing failures without touching the
 * real signing path.
 */
public class DebuggingContentSigner implements ContentSigner {

    private static final Logger LOG = Logger.getLogger(DebuggingContentSigner.class);

    private final ContentSigner delegate;

    public DebuggingContentSigner(ContentSigner delegate) {
        this.delegate = delegate;
    }

    @Override
    public AlgorithmIdentifier getAlgorithmIdentifier() {
        return delegate.getAlgorithmIdentifier();
    }

    @Override
    public OutputStream getOutputStream() {
        return delegate.getOutputStream();
    }

    @Override
    public byte[] getSignature() {
        byte[] sig = delegate.getSignature();
        LOG.debugf("ContentSigner produced %d signature bytes (algo=%s)",
            sig.length, delegate.getAlgorithmIdentifier().getAlgorithm());
        return sig;
    }
}
