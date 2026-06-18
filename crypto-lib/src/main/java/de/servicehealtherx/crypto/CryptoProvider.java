package de.servicehealtherx.crypto;

import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

public interface CryptoProvider {

    CryptoOperationResult sign(CryptoOperationRequest request);

    boolean verify(CryptoOperationRequest request, byte[] signature);

    CryptoOperationResult encrypt(CryptoOperationRequest request);

    CryptoOperationResult decrypt(CryptoOperationRequest request);

    /**
     * Reads the X.509 certificate referenced by {@code certRef} ({@code C.AUT} / {@code C.SIG} …)
     * for the given crypto algorithm ({@code RSA} / {@code ECC}) from the key source identified by
     * {@code alias} (gemSpec_Kon ReadCardCertificate, TUC_KON_216 "LeseZertifikat").
     *
     * <p>The default implementation rejects the call; providers backed by a real key source override it.
     */
    default X509Certificate readCertificate(KeyAlias alias, String certRef, String crypt) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support readCertificate for alias " + alias.value());
    }

    List<KeyStoreDescriptor> listKeyStores();

    KeyStoreAvailability getAvailability(KeyAlias alias);

    Map<String, KeyStoreAvailability> getAvailabilities();
}
