package de.servicehealtherx.systemtests.consumer.functional;

import de.gematik.idp.tests.Afo;
import de.servicehealtherx.systemtests.consumer.support.ConsumerEnvelopes;
import de.servicehealtherx.systemtests.consumer.support.ConsumerNamespaces;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSoapClient;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSystemTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * ISO/IEC 25010 <b>Functional Suitability</b> — Basisdienst Zertifikatsdienst (CertificateService).
 *
 * <p>Black-box test of the two CertificateService operations against a running Basis-Consumer.
 * The card-bound {@code ReadCertificate} skips itself when the environment has no provisioned
 * card/key (the consumer answers with a gematik-SOAP-Fault); the certificate it reads feeds
 * {@code VerifyCertificate}.
 */
@TestMethodOrder(OrderAnnotation.class)
class CertificateServiceTest extends ConsumerSystemTest {

    private static String readCertificateBase64;

    @Test
    @Order(1)
    @Afo("A_17408")
    @Afo("A_24782-02")
    @DisplayName("ReadCertificate (C.AUT) returns an X.509 certificate — A_24782-02 / A_17408")
    void readCertificate_returnsX509Certificate() throws Exception {
        HttpResponse<String> resp = client.post(
                ConsumerNamespaces.EP_CERT,
                ConsumerEnvelopes.readCertificate(ConsumerSoapClient.CARD_HANDLE, "C.AUT", "ECC"),
                ConsumerNamespaces.ACTION_READ_CERTIFICATE);

        assumeFalse(ConsumerSoapClient.isSoapFault(resp),
                "ReadCertificate needs a provisioned card/key (handle '" + ConsumerSoapClient.CARD_HANDLE
                        + "'); skipping. Set -Dsystemtest.card.handle=... — fault was: " + resp.body());

        ConsumerSoapClient.assertOk(resp, "ReadCertificateResponse");
        readCertificateBase64 = ConsumerSoapClient.firstMatch(resp.body(), "X509Certificate");
        assertNotNull(readCertificateBase64,
                "expected an X509Certificate in the ReadCertificate response: " + resp.body());
    }

    @Test
    @Order(2)
    @Afo("A_17429-02")
    @DisplayName("VerifyCertificate reports a verification result for a real certificate — A_17429-02")
    void verifyCertificate_reportsResult() throws Exception {
        assumeFalse(readCertificateBase64 == null,
                "no certificate available from ReadCertificate; skipping VerifyCertificate");

        HttpResponse<String> resp = client.post(
                ConsumerNamespaces.EP_CERT,
                ConsumerEnvelopes.verifyCertificate(readCertificateBase64),
                ConsumerNamespaces.ACTION_VERIFY_CERTIFICATE);

        ConsumerSoapClient.assertOk(resp, "VerifyCertificateResponse");
        String result = ConsumerSoapClient.firstMatch(resp.body(), "VerificationResult");
        assertNotNull(result, "expected a VerificationResult: " + resp.body());
        assertTrue(result.matches("VALID|INVALID|INCONCLUSIVE"),
                "VerificationResult must be one of VALID/INVALID/INCONCLUSIVE, was: " + result);
    }
}
