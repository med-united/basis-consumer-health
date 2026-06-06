package de.servicehealtherx.crypto.adapter;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAdapter;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.SourceType;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;

public class Pkcs11KeyStoreAdapter extends KeyStoreAdapter {

    private static final Logger LOG = Logger.getLogger(Pkcs11KeyStoreAdapter.class);

    private final KeyAlias alias;
    private final String providerName;
    private final String libraryPath;
    private final char[] tokenPin;
    private final int slotListIndex;
    private final KeyStoreDescriptor descriptor;

    private volatile KeyStore keyStore;

    public Pkcs11KeyStoreAdapter(String aliasValue, String providerName, String libraryPath,
                                  String tokenPin, int slotListIndex) {
        this.alias = new KeyAlias(aliasValue);
        this.providerName = providerName;
        this.libraryPath = libraryPath;
        this.tokenPin = tokenPin != null ? tokenPin.toCharArray() : new char[0];
        this.slotListIndex = slotListIndex;
        this.descriptor = new KeyStoreDescriptor(this.alias, SourceType.PKCS11);
    }

    @Override
    public SourceType sourceType() {
        return SourceType.PKCS11;
    }

    @Override
    public KeyStoreDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException {
        try {
            String config = "name=" + providerName + "\nslotListIndex=" + slotListIndex + "\nlibrary=" + libraryPath;
            Provider base = Security.getProvider("SunPKCS11");
            if (base == null) {
                throw new IllegalStateException("SunPKCS11 provider not available in this JVM");
            }
            Provider provider = base.configure(config);
            Security.addProvider(provider);

            KeyStore ks = KeyStore.getInstance("PKCS11", provider);
            ks.load(null, tokenPin);
            this.keyStore = ks;
            descriptor.markAvailable();
            LOG.infof("[PKCS11] loaded alias=%s provider=%s slot=%d", alias, providerName, slotListIndex);
        } catch (Exception e) {
            descriptor.markError(e.getMessage());
            // tokenPin MUST NOT appear in logs
            LOG.errorf("[PKCS11] failed to load alias=%s: %s", alias, e.getMessage());
            throw new IOException("PKCS11 load failed for alias " + alias, e);
        }
    }

    @Override
    public CryptoOperationResult sign(CryptoOperationRequest request) {
        ensureLoaded();
        try {
            String entryAlias = findFirstKeyAlias();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(entryAlias, tokenPin);
            X509Certificate cert = (X509Certificate) keyStore.getCertificate(entryAlias);

            Signature sig = Signature.getInstance(request.algorithm);
            sig.initSign(privateKey);
            sig.update(request.data);
            byte[] signatureBytes = sig.sign();

            return new CryptoOperationResult(request.alias, signatureBytes, cert, request.algorithm);
        } catch (Exception e) {
            if (e.getMessage() != null && (e.getMessage().contains("CKR_TOKEN_NOT_PRESENT") ||
                e.getMessage().contains("CKR_SESSION_CLOSED"))) {
                descriptor.markUnavailable();
            }
            throw new RuntimeException("PKCS11 sign failed for alias " + alias, e);
        }
    }

    @Override
    public boolean verify(CryptoOperationRequest request, byte[] signatureBytes) {
        ensureLoaded();
        try {
            String entryAlias = findFirstKeyAlias();
            X509Certificate cert = (X509Certificate) keyStore.getCertificate(entryAlias);

            Signature sig = Signature.getInstance(request.algorithm);
            sig.initVerify(cert.getPublicKey());
            sig.update(request.data);
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            throw new RuntimeException("PKCS11 verify failed for alias " + alias, e);
        }
    }

    @Override
    public CryptoOperationResult encrypt(CryptoOperationRequest request) {
        throw new UnsupportedOperationException("Use EncryptionService for hybrid encryption");
    }

    @Override
    public CryptoOperationResult decrypt(CryptoOperationRequest request) {
        ensureLoaded();
        try {
            String entryAlias = findFirstKeyAlias();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(entryAlias, tokenPin);
            var cipher = javax.crypto.Cipher.getInstance(request.algorithm);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, privateKey);
            byte[] plaintext = cipher.doFinal(request.data);
            return new CryptoOperationResult(request.alias, plaintext, null, request.algorithm);
        } catch (Exception e) {
            throw new RuntimeException("PKCS11 decrypt failed for alias " + alias, e);
        }
    }

    private void ensureLoaded() {
        if (keyStore == null || descriptor.getAvailability() != KeyStoreAvailability.AVAILABLE) {
            throw new IllegalStateException("PKCS11 key store not loaded for alias: " + alias);
        }
    }

    private String findFirstKeyAlias() throws Exception {
        var aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            if (keyStore.isKeyEntry(a)) return a;
        }
        throw new IllegalStateException("No key entries in PKCS11 token for alias " + alias);
    }
}
