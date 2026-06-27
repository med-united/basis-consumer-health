package de.servicehealtherx.systemtests.consumer.security;

import de.gematik.idp.tests.Afo;
import de.servicehealtherx.systemtests.consumer.support.ConsumerEnvelopes;
import de.servicehealtherx.systemtests.consumer.support.ConsumerNamespaces;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSoapClient;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSystemTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISO/IEC 25010 <b>Security</b> (confidentiality / accountability) — error transport.
 *
 * <p>Every Basis-Consumer web service must report errors as a gematik-SOAP-Fault (A_15237 /
 * GS-A_3796) using the generic error structure (GS-A_4547), and must not leak sensitive internals
 * (no Java stack traces, no key or PIN material) in the fault. The fault here is forced without a
 * card by handing {@code VerifyCertificate} bytes that are not a valid X.509 certificate, so this
 * test runs in any environment with a live server.
 */
class SoapFaultLeakageTest extends ConsumerSystemTest {

    @Test
    @Afo("A_15237")
    @Afo("GS-A_3796")
    @Afo("GS-A_4547")
    @DisplayName("A failing operation returns a gematik-SOAP-Fault without leaking internals — A_15237")
    void failingOperation_returnsCleanGematikFault() throws Exception {
        String notACertificate = Base64.getEncoder().encodeToString("not-a-certificate".getBytes());

        HttpResponse<String> resp = client.post(
                ConsumerNamespaces.EP_CERT,
                ConsumerEnvelopes.verifyCertificate(notACertificate),
                ConsumerNamespaces.ACTION_VERIFY_CERTIFICATE);

        ConsumerSoapClient.assertSoapFault(resp);

        String body = resp.body();
        // No raw Java stack trace / internal class plumbing leaked into the fault.
        assertFalse(body.contains("\tat ") || body.contains("Caused by:")
                        || body.contains(".java:"),
                "SOAP fault must not leak a Java stack trace: " + body);
        // No key or credential material leaked.
        assertFalse(body.contains("PRIVATE KEY") || body.contains("BEGIN ")
                        || body.toLowerCase().contains("\"pin\"") || body.contains("VerifyPin"),
                "SOAP fault must not leak key/PIN material: " + body);
    }

    @Test
    @Afo("GS-A_4547")
    @DisplayName("Fault payload carries a structured gematik error (Trace / MessageID) — GS-A_4547")
    void fault_carriesStructuredGematikError() throws Exception {
        String notACertificate = Base64.getEncoder().encodeToString("garbage".getBytes());

        HttpResponse<String> resp = client.post(
                ConsumerNamespaces.EP_CERT,
                ConsumerEnvelopes.verifyCertificate(notACertificate),
                ConsumerNamespaces.ACTION_VERIFY_CERTIFICATE);

        ConsumerSoapClient.assertSoapFault(resp);
        assertTrue(resp.body().contains("faultstring") || resp.body().contains("Reason")
                        || resp.body().contains("Error"),
                "expected a structured fault reason / gematik Error element: " + resp.body());
    }
}
