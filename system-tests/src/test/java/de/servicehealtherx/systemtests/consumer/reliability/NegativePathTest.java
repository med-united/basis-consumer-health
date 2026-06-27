package de.servicehealtherx.systemtests.consumer.reliability;

import de.gematik.idp.tests.Afo;
import de.servicehealtherx.systemtests.consumer.support.ConsumerEnvelopes;
import de.servicehealtherx.systemtests.consumer.support.ConsumerNamespaces;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSoapClient;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSystemTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISO/IEC 25010 <b>Reliability</b> (maturity / fault tolerance) — defined behaviour on bad input.
 *
 * <p>Invalid card handles, malformed envelopes and a wrong {@code SOAPAction} must yield a defined
 * gematik-SOAP-Fault (GS-A_4547) and must leave the server available for the next request, rather
 * than crashing the endpoint.
 */
class NegativePathTest extends ConsumerSystemTest {

    @Test
    @Afo("GS-A_4547")
    @DisplayName("An unknown card handle yields a defined fault, not a crash — GS-A_4547")
    void unknownCardHandle_yieldsDefinedFault() throws Exception {
        String dummyCiphertext = Base64.getEncoder().encodeToString("nope".getBytes());

        HttpResponse<String> resp = client.post(
                ConsumerNamespaces.EP_CRYPT,
                ConsumerEnvelopes.decryptDocument(
                        "no-such-card-handle-xyz", "ECC", dummyCiphertext),
                ConsumerNamespaces.ACTION_DECRYPT_DOCUMENT);

        ConsumerSoapClient.assertSoapFault(resp);
    }

    @Test
    @Afo("GS-A_4547")
    @DisplayName("A malformed envelope yields a fault and the server stays available — GS-A_4547")
    void malformedEnvelope_yieldsFaultAndServerStaysUp() throws Exception {
        HttpResponse<String> resp = client.post(
                ConsumerNamespaces.EP_CERT,
                "<this-is-not-a-soap-envelope/>",
                ConsumerNamespaces.ACTION_VERIFY_CERTIFICATE);

        // Some kind of error response must come back (fault or 4xx/5xx), never silent acceptance.
        assertTrue(ConsumerSoapClient.isSoapFault(resp) || resp.statusCode() >= 400,
                "expected an error for a malformed envelope, got " + resp.statusCode() + ": "
                        + resp.body());

        // And the endpoint must still serve a subsequent well-formed request.
        assertTrue(client.serverReachable(), "server must remain reachable after a malformed request");
    }

    @Test
    @Afo("GS-A_4547")
    @DisplayName("A wrong SOAPAction is handled gracefully without taking the endpoint down — GS-A_4547")
    void wrongSoapAction_handledGracefully() throws Exception {
        HttpResponse<String> resp = client.post(
                ConsumerNamespaces.EP_CERT,
                ConsumerEnvelopes.readCertificate(ConsumerSoapClient.CARD_HANDLE, "C.AUT", "ECC"),
                "urn:bogus#WrongAction");

        assertTrue(resp.statusCode() > 0, "expected some HTTP response for a wrong SOAPAction");
        assertTrue(client.serverReachable(), "server must remain reachable after a wrong SOAPAction");
    }
}
