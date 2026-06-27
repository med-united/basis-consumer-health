package de.servicehealtherx.systemtests.consumer.interoperability;

import de.gematik.idp.tests.Afo;
import de.servicehealtherx.systemtests.consumer.support.ConsumerNamespaces;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSoapClient;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSystemTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISO/IEC 25010 <b>Compatibility / Interoperability</b> — each Basis-Consumer service must publish
 * its WSDL on the wire, declaring the gematik service namespace a primary system binds against.
 *
 * <p>This is the on-the-wire contract check that does not require a card: it confirms the three
 * basisdienste (Certificate, Encryption, Signature) expose the WSDL at {@code ?wsdl} with the
 * correct target/type namespaces.
 */
class WsdlConformanceTest extends ConsumerSystemTest {

    @Test
    @Afo("A_17408")
    @DisplayName("CertificateService publishes its WSDL with the v3 namespace — A_17408")
    void certificateService_publishesWsdl() throws Exception {
        assertWsdlPublishes(ConsumerNamespaces.EP_CERT,
                "http://ws.gematik.de/consumer/CertificateService/WSDL/v3.0");
    }

    @Test
    @Afo("A_17477")
    @DisplayName("EncryptionService publishes its WSDL with the v3 namespace — A_17477")
    void encryptionService_publishesWsdl() throws Exception {
        assertWsdlPublishes(ConsumerNamespaces.EP_CRYPT,
                "http://ws.gematik.de/consumer/EncryptionService/WSDL/v3.0");
    }

    @Test
    @Afo("A_17523")
    @DisplayName("SignatureService publishes its WSDL with the v3.2 namespace — A_17523")
    void signatureService_publishesWsdl() throws Exception {
        assertWsdlPublishes(ConsumerNamespaces.EP_SIG,
                "http://ws.gematik.de/consumer/SignatureService/WSDL/v3.2");
    }

    private void assertWsdlPublishes(String endpoint, String expectedTargetNamespace)
            throws Exception {
        HttpResponse<String> resp = client.get(endpoint + "?wsdl");
        assertEquals(200, resp.statusCode(),
                "expected the WSDL to be served at " + endpoint + "?wsdl: " + resp.statusCode());
        assertTrue(resp.body().contains("definitions"),
                "expected a WSDL <definitions> document: " + resp.body());
        assertTrue(resp.body().contains(expectedTargetNamespace),
                "expected WSDL to declare " + expectedTargetNamespace + ": " + resp.body());
    }
}
