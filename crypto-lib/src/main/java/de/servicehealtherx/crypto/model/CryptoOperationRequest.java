package de.servicehealtherx.crypto.model;

import de.servicehealtherx.crypto.KeyAlias;

public class CryptoOperationRequest {

    public final KeyAlias alias;
    public final String algorithm;
    public final byte[] data;
    public final String callerIdentity;

    public CryptoOperationRequest(KeyAlias alias, String algorithm, byte[] data, String callerIdentity) {
        this.alias = alias;
        this.algorithm = algorithm;
        this.data = data;
        this.callerIdentity = callerIdentity;
    }
}
