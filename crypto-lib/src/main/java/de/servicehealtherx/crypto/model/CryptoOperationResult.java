package de.servicehealtherx.crypto.model;

import de.servicehealtherx.crypto.KeyAlias;

import java.security.cert.X509Certificate;

public class CryptoOperationResult {

    public final KeyAlias alias;
    public final byte[] result;
    public final X509Certificate certificate;
    public final String algorithm;

    public CryptoOperationResult(KeyAlias alias, byte[] result, X509Certificate certificate, String algorithm) {
        this.alias = alias;
        this.result = result;
        this.certificate = certificate;
        this.algorithm = algorithm;
    }
}
