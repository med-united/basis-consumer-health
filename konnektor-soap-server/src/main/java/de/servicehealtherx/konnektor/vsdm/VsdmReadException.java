package de.servicehealtherx.konnektor.vsdm;

/**
 * Signals that a local ReadVSD must abort. Carries the gematik error code
 * ({@link VsdmErrorCode}) and a short, <strong>non-PII</strong> detail (e.g. the affected container
 * or card handle) that the SOAP edge turns into a gematik fault. Never carries insured data or a
 * stack-trace-bearing message destined for the client.
 */
public final class VsdmReadException extends RuntimeException {

    private final int errorCode;
    private final String detail;

    public VsdmReadException(int errorCode, String detail) {
        super("VSDM error " + errorCode + (detail == null ? "" : ": " + detail));
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public VsdmReadException(int errorCode, String detail, Throwable cause) {
        super("VSDM error " + errorCode + (detail == null ? "" : ": " + detail), cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public int errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }
}
