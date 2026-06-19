package de.servicehealtherx.apdu.card;

/**
 * Thrown by {@link ApduExecutor} when a transmitted APDU returns a status word not accepted by the
 * step's {@code ExpectedStatusSet}. Carries the failing step's semantic label and the status word so
 * the orchestrator can map it to the correct VSDM fault code (e.g. 3011 read failure). Carries no
 * card data / PII.
 */
public final class ApduExecutionException extends RuntimeException {

    private final int statusWord;

    public ApduExecutionException(String stepLabel, int statusWord) {
        super("APDU step failed: " + stepLabel + " → SW=" + String.format("%04X", statusWord & 0xFFFF));
        this.statusWord = statusWord;
    }

    public int statusWord() {
        return statusWord;
    }
}
