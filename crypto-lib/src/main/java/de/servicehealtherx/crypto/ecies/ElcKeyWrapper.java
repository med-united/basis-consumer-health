package de.servicehealtherx.crypto.ecies;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * Sender side of gemSpec_COS ELC: wraps a transport key for one elliptic-curve recipient,
 * producing the card-compatible {@link ElcCryptogram} {@code (PO, C, T)}.
 *
 * <p>Implements {@code ELC_ENC} (gemSpec_COS N004.500): generate an ephemeral key pair on the
 * recipient's curve, agree {@code KAB}, derive {@code (Kenc, Kmac)}, AES-256-CBC the transport
 * key (IV {@code T1}), and AES-CMAC the ciphertext.
 */
public final class ElcKeyWrapper {

    /**
     * @param transportKey the 256-bit AES content key to wrap
     * @param recipient    the recipient's EC public key (curve taken from this key)
     * @param curveOid     the recipient curve OID recorded in the cryptogram
     */
    public ElcCryptogram wrap(byte[] transportKey, ECPublicKey recipient, ASN1ObjectIdentifier curveOid) {
        byte[] zab = null;
        byte[] kEnc = null;
        byte[] kMac = null;
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            kpg.initialize(recipient.getParams());
            KeyPair ephemeral = kpg.generateKeyPair();

            zab = ElcCrypto.ecka(ephemeral.getPrivate(), recipient);
            byte[][] keys = ElcCrypto.deriveKeys(zab);
            kEnc = keys[0];
            kMac = keys[1];

            byte[] iv = ElcCrypto.t1(kEnc);
            byte[] c = ElcCrypto.aesCbcEncrypt(kEnc, iv, ElcCrypto.isoPad(transportKey));
            byte[] t = ElcCrypto.cmac(kMac, c);
            byte[] po = ElcCrypto.encodeUncompressed((ECPublicKey) ephemeral.getPublic());

            return new ElcCryptogram(curveOid, po, c, t);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ELC key wrap failed", e);
        } finally {
            zeroize(zab);
            zeroize(kEnc);
            zeroize(kMac);
        }
    }

    private static void zeroize(byte[] secret) {
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
