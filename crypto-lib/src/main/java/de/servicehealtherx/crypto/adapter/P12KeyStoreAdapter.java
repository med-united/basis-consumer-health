package de.servicehealtherx.crypto.adapter;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAdapter;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.SourceType;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import org.jboss.logging.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

public class P12KeyStoreAdapter extends KeyStoreAdapter {

    private static final Logger LOG = Logger.getLogger(P12KeyStoreAdapter.class);

    private final KeyAlias alias;
    private final String p12Path;
    private final char[] keystorePassword;
    private final char[] entryPassword;
    private final KeyStoreDescriptor descriptor;

    private volatile KeyStore keyStore;

    public P12KeyStoreAdapter(String aliasValue, String p12Path, String keystorePassword, String entryPassword) {
        this.alias = new KeyAlias(aliasValue);
        this.p12Path = p12Path;
        // Passwords handled as char[] to enable zeroing after use
        this.keystorePassword = keystorePassword != null ? keystorePassword.toCharArray() : new char[0];
        this.entryPassword = entryPassword != null ? entryPassword.toCharArray() : this.keystorePassword;
        this.descriptor = new KeyStoreDescriptor(this.alias, SourceType.P12);
    }

    @Override
    public SourceType sourceType() {
        return SourceType.P12;
    }

    @Override
    public KeyStoreDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException {
        try (FileInputStream fis = new FileInputStream(p12Path)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(fis, keystorePassword);
            this.keyStore = ks;
            descriptor.markAvailable();
            LOG.infof("[P12] loaded alias=%s path=%s", alias, p12Path);
        } catch (Exception e) {
            descriptor.markError(e.getMessage());
            // Passwords MUST NOT appear in logs
            LOG.errorf("[P12] failed to load alias=%s: %s", alias, e.getMessage());
            throw new IOException("P12 load failed for alias " + alias, e);
        }
    }

    @Override
    public CryptoOperationResult sign(CryptoOperationRequest request) {
        ensureLoaded();
        try {
            String entryAlias = extractEntryAlias();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(entryAlias, entryPassword);
            X509Certificate cert = (X509Certificate) keyStore.getCertificate(entryAlias);

            String algorithm = resolveAlgorithm(privateKey, request.algorithm);
            Signature sig = Signature.getInstance(algorithm);
            applyAlgorithmParams(sig, algorithm);
            sig.initSign(privateKey);
            sig.update(request.data);
            byte[] signature = sig.sign();

            return new CryptoOperationResult(request.alias, signature, cert, algorithm);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Sign operation failed for alias " + alias, e);
        }
    }

    @Override
    public boolean verify(CryptoOperationRequest request, byte[] signatureBytes) {
        ensureLoaded();
        try {
            String entryAlias = extractEntryAlias();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(entryAlias, entryPassword);
            X509Certificate cert = (X509Certificate) keyStore.getCertificate(entryAlias);

            String algorithm = resolveAlgorithm(privateKey, request.algorithm);
            Signature sig = Signature.getInstance(algorithm);
            applyAlgorithmParams(sig, algorithm);
            sig.initVerify(cert.getPublicKey());
            sig.update(request.data);
            return sig.verify(signatureBytes);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Verify operation failed for alias " + alias, e);
        }
    }

    private String resolveAlgorithm(PrivateKey key, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return switch (key.getAlgorithm()) {
            case "EC" -> "SHA256withECDSA";
            case "RSA" -> "SHA256withRSA";
            default -> throw new IllegalStateException(
                    "No default signature algorithm for key type '" + key.getAlgorithm() + "' on alias " + alias);
        };
    }

    private void applyAlgorithmParams(Signature sig, String algorithm) throws Exception {
        if ("SHA256withRSA".equals(algorithm)) {
            sig.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        }
    }

    @Override
    public CryptoOperationResult encrypt(CryptoOperationRequest request) {
        throw new UnsupportedOperationException(
                "P12KeyStoreAdapter does not support asymmetric encryption directly — use EncryptionService");
    }

    @Override
    public CryptoOperationResult decrypt(CryptoOperationRequest request) {
        ensureLoaded();
        try {
            String entryAlias = extractEntryAlias();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(entryAlias, entryPassword);
            var cipher = javax.crypto.Cipher.getInstance(request.algorithm);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, privateKey);
            byte[] plaintext = cipher.doFinal(request.data);
            return new CryptoOperationResult(request.alias, plaintext, null, request.algorithm);
        } catch (Exception e) {
            throw new RuntimeException("Decrypt operation failed for alias " + alias, e);
        }
    }

    private void ensureLoaded() {
        if (keyStore == null || descriptor.getAvailability() != KeyStoreAvailability.AVAILABLE) {
            throw new IllegalStateException("P12 key store not loaded for alias: " + alias);
        }
    }

    private String extractEntryAlias() throws Exception {
        var aliases = keyStore.aliases();
        if (aliases.hasMoreElements()) {
            return aliases.nextElement();
        }
        throw new IllegalStateException("P12 keystore for alias " + alias + " contains no entries");
    }
}
