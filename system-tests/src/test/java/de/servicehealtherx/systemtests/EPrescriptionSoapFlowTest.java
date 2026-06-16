package de.servicehealtherx.systemtests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Black-box system test that exercises every SOAP request needed to create (sign) an
 * e-prescription against a <em>running</em> Konnektor server, in the order a primary system
 * would issue them:
 *
 * <ol>
 *   <li>{@code EventService.GetCards} &mdash; discover the inserted cards and pick a card handle.</li>
 *   <li>{@code CertificateService.ReadCardCertificate} for {@code <CertRef>C.AUT</CertRef>}
 *       &mdash; read the authentication certificate of that card.</li>
 *   <li>{@code AuthSignatureService.ExternalAuthenticate} &mdash; sign a hash with C.AUT.</li>
 *   <li>{@code SignatureService.GetJobNumber} &mdash; obtain a job number for the signing batch.</li>
 *   <li>{@code SignatureService.SignDocument} &mdash; produce the qualified signature over the
 *       prescription document.</li>
 * </ol>
 *
 * <p>Each step sends a hand-written SOAP envelope over plain HTTP (no generated stubs, no Quarkus
 * runtime) so the test reflects exactly what an external client sends on the wire.
 *
 * <p>The target server is taken from the {@code systemtest.base.url} system property
 * (default {@code http://localhost:8080/ws}). Because this is a <em>system</em> test it needs a
 * live server: when none is reachable the whole class skips itself via a JUnit assumption rather
 * than failing the reactor build. A specific card handle can be forced with the
 * {@code systemtest.card.handle} system property, otherwise it is taken from the GetCards response.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class EPrescriptionSoapFlowTest {

    private static final String BASE_URL =
            System.getProperty("systemtest.base.url", "http://localhost:8080/ws");

    // SOAP / gematik connector XML namespaces.
    private static final String NS_SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String NS_EVENT = "http://ws.gematik.de/conn/EventService/v7.2";
    private static final String NS_CERT = "http://ws.gematik.de/conn/CertificateService/v6.0";
    private static final String NS_SIG = "http://ws.gematik.de/conn/SignatureService/v7.5";
    private static final String NS_CTX = "http://ws.gematik.de/conn/ConnectorContext/v2.0";
    private static final String NS_CCOMMON = "http://ws.gematik.de/conn/ConnectorCommon/v5.0";
    private static final String NS_DSS = "urn:oasis:names:tc:dss:1.0:core:schema";

    // SOAPAction values, taken verbatim from the WSDL @WebMethod(action=...) of each operation.
    private static final String ACTION_GET_CARDS = NS_EVENT + "#GetCards";
    private static final String ACTION_READ_CERT = NS_CERT + "#ReadCardCertificate";
    private static final String ACTION_EXTERNAL_AUTH =
            "http://ws.gematik.de/conn/SignatureService/v7.4#ExternalAuthenticate";
    private static final String ACTION_GET_JOB_NUMBER = NS_SIG + "#GetJobNumber";
    private static final String ACTION_SIGN_DOCUMENT = NS_SIG + "#SignDocument";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Reusable AufrufKontext block; declares the ctx/cc prefixes used by every request body. */
    private static final String CONTEXT = """
            <ctx:Context>
              <cc:MandantId>Mandant1</cc:MandantId>
              <cc:ClientSystemId>ClientSystem1</cc:ClientSystemId>
              <cc:WorkplaceId>Workplace1</cc:WorkplaceId>
              <cc:UserId>User1</cc:UserId>
            </ctx:Context>""";

    // Carried between ordered steps.
    private String cardHandle;
    private String jobNumber;

    @BeforeAll
    void serverMustBeReachable() {
        boolean reachable;
        try {
            HttpResponse<String> resp = post(
                    "/conn/EventService", soapGetCards(), ACTION_GET_CARDS);
            reachable = resp.statusCode() > 0;
        } catch (ConnectException e) {
            reachable = false;
        } catch (IOException | InterruptedException e) {
            reachable = false;
        }
        assumeTrue(reachable,
                "No server reachable at " + BASE_URL + " — skipping e-prescription SOAP system test");
    }

    @Test
    @Order(1)
    void getCards_returnsCardListAndYieldsACardHandle() throws Exception {
        HttpResponse<String> resp = post(
                "/conn/EventService", soapGetCards(), ACTION_GET_CARDS);

        assertOkSoap(resp, "GetCardsResponse");

        String forced = System.getProperty("systemtest.card.handle");
        cardHandle = forced != null && !forced.isBlank()
                ? forced.trim()
                : firstMatch(resp.body(), "CardHandle");

        assumeTrue(cardHandle != null && !cardHandle.isBlank(),
                "GetCards returned no card handle (no card inserted?) — skipping the signing flow. "
                        + "Set -Dsystemtest.card.handle=... to force one.");
    }

    @Test
    @Order(2)
    void readCardCertificate_cAut_returnsCertificate() throws Exception {
        assumeTrue(cardHandle != null, "no card handle from GetCards");

        HttpResponse<String> resp = post(
                "/conn/CertificateService", soapReadCardCertificate(cardHandle),
                ACTION_READ_CERT);

        assertOkSoap(resp, "ReadCardCertificateResponse");
        // The C.AUT certificate echoes back inside an X509DataInfoList / X509Certificate element.
        assertTrue(resp.body().contains("X509Certificate"),
                "expected an X509Certificate in the ReadCardCertificate response: " + resp.body());
    }

    @Test
    @Order(3)
    void externalAuthenticate_signsHashWithCAut() throws Exception {
        assumeTrue(cardHandle != null, "no card handle from GetCards");

        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest("e-prescription-to-be-authenticated".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> resp = post(
                "/conn/AuthSignatureService", soapExternalAuthenticate(cardHandle, hash),
                ACTION_EXTERNAL_AUTH);

        assertOkSoap(resp, "ExternalAuthenticateResponse");
        assertTrue(resp.body().contains("SignatureObject") && resp.body().contains("Base64Signature"),
                "expected a Base64Signature in the ExternalAuthenticate response: " + resp.body());
    }

    @Test
    @Order(4)
    void getJobNumber_returnsJobNumber() throws Exception {
        HttpResponse<String> resp = post(
                "/conn/SignatureService", soapGetJobNumber(),
                ACTION_GET_JOB_NUMBER);

        assertHttpOkNoFault(resp);
        jobNumber = firstMatch(resp.body(), "JobNumber");
        assertTrue(jobNumber != null && !jobNumber.isBlank(),
                "expected a JobNumber in the GetJobNumber response: " + resp.body());
    }

    @Test
    @Order(5)
    void signDocument_producesSignature() throws Exception {
        assumeTrue(cardHandle != null, "no card handle from GetCards");
        if (jobNumber == null) {
            jobNumber = "0";
        }

        byte[] prescription = ("<?xml version=\"1.0\"?><ePrescription id=\"sys-test\">"
                + "<medication>Ibuprofen 400mg</medication></ePrescription>")
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> resp = post(
                "/conn/SignatureService", soapSignDocument(cardHandle, jobNumber, prescription),
                ACTION_SIGN_DOCUMENT);

        assertOkSoap(resp, "SignDocumentResponse");
        assertTrue(resp.body().contains("DocumentWithSignature") || resp.body().contains("Base64Data"),
                "expected a signed document in the SignDocument response: " + resp.body());
    }

    // ─── SOAP envelope builders ──────────────────────────────────────────────────────────────

    private static String envelope(String bodyContent, String... extraNamespaces) {
        StringBuilder ns = new StringBuilder();
        for (String n : extraNamespaces) {
            ns.append(' ').append(n);
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="%s"
                                  xmlns:ctx="%s"
                                  xmlns:cc="%s"
                                  xmlns:dss="%s"%s>
                  <soapenv:Header/>
                  <soapenv:Body>
                %s
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(NS_SOAP, NS_CTX, NS_CCOMMON, NS_DSS, ns, bodyContent);
    }

    private static String soapGetCards() {
        return envelope("""
                    <evt:GetCards xmlns:evt="%s">
                      %s
                    </evt:GetCards>
                """.formatted(NS_EVENT, CONTEXT));
    }

    private static String soapReadCardCertificate(String cardHandle) {
        return envelope("""
                    <crt:ReadCardCertificate xmlns:crt="%s">
                      <cc:CardHandle>%s</cc:CardHandle>
                      %s
                      <crt:CertRefList>
                        <crt:CertRef>C.AUT</crt:CertRef>
                      </crt:CertRefList>
                      <crt:Crypt>ECC</crt:Crypt>
                    </crt:ReadCardCertificate>
                """.formatted(NS_CERT, xml(cardHandle), CONTEXT));
    }

    private static String soapExternalAuthenticate(String cardHandle, byte[] hash) {
        return envelope("""
                    <sig:ExternalAuthenticate xmlns:sig="%s">
                      <cc:CardHandle>%s</cc:CardHandle>
                      %s
                      <sig:BinaryString>
                        <dss:Base64Data MimeType="application/octet-stream">%s</dss:Base64Data>
                      </sig:BinaryString>
                    </sig:ExternalAuthenticate>
                """.formatted(NS_SIG, xml(cardHandle), CONTEXT, base64(hash)));
    }

    private static String soapGetJobNumber() {
        return envelope("""
                    <sig:GetJobNumber xmlns:sig="%s">
                      %s
                    </sig:GetJobNumber>
                """.formatted(NS_SIG, CONTEXT));
    }

    private static String soapSignDocument(String cardHandle, String jobNumber, byte[] document) {
        return envelope("""
                    <sig:SignDocument xmlns:sig="%s">
                      <cc:CardHandle>%s</cc:CardHandle>
                      <sig:Crypt>RSA</sig:Crypt>
                      %s
                      <sig:TvMode>NONE</sig:TvMode>
                      <sig:JobNumber>%s</sig:JobNumber>
                      <sig:SignRequest RequestID="req-1">
                        <sig:Document ShortText="e-prescription">
                          <cc:Base64XML>%s</cc:Base64XML>
                        </sig:Document>
                        <sig:IncludeRevocationInfo>false</sig:IncludeRevocationInfo>
                      </sig:SignRequest>
                    </sig:SignDocument>
                """.formatted(NS_SIG, xml(cardHandle), CONTEXT, xml(jobNumber), base64(document)));
    }

    // ─── HTTP + assertion helpers ────────────────────────────────────────────────────────────

    private HttpResponse<String> post(String path, String soap, String soapAction)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "text/xml; charset=UTF-8")
                .header("SOAPAction", '"' + soapAction + '"')
                .POST(HttpRequest.BodyPublishers.ofString(soap, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Assert HTTP 200, no SOAP Fault, and that the expected response element is present. */
    private static void assertOkSoap(HttpResponse<String> resp, String expectedResponseElement) {
        assertHttpOkNoFault(resp);
        assertTrue(resp.body().contains(expectedResponseElement),
                "expected <" + expectedResponseElement + "> in response, got: " + resp.body());
    }

    private static void assertHttpOkNoFault(HttpResponse<String> resp) {
        assertEquals(200, resp.statusCode(),
                "expected HTTP 200 but got " + resp.statusCode() + ": " + resp.body());
        assertFalse(resp.body().contains("Fault") && resp.body().contains("faultstring"),
                "SOAP Fault returned: " + resp.body());
    }

    /** First text content of an element with the given local name, ignoring any namespace prefix. */
    private static String firstMatch(String xml, String localName) {
        Matcher m = Pattern.compile(
                        "<(?:[\\w.-]+:)?" + Pattern.quote(localName) + "\\b[^>]*>(.*?)</(?:[\\w.-]+:)?"
                                + Pattern.quote(localName) + ">",
                        Pattern.DOTALL)
                .matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static String xml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
