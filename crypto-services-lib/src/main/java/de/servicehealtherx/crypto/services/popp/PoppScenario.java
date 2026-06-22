package de.servicehealtherx.crypto.services.popp;

import java.security.cert.X509Certificate;
import java.util.List;

/**
 * A verified PoPP signed scenario: the {@code StandardScenarioMessage} carried in the JWS payload
 * (gematik api-popp 3.0.0) together with the end-entity certificate whose key signed it. Produced
 * by {@link PoppScenarioVerifier} only after the JWS signature checks out.
 *
 * @param clientSessionId  scenario session id (replay protection is scoped to it)
 * @param sequenceCounter  replay counter; the first scenario in a sequence is {@code 0} and each
 *                         subsequent one increments by exactly 1
 * @param timeSpanMillis   PoPP service timing hint; {@code 0} marks the last scenario in a sequence
 * @param steps            the command APDUs to execute, in order
 * @param signerCertificate the {@code x5c[0]} certificate that signed the JWS (for trust checking)
 */
public record PoppScenario(
        String clientSessionId,
        int sequenceCounter,
        int timeSpanMillis,
        List<ScenarioStep> steps,
        X509Certificate signerCertificate) {
}
