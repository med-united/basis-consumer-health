package de.servicehealtherx.crypto.signer.card;

import java.security.PrivateKey;

/**
 * A JCE {@link PrivateKey} handle to the ECDSA signing key held on a smart card (eHBA / SMC-B),
 * mirroring {@code CardElcPrivateKey} for the ELC decryption path. It carries no key material
 * ({@link #getEncoded()} / {@link #getFormat()} return {@code null}); the actual signature is
 * delegated to the bound {@link CardSigner}, which drives the card.
 *
 * <p>Used so that {@code CadesSignature}, {@code PadesSignature} and {@code XadesSignature} — which
 * are written against a generic {@link PrivateKey} and the {@code "EHBA"} JCE provider — sign on the
 * card without knowing a card is involved. {@link EhbaCardEcdsaSignatureSpi} casts the key it is
 * initialised with back to this type to reach the {@link CardSigner}.
 */
public final class CardSigningPrivateKey implements PrivateKey {

    private final transient CardSigner signer;

    public CardSigningPrivateKey(CardSigner signer) {
        if (signer == null) {
            throw new IllegalArgumentException("signer must not be null");
        }
        this.signer = signer;
    }

    CardSigner signer() {
        return signer;
    }

    @Override
    public String getAlgorithm() {
        // ECDSA on an EC (brainpoolP256r1) card key; the actual signature is produced by the card.
        return "EC";
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
