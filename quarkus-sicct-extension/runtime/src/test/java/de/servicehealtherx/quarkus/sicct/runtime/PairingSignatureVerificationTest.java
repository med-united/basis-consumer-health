package de.servicehealtherx.quarkus.sicct.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the EHEALTH TERMINAL AUTHENTICATE CREATE signature scheme used by
 * {@code SicctChannelHandler.validateSignature} matches what the terminal produces:
 * the terminal signs {@code SHA-256(sharedSecret)} once (NoneWithECDSA) and returns
 * {@code r||s} in TR-03111 plain format, so verification must use the PLAIN-ECDSA
 * variant fed the raw shared secret (single internal hash, plain encoding).
 */
public class PairingSignatureVerificationTest {

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /** Reproduces the reference terminal: sign SHA-256(sharedSecret) and emit plain r||s. */
    private static byte[] signLikeTerminal(KeyPair keyPair, byte[] sharedSecret) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret);
        Signature signer = Signature.getInstance("NoneWithECDSA", "BC");
        signer.initSign(keyPair.getPrivate());
        signer.update(hash);
        byte[] der = signer.sign();

        // DER SEQUENCE { INTEGER r, INTEGER s } -> fixed-width r||s (32 bytes each for P-256).
        ASN1Sequence seq = ASN1Sequence.getInstance(der);
        byte[] r = toFixed(((ASN1Integer) seq.getObjectAt(0)).getPositiveValue(), 32);
        byte[] s = toFixed(((ASN1Integer) seq.getObjectAt(1)).getPositiveValue(), 32);
        byte[] plain = new byte[64];
        System.arraycopy(r, 0, plain, 0, 32);
        System.arraycopy(s, 0, plain, 32, 32);
        return plain;
    }

    private static byte[] toFixed(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        if (b.length == len) {
            return b;
        }
        byte[] out = new byte[len];
        if (b.length > len) { // drop a leading 0x00 sign byte
            System.arraycopy(b, b.length - len, out, 0, len);
        } else {
            System.arraycopy(b, 0, out, len - b.length, b.length);
        }
        return out;
    }

    /** Mirrors the production verification path (SHA256withPLAIN-ECDSA over the raw secret). */
    private static boolean verifyLikeKonnektor(KeyPair keyPair, byte[] sharedSecret, byte[] plainSignature)
            throws Exception {
        Signature verifier = Signature.getInstance("SHA256withPLAIN-ECDSA", "BC");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(sharedSecret);
        return verifier.verify(plainSignature);
    }

    private static KeyPair brainpoolKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(new ECGenParameterSpec("brainpoolP256r1"));
        return kpg.generateKeyPair();
    }

    @Test
    public void plainEcdsaSignatureOverSharedSecretHashVerifies() throws Exception {
        KeyPair keyPair = brainpoolKeyPair();
        byte[] sharedSecret = new byte[16];
        for (int i = 0; i < sharedSecret.length; i++) {
            sharedSecret[i] = (byte) (i * 7 + 1);
        }

        byte[] plainSignature = signLikeTerminal(keyPair, sharedSecret);

        assertTrue(verifyLikeKonnektor(keyPair, sharedSecret, plainSignature),
                "plain-format ECDSA signature over SHA-256(sharedSecret) must verify");
    }

    @Test
    public void tamperedSharedSecretFailsVerification() throws Exception {
        KeyPair keyPair = brainpoolKeyPair();
        byte[] sharedSecret = new byte[16];
        Arrays.fill(sharedSecret, (byte) 0x5A);

        byte[] plainSignature = signLikeTerminal(keyPair, sharedSecret);

        byte[] tampered = sharedSecret.clone();
        tampered[0] ^= 0xFF;
        assertFalse(verifyLikeKonnektor(keyPair, tampered, plainSignature),
                "a signature must not verify against a different shared secret");
    }
}
