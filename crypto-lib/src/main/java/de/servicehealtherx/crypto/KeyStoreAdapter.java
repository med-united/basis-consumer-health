package de.servicehealtherx.crypto;

import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.cert.Certificate;
import java.util.Date;
import java.util.Enumeration;

public abstract class KeyStoreAdapter extends KeyStoreSpi {

    public abstract SourceType sourceType();

    public abstract KeyStoreDescriptor descriptor();

    public abstract CryptoOperationResult sign(CryptoOperationRequest request);

    public abstract boolean verify(CryptoOperationRequest request, byte[] signature);

    public abstract CryptoOperationResult encrypt(CryptoOperationRequest request);

    public abstract CryptoOperationResult decrypt(CryptoOperationRequest request);

    // --- KeyStoreSpi methods not required by this system ---

    @Override
    public Key engineGetKey(String alias, char[] password) {
        throw new UnsupportedOperationException("engineGetKey not supported — use sign/encrypt operations via CryptoProvider");
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
        throw new UnsupportedOperationException("engineGetCertificateChain not supported");
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
        throw new UnsupportedOperationException("engineGetCertificate not supported — use CertificateService");
    }

    @Override
    public Date engineGetCreationDate(String alias) {
        throw new UnsupportedOperationException("engineGetCreationDate not supported");
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
        throw new UnsupportedOperationException("engineSetKeyEntry not supported — key stores are read-only");
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) {
        throw new UnsupportedOperationException("engineSetKeyEntry not supported — key stores are read-only");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert) {
        throw new UnsupportedOperationException("engineSetCertificateEntry not supported");
    }

    @Override
    public void engineDeleteEntry(String alias) {
        throw new UnsupportedOperationException("engineDeleteEntry not supported — key stores are read-only");
    }

    @Override
    public Enumeration<String> engineAliases() {
        throw new UnsupportedOperationException("engineAliases not supported — use listKeyStores()");
    }

    @Override
    public boolean engineContainsAlias(String alias) {
        throw new UnsupportedOperationException("engineContainsAlias not supported");
    }

    @Override
    public int engineSize() {
        throw new UnsupportedOperationException("engineSize not supported");
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
        throw new UnsupportedOperationException("engineIsKeyEntry not supported");
    }

    @Override
    public boolean engineIsCertificateEntry(String alias) {
        throw new UnsupportedOperationException("engineIsCertificateEntry not supported");
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert) {
        throw new UnsupportedOperationException("engineGetCertificateAlias not supported");
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) throws IOException {
        throw new UnsupportedOperationException("engineStore not supported — key stores are read-only");
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException {
        // Subclasses override to perform initialization; default no-op for hot-reload
    }
}
