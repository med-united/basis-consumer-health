package de.servicehealtherx.crypto.model;

/**
 * Outcome of a card {@code VERIFY} of a PIN (gemSpec_COS §14.6.2 — e.g. PIN.SMC on an SMC-B).
 * Transport-agnostic so it survives the crossing from a {@code CryptoProvider} (which drives the
 * card) to the SOAP CardService, which maps it onto a {@code PinResponseType}.
 *
 * @param status         the verification result
 * @param triesRemaining retry counter left on the PIN after a wrong attempt (status {@code WRONG});
 *                       {@code -1} when not applicable (verified, blocked, transport PIN, error)
 */
public record PinVerificationResult(Status status, int triesRemaining) {

    public enum Status {
        /** PIN verified, the protected objects are now usable (SW 9000). */
        VERIFIED,
        /** Wrong PIN; {@link #triesRemaining()} attempts left before it blocks (SW 63Cx). */
        WRONG,
        /** PIN is blocked — the retry counter is exhausted (SW 6983). */
        BLOCKED,
        /** PIN is still a transport PIN and must be changed first (SW 6984). */
        TRANSPORT_PIN,
        /** Any other status word the card returned. */
        ERROR
    }

    public static PinVerificationResult verified() {
        return new PinVerificationResult(Status.VERIFIED, -1);
    }

    public static PinVerificationResult wrong(int triesRemaining) {
        return new PinVerificationResult(Status.WRONG, triesRemaining);
    }
}
