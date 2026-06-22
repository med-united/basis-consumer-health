package de.servicehealtherx.crypto.services.popp;

import java.util.List;

/**
 * A single step of a PoPP {@code StandardScenarioMessage} (gematik api-popp 3.0.0,
 * {@code ScenarioStep}): one ISO/IEC 7816-4 command APDU to send to the eGK plus the status words
 * the PoPP service expects in the response.
 *
 * @param commandApdu         the raw command APDU bytes (decoded from the scenario's hex string)
 * @param expectedStatusWords expected response status words as lower-case 4-hex-digit strings
 *                            (e.g. {@code "9000"}); empty means "accept any"
 */
public record ScenarioStep(byte[] commandApdu, List<String> expectedStatusWords) {
}
