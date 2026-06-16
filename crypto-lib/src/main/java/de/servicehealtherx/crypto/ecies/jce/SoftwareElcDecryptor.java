package de.servicehealtherx.crypto.ecies.jce;

import de.servicehealtherx.crypto.ecies.ElcCryptogram;
import de.servicehealtherx.crypto.ecies.ElcCrypto;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * Software {@link ElcDecryptor} for exportable EC private keys (e.g. P12).
 *
 * <p>Implements {@code ELC_DEC} (gemSpec_COS N004.600): rebuild the sender's ephemeral point,
 * agree {@code KAB}, derive {@code (Kenc, Kmac)}, verify the AES-CMAC over {@code C}, then
 * AES-256-CBC-decrypt and remove ISO padding to recover the transport key.
 */
public final class SoftwareElcDecryptor implements ElcDecryptor {

    private final ECPrivateKey privateKey;

    public SoftwareElcDecryptor(ECPrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    @Override
    public byte[] unwrapTransportKey(ElcCryptogram cryptogram) {
        byte[] zab = null;
        byte[] kEnc = null;
        byte[] kMac = null;
        byte[] padded = null;
        try {
            ECPublicKey ephemeral = ElcCrypto.decodeUncompressed(cryptogram.po(), privateKey.getParams());
            zab = ElcCrypto.ecka(privateKey, ephemeral);
            byte[][] keys = ElcCrypto.deriveKeys(zab);
            kEnc = keys[0];
            kMac = keys[1];

            if (!ElcCrypto.cmacVerify(kMac, cryptogram.t(), cryptogram.c())) {
                throw new SecurityException("ELC MAC verification failed");
            }

            byte[] iv = ElcCrypto.t1(kEnc);
            padded = ElcCrypto.aesCbcDecrypt(kEnc, iv, cryptogram.c());
            return ElcCrypto.isoUnpad(padded);
        } finally {
            zeroize(zab);
            zeroize(kEnc);
            zeroize(kMac);
            zeroize(padded);
        }
    }

    private static void zeroize(byte[] secret) {
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
