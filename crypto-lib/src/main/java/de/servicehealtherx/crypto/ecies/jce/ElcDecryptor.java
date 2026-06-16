package de.servicehealtherx.crypto.ecies.jce;

import de.servicehealtherx.crypto.ecies.ElcCryptogram;

/**
 * Unwraps the transport key from an ELC {@code (PO, C, T)} cryptogram using a recipient private key.
 *
 * <p>Two real implementations justify this seam (constitution Principle I): {@link SoftwareElcDecryptor}
 * for exportable EC keys, and a card-backed decryptor (in the PC/SC and SICCT provider modules) that
 * issues {@code PSO:DECIPHER} APDUs. The {@link ElcCipherSpi} dispatches to the right one by key type,
 * keeping callers on the standard {@code javax.crypto.Cipher} abstraction.
 */
public interface ElcDecryptor {

    /**
     * @return the recovered transport key (the caller MUST zeroize it after use)
     * @throws java.security.GeneralSecurityException-style runtime failure on MAC/curve/padding errors
     */
    byte[] unwrapTransportKey(ElcCryptogram cryptogram);
}
