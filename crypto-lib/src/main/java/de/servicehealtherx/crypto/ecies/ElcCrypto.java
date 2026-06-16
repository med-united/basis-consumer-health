package de.servicehealtherx.crypto.ecies;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.CMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

/**
 * Low-level building blocks of the gemSpec_COS ELC scheme (N004.500 {@code ELC_ENC} /
 * N004.600 {@code ELC_DEC}, N001.520 {@code KeyDerivation_AES256}, BSI TR-03111).
 *
 * <p>This is an internal helper of the {@code ecies} package; it is not a public API surface.
 * All methods are stateless and thread-safe. Sensitive intermediate material is the caller's
 * responsibility to zeroize.
 */
public final class ElcCrypto {

    public static final int CMAC_TAG_LENGTH = 8; // COS secure-messaging MAC length

    private ElcCrypto() {
    }

    /**
     * {@code KAB = ECKAvalue(d, P, dP)} — the shared-secret x-coordinate as a fixed-length octet
     * string (BSI TR-03111 §4.3.1). Bouncy Castle's plain "ECDH" returns exactly {@code I2OS(x, L)}.
     */
    public static byte[] ecka(PrivateKey ownPrivate, PublicKey peerPublic) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
            ka.init(ownPrivate);
            ka.doPhase(peerPublic, true);
            return ka.generateSecret();
        } catch (Exception e) {
            throw new IllegalStateException("ECKA agreement failed", e);
        }
    }

    /** {@code KeyDerivation_AES256(KD)} → {Kenc, Kmac} (T2 is always 0 and folded into {@link #t1}). */
    public static byte[][] deriveKeys(byte[] kd) {
        byte[] kEnc = sha256(concat(kd, counter(1)));
        byte[] kMac = sha256(concat(kd, counter(2)));
        return new byte[][]{kEnc, kMac};
    }

    /** {@code T1 = AES_ENC(Kenc, I2OS(T2=0, 16))} — the CBC IV (16 zero bytes encrypted under Kenc). */
    public static byte[] t1(byte[] kEnc) {
        try {
            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kEnc, "AES"));
            return aes.doFinal(new byte[16]);
        } catch (Exception e) {
            throw new IllegalStateException("T1 derivation failed", e);
        }
    }

    public static byte[] aesCbcEncrypt(byte[] kEnc, byte[] iv, byte[] padded) {
        return aesCbc(Cipher.ENCRYPT_MODE, kEnc, iv, padded);
    }

    public static byte[] aesCbcDecrypt(byte[] kEnc, byte[] iv, byte[] ciphertext) {
        return aesCbc(Cipher.DECRYPT_MODE, kEnc, iv, ciphertext);
    }

    private static byte[] aesCbc(int mode, byte[] kEnc, byte[] iv, byte[] data) {
        try {
            Cipher aes = Cipher.getInstance("AES/CBC/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
            aes.init(mode, new SecretKeySpec(kEnc, "AES"), new IvParameterSpec(iv));
            return aes.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-CBC operation failed", e);
        }
    }

    /** {@code CalculateCMAC_IsoPadding(K, M)} = AES-CMAC over {@code PaddingIso(M)}, truncated to 8 bytes. */
    public static byte[] cmac(byte[] kMac, byte[] message) {
        CMac mac = new CMac(AESEngine.newInstance());
        mac.init(new KeyParameter(kMac));
        byte[] padded = isoPad(message);
        mac.update(padded, 0, padded.length);
        byte[] full = new byte[mac.getMacSize()];
        mac.doFinal(full, 0);
        return Arrays.copyOf(full, CMAC_TAG_LENGTH);
    }

    public static boolean cmacVerify(byte[] kMac, byte[] expectedTag, byte[] message) {
        return MessageDigest.isEqual(expectedTag, cmac(kMac, message));
    }

    /** ISO/IEC 7816-4 padding to a 16-byte boundary (always adds at least the 0x80 byte). */
    public static byte[] isoPad(byte[] m) {
        int padLen = 16 - (m.length % 16);
        byte[] out = Arrays.copyOf(m, m.length + padLen);
        out[m.length] = (byte) 0x80;
        return out;
    }

    /** {@code TruncateIso} — remove ISO/IEC 7816-4 padding. */
    public static byte[] isoUnpad(byte[] data) {
        int i = data.length - 1;
        while (i >= 0 && data[i] == 0x00) {
            i--;
        }
        if (i < 0 || (data[i] & 0xFF) != 0x80) {
            throw new IllegalArgumentException("Invalid ISO 7816-4 padding");
        }
        return Arrays.copyOf(data, i);
    }

    /** {@code P2OS} — uncompressed point encoding {@code 04 || X || Y} (TR-03111 §3.2.1). */
    public static byte[] encodeUncompressed(ECPublicKey publicKey) {
        int fieldSize = (publicKey.getParams().getCurve().getField().getFieldSize() + 7) / 8;
        byte[] x = i2os(publicKey.getW().getAffineX(), fieldSize);
        byte[] y = i2os(publicKey.getW().getAffineY(), fieldSize);
        byte[] out = new byte[1 + 2 * fieldSize];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, fieldSize);
        System.arraycopy(y, 0, out, 1 + fieldSize, fieldSize);
        return out;
    }

    /** {@code OS2P} — rebuild the sender's ephemeral public key on the recipient's curve. */
    public static ECPublicKey decodeUncompressed(byte[] po, ECParameterSpec params) {
        int fieldSize = (params.getCurve().getField().getFieldSize() + 7) / 8;
        if (po.length != 1 + 2 * fieldSize || (po[0] & 0xFF) != 0x04) {
            throw new IllegalArgumentException("Ephemeral point PO is not in uncompressed encoding");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(po, 1, 1 + fieldSize));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(po, 1 + fieldSize, po.length));
        try {
            KeyFactory kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
        } catch (Exception e) {
            throw new IllegalArgumentException("Ephemeral point PO is not a valid curve point", e);
        }
    }

    private static byte[] i2os(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[length];
        if (raw.length == length) {
            return raw;
        }
        if (raw.length == length + 1 && raw[0] == 0) { // strip sign byte
            System.arraycopy(raw, 1, out, 0, length);
            return out;
        }
        if (raw.length < length) { // left-pad
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
            return out;
        }
        throw new IllegalArgumentException("Integer too large for " + length + " octets");
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] counter(int value) {
        return new byte[]{0, 0, 0, (byte) value};
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
