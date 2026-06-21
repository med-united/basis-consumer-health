package de.servicehealtherx.crypto.signer.card;

import java.io.ByteArrayOutputStream;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.SignatureSpi;

/**
 * {@link SignatureSpi} backing the {@code "EHBA"} provider's {@code Signature.SHA256withECDSA}. It
 * accumulates the to-be-signed bytes that the CMS ({@code CadesSignature} / {@code PadesSignature})
 * or XMLDSig ({@code XadesSignature}) layer feeds in, hands them to the card via the {@link CardSigner}
 * carried by the {@link CardSigningPrivateKey}, and returns the resulting signature DER-encoded.
 *
 * <p>The card hashes the accumulated bytes with SHA-256 itself before signing, so this SPI does not
 * pre-digest — feeding the raw bytes yields a valid {@code SHA256withECDSA} signature. The card
 * emits the signature as raw {@code R||S}; {@link EcdsaDerEncoder} wraps it into the
 * {@code SEQUENCE { INTEGER r, INTEGER s }} that JCE callers expect.
 *
 * <p>Verification is not supported — this provider exists only to sign on the card.
 */
public final class EhbaCardEcdsaSignatureSpi extends SignatureSpi {

    private CardSigner cardSigner;
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    protected void engineInitSign(PrivateKey privateKey) throws InvalidKeyException {
        if (!(privateKey instanceof CardSigningPrivateKey cardKey)) {
            throw new InvalidKeyException(
                    "EHBA SHA256withECDSA requires a CardSigningPrivateKey, got "
                            + (privateKey == null ? "null" : privateKey.getClass().getName()));
        }
        this.cardSigner = cardKey.signer();
        this.buffer = new ByteArrayOutputStream();
    }

    @Override
    protected void engineUpdate(byte b) {
        buffer.write(b);
    }

    @Override
    protected void engineUpdate(byte[] b, int off, int len) {
        buffer.write(b, off, len);
    }

    @Override
    protected byte[] engineSign() throws SignatureException {
        if (cardSigner == null) {
            throw new SignatureException("EHBA signature not initialised for signing");
        }
        try {
            byte[] raw = cardSigner.sign(buffer.toByteArray());
            return EcdsaDerEncoder.rawToDer(raw);
        } catch (RuntimeException e) {
            throw new SignatureException("Card signing failed: " + e.getMessage(), e);
        } finally {
            buffer = new ByteArrayOutputStream();
        }
    }

    @Override
    protected void engineInitVerify(PublicKey publicKey) throws InvalidKeyException {
        throw new InvalidKeyException("EHBA provider does not support verification");
    }

    @Override
    protected boolean engineVerify(byte[] sigBytes) throws SignatureException {
        throw new SignatureException("EHBA provider does not support verification");
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void engineSetParameter(String param, Object value) {
        throw new UnsupportedOperationException("setParameter unsupported");
    }

    @Override
    @SuppressWarnings("deprecation")
    protected Object engineGetParameter(String param) {
        throw new UnsupportedOperationException("getParameter unsupported");
    }
}
