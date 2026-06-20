package de.servicehealtherx.crypto.pcsc.jmx;

/**
 * JMX management interface for verifying a gematik card PIN on a directly connected PC/SC reader.
 * Exposed on the platform MBean server so an operator can release a card's protected objects (e.g.
 * the SMC-B card-holder PIN) interactively from JConsole / a JMX client during bring-up and support.
 *
 * <p>Two flavours of {@code verifyPin} are offered (overloaded by arity, both visible as distinct
 * JMX operations):
 * <ul>
 *   <li>{@link #verifyPin(String, String, String)} — the caller supplies the PIN, which is sent to
 *       the card as a software VERIFY. Convenient for unattended/test setups; the secret crosses
 *       this process.</li>
 *   <li>{@link #verifyPin(String, String)} — no PIN argument; the card holder enters it on the
 *       reader's secure PIN pad ({@code FEATURE_VERIFY_PIN_DIRECT}). The secret never enters this
 *       process. Fails if the reader has no PIN pad.</li>
 * </ul>
 *
 * <p>{@code pinKeyRef} accepts a gematik PIN name ({@code PIN.SMC}, {@code PIN.CH}, {@code PIN.QES})
 * or a raw reference ({@code 0x81}, {@code 81}, {@code 1}).
 */
public interface PcscPinVerifierMBean {

    /**
     * VERIFY {@code pin} against {@code pinKeyRef} on the card behind {@code cardHandle} (software
     * VERIFY). Returns a human-readable success message; throws on a wrong/blocked/transport PIN or
     * any transport error.
     */
    String verifyPin(String cardHandle, String pinKeyRef, String pin);

    /**
     * VERIFY {@code pinKeyRef} on the card behind {@code cardHandle} using the reader's secure PIN
     * pad — the card holder is prompted on the reader. Returns a success message; throws on a
     * wrong/blocked/transport PIN, an absent PIN pad, or any transport error.
     */
    String verifyPin(String cardHandle, String pinKeyRef);
}
