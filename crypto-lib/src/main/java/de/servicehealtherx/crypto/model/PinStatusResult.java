package de.servicehealtherx.crypto.model;

/**
 * Outcome of a card {@code GET PIN STATUS} query (gemSpec_COS §14.6.4 — e.g. PIN.SMC on an SMC-B).
 * Transport-agnostic so it survives the crossing from a {@code CryptoProvider} (which drives the
 * card) to the SOAP CardService, which maps it onto a {@code GetPinStatusResponse} / PinStatusEnum.
 *
 * <p>Unlike {@link PinVerificationResult}, reading the status does not present a secret and therefore
 * does not consume a retry.
 *
 * @param status         the password state
 * @param triesRemaining retry counter currently left on the PIN (status {@code VERIFIABLE});
 *                       {@code -1} when not applicable
 */
public record PinStatusResult(Status status, int triesRemaining) {

    public enum Status {
        /** Secret already verified in the current security context (SW 9000). */
        VERIFIED,
        /** Set and verifiable; {@link #triesRemaining()} attempts left (SW 63Cx). */
        VERIFIABLE,
        /** Still a transport PIN and must be changed first (SW 6984). */
        TRANSPORT_PIN,
        /** No secret set yet (leerPIN). */
        EMPTY_PIN,
        /** Retry counter exhausted, the PIN is blocked (SW 6983). */
        BLOCKED,
        /** The verification requirement is switched off. */
        DISABLED,
        /** Any other status word the card returned. */
        ERROR
    }

    public static PinStatusResult of(Status status) {
        return new PinStatusResult(status, -1);
    }

    public static PinStatusResult verifiable(int triesRemaining) {
        return new PinStatusResult(Status.VERIFIABLE, triesRemaining);
    }
}
