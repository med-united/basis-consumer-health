package de.servicehealtherx.crypto.ecies.jce;

import de.servicehealtherx.crypto.ecies.ElcCryptogram;

import javax.crypto.CipherSpi;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

/**
 * JCE {@link CipherSpi} for the {@code "ELC"} transformation: decrypts an ELC {@code (PO, C, T)}
 * cryptogram to recover the transport key. Decryption only.
 *
 * <p>Dispatch is by key type (FR-015): an {@link ECPrivateKey} runs the software path
 * ({@link SoftwareElcDecryptor}); a {@link CardElcPrivateKey} delegates to its card-backed
 * {@link ElcDecryptor} (which issues APDUs). Callers use only {@link javax.crypto.Cipher} and
 * remain unaware of the key's location.
 */
public final class ElcCipherSpi extends CipherSpi {

    private ElcDecryptor decryptor;

    @Override
    protected void engineInit(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
        if (opmode != javax.crypto.Cipher.DECRYPT_MODE && opmode != javax.crypto.Cipher.UNWRAP_MODE) {
            throw new UnsupportedOperationException(
                    "ELC Cipher supports DECRYPT only; use ElcKeyWrapper for encryption");
        }
        if (key instanceof CardElcPrivateKey cardKey) {
            this.decryptor = cardKey.decryptor();
        } else if (key instanceof ECPrivateKey ecKey) {
            this.decryptor = new SoftwareElcDecryptor(ecKey);
        } else {
            throw new InvalidKeyException(
                    "ELC Cipher requires an ECPrivateKey or CardElcPrivateKey, got "
                            + (key == null ? "null" : key.getClass().getName()));
        }
    }

    @Override
    protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        if (params != null) {
            throw new InvalidAlgorithmParameterException("ELC Cipher takes no parameters");
        }
        engineInit(opmode, key, random);
    }

    @Override
    protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        if (params != null) {
            throw new InvalidAlgorithmParameterException("ELC Cipher takes no parameters");
        }
        engineInit(opmode, key, random);
    }

    @Override
    protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen) {
        if (decryptor == null) {
            throw new IllegalStateException("ELC Cipher not initialized");
        }
        byte[] der = Arrays.copyOfRange(input, inputOffset, inputOffset + inputLen);
        return decryptor.unwrapTransportKey(ElcCryptogram.parse(der));
    }

    @Override
    protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) {
        byte[] result = engineDoFinal(input, inputOffset, inputLen);
        System.arraycopy(result, 0, output, outputOffset, result.length);
        return result.length;
    }

    // --- single-shot only: streaming and encryption-oriented operations are unsupported ---

    @Override
    protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
        throw new UnsupportedOperationException("ELC Cipher is single-shot; use doFinal(cryptogram)");
    }

    @Override
    protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) {
        throw new UnsupportedOperationException("ELC Cipher is single-shot; use doFinal(cryptogram)");
    }

    @Override
    protected void engineSetMode(String mode) {
        throw new UnsupportedOperationException("ELC Cipher has a fixed mode");
    }

    @Override
    protected void engineSetPadding(String padding) {
        throw new UnsupportedOperationException("ELC Cipher has fixed padding");
    }

    @Override
    protected int engineGetBlockSize() {
        return 0;
    }

    @Override
    protected int engineGetOutputSize(int inputLen) {
        return 64; // upper bound for a wrapped transport key
    }

    @Override
    protected byte[] engineGetIV() {
        return null;
    }

    @Override
    protected AlgorithmParameters engineGetParameters() {
        return null;
    }
}
