package de.servicehealtherx.systemtests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Black-box system test that exercises every SOAP request needed to create (sign) an e-prescription
 * against a <em>running</em> Konnektor server, in the order a primary system would issue them. The
 * actual wire flow lives in the reusable, crypto-provider-independent {@link EPrescriptionFlow}; this
 * class just points it at an external server and drives its steps in order:
 *
 * <ol>
 *   <li>{@code EventService.GetCards} &mdash; discover the inserted cards and pick a card handle.</li>
 *   <li>{@code CertificateService.ReadCardCertificate} {@code C.AUT} &mdash; read the auth cert.</li>
 *   <li>{@code AuthSignatureService.ExternalAuthenticate} &mdash; sign a hash with C.AUT.</li>
 *   <li>{@code SignatureService.GetJobNumber} &mdash; obtain a job number for the signing batch.</li>
 *   <li>{@code SignatureService.SignDocument} &mdash; produce the qualified signature.</li>
 * </ol>
 *
 * <p>The target server is taken from the {@code systemtest.base.url} system property
 * (default {@code http://localhost:8080/ws}). Because this is a <em>system</em> test it needs a live
 * server: when none is reachable the whole class skips itself via a JUnit assumption rather than
 * failing the reactor build, and the card-bound steps skip when no provisioned card is reachable. A
 * specific card handle can be forced with {@code systemtest.card.handle}.
 *
 * <p>Because the flow only speaks the connector wire protocol it is independent of the server's crypto
 * provider; {@link SicctTerminalPairingSystemTest} runs the very same {@link EPrescriptionFlow} against
 * a Konnektor whose card is provided by a paired <b>SICCT terminal</b>.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class EPrescriptionSoapFlowTest {

    private static final String BASE_URL =
            System.getProperty("systemtest.base.url", "http://localhost:8080/ws");

    private final EPrescriptionFlow flow = new EPrescriptionFlow(BASE_URL);

    @BeforeAll
    void serverMustBeReachable() {
        assumeTrue(flow.serverReachable(),
                "No server reachable at " + BASE_URL + " — skipping e-prescription SOAP system test");
    }

    @Test
    @Order(1)
    void getCards_returnsCardListAndYieldsACardHandle() throws Exception {
        flow.selectHbaCardHandle();
    }

    @Test
    @Order(2)
    void readCardCertificate_cAut_returnsCertificate() throws Exception {
        flow.readCAutCertificate();
    }

    @Test
    @Order(3)
    void externalAuthenticate_signsHashWithCAut() throws Exception {
        flow.externalAuthenticate();
    }

    @Test
    @Order(4)
    void getJobNumber_returnsJobNumber() throws Exception {
        flow.getJobNumber();
    }

    @Test
    @Order(5)
    void signDocument_producesSignature() throws Exception {
        flow.signDocument();
    }
}
