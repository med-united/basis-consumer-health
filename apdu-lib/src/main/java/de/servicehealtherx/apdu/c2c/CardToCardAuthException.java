package de.servicehealtherx.apdu.c2c;

/**
 * Signals that the card-to-card authentication (TUC_KON_005) could not be completed. Carries a
 * transport-neutral {@link Reason} and a short, <strong>non-PII</strong> detail (e.g. the affected
 * card handle). The VSDM layer maps the reason to the appropriate gematik error code — this package
 * stays free of any VSDM-specific dependency.
 */
public final class CardToCardAuthException extends RuntimeException {

    /** Why the C2C handshake aborted, independent of any VSDM error-code mapping. */
    public enum Reason {
        /** No reader is bound for the eGK. */
        EGK_READER_UNAVAILABLE,
        /** No reader is bound for the HBA/SMC-B. */
        HPC_READER_UNAVAILABLE,
        /** The SMC-B security state (PIN.SMC) is insufficient for C2C. */
        SMB_SECURITY_STATE_INSUFFICIENT,
        /** The HBA security state (PIN.CH) is insufficient for C2C. */
        HBA_SECURITY_STATE_INSUFFICIENT,
        /** Reading/selecting the partner card's CVC chain failed. */
        CVC_READ_FAILED
    }

    private final Reason reason;
    private final String detail;

    public CardToCardAuthException(Reason reason, String detail) {
        super("C2C authentication failed (" + reason + ")" + (detail == null ? "" : ": " + detail));
        this.reason = reason;
        this.detail = detail;
    }

    public Reason reason() {
        return reason;
    }

    public String detail() {
        return detail;
    }
}
