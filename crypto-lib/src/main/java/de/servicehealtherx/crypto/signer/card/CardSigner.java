package de.servicehealtherx.crypto.signer.card;

/**
 * Bridges a JCE signing operation onto an inserted smart card (eHBA / SMC-B). The implementation
 * issues the card APDUs ({@code MSE:SET} + {@code PSO:Compute Digital Signature}) that compute an
 * ECDSA signature over {@code toBeSigned} with the card's qualified ({@code C.QES}) or, where no
 * qualified key exists, authentication ({@code C.AUT}) key.
 *
 * <p>The card hashes {@code toBeSigned} with SHA-256 itself, so the bytes passed here are the raw
 * to-be-signed octets (e.g. the DER-encoded CMS signed attributes), <em>not</em> a pre-computed
 * digest. The returned value is the <strong>raw</strong> ECDSA signature {@code R||S} (64 bytes for
 * brainpoolP256r1) exactly as the card emits it; DER framing is applied by
 * {@link EhbaCardEcdsaSignatureSpi}.
 */
@FunctionalInterface
public interface CardSigner {

    /** Sign {@code toBeSigned} with the card key; returns the raw ECDSA {@code R||S} signature. */
    byte[] sign(byte[] toBeSigned);
}
