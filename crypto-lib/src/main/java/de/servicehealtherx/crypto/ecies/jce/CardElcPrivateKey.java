package de.servicehealtherx.crypto.ecies.jce;

/**
 * A handle to a private key held on a smart card (eGK/SMC-B/HBA), used as a JCE {@link java.security.PrivateKey}
 * so that {@link ElcCipherSpi} can decrypt without the caller knowing a card is involved (FR-015).
 *
 * <p>It carries no key material — {@link #getEncoded()} and {@link #getFormat()} return {@code null};
 * the actual ELC unwrap is delegated to the bound {@link ElcDecryptor}, whose card implementation
 * issues {@code PSO:DECIPHER} APDUs.
 */
public final class CardElcPrivateKey implements java.security.PrivateKey {

    private final String alias;
    private final transient ElcDecryptor decryptor;

    public CardElcPrivateKey(String alias, ElcDecryptor decryptor) {
        this.alias = alias;
        this.decryptor = decryptor;
    }

    public String alias() {
        return alias;
    }

    ElcDecryptor decryptor() {
        return decryptor;
    }

    @Override
    public String getAlgorithm() {
        return "ELC";
    }

    @Override
    public String getFormat() {
        return null;
    }

    @Override
    public byte[] getEncoded() {
        return null;
    }
}
