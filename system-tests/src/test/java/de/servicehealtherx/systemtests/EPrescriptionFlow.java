package de.servicehealtherx.systemtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

/**
 * The e-prescription SOAP signing flow as a reusable, <b>crypto-provider-independent</b> sequence of
 * hand-written SOAP requests driven over plain HTTP against <em>any</em> running Konnektor:
 *
 * <ol>
 *   <li>{@code EventService.GetCards} — discover the inserted cards and pick the HBA card handle.</li>
 *   <li>{@code CertificateService.ReadCardCertificate} {@code C.AUT} — read the authentication cert.</li>
 *   <li>{@code AuthSignatureService.ExternalAuthenticate} — sign a hash with C.AUT.</li>
 *   <li>{@code SignatureService.GetJobNumber} — obtain a signing-batch job number.</li>
 *   <li>{@code SignatureService.SignDocument} — produce the qualified signature.</li>
 * </ol>
 *
 * <p>The flow only ever speaks the gematik connector wire protocol, so it is agnostic to which crypto
 * provider backs the selected card on the server: the very same steps exercise a card behind a
 * directly-attached <b>PC/SC reader</b> (when run against a PC/SC-provisioned server) or a card behind
 * a paired <b>SICCT terminal</b> (when run against a SICCT-backed server). Card-bound steps
 * {@code assume}-skip (rather than fail) when the server has no provisioned HBA reachable, matching the
 * consumer suite's behaviour.
 *
 * <p>State (the selected {@code cardHandle} and the {@code jobNumber}) is carried across the ordered
 * step methods, so a caller drives a single instance through {@link #selectHbaCardHandle()} …
 * {@link #signDocument()} (or {@link #runFullFlow()} in one shot).
 */
final class EPrescriptionFlow {

    // SOAP / gematik connector XML namespaces.
    private static final String NS_SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String NS_EVENT = "http://ws.gematik.de/conn/EventService/v7.2";
    private static final String NS_CERT = "http://ws.gematik.de/conn/CertificateService/v6.0";
    private static final String NS_SIG = "http://ws.gematik.de/conn/SignatureService/v7.5";
    private static final String NS_SIG_7_4 = "http://ws.gematik.de/conn/SignatureService/v7.4";
    private static final String NS_CTX = "http://ws.gematik.de/conn/ConnectorContext/v2.0";
    private static final String NS_CCOMMON = "http://ws.gematik.de/conn/ConnectorCommon/v5.0";
    private static final String NS_DSS = "urn:oasis:names:tc:dss:1.0:core:schema";

    // SOAPAction values, taken verbatim from the WSDL @WebMethod(action=...) of each operation.
    private static final String ACTION_GET_CARDS = NS_EVENT + "#GetCards";
    private static final String ACTION_READ_CERT = NS_CERT + "#ReadCardCertificate";
    private static final String ACTION_EXTERNAL_AUTH = NS_SIG_7_4 + "#ExternalAuthenticate";
    private static final String ACTION_GET_JOB_NUMBER = NS_SIG + "#GetJobNumber";
    private static final String ACTION_SIGN_DOCUMENT = NS_SIG + "#SignDocument";

    /** Reusable AufrufKontext block; declares the ctx/cc prefixes used by every request body. */
    private static final String CONTEXT = """
            <ctx:Context>
              <cc:MandantId>Mandant1</cc:MandantId>
              <cc:ClientSystemId>ClientSystem1</cc:ClientSystemId>
              <cc:WorkplaceId>Workplace1</cc:WorkplaceId>
              <cc:UserId>User1</cc:UserId>
            </ctx:Context>""";

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Carried between ordered steps.
    private String cardHandle;
    private String jobNumber;

    /** @param baseUrl base URL of the CXF endpoints, e.g. {@code http://localhost:8080/ws}. */
    EPrescriptionFlow(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    String cardHandle() {
        return cardHandle;
    }

    String jobNumber() {
        return jobNumber;
    }

    /** {@code true} if the server answers a GetCards probe at all (used to skip when none is up). */
    boolean serverReachable() {
        try {
            return post("/conn/EventService", soapGetCards(), ACTION_GET_CARDS).statusCode() > 0;
        } catch (ConnectException e) {
            return false;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // ─── ordered flow steps (each asserts its own postconditions) ───────────────────────────────

    /**
     * Step 1: {@code GetCards}, then pick the <b>HBA</b> card handle (an e-prescription is signed by
     * the practitioner's Heilberufsausweis); fall back to the first card handle, or to a handle forced
     * via {@code -Dsystemtest.card.handle}. {@code assume}-skips when no card handle is available.
     */
    String selectHbaCardHandle() throws Exception {
        HttpResponse<String> resp = post("/conn/EventService", soapGetCards(), ACTION_GET_CARDS);
        assertOkSoap(resp, "GetCardsResponse");

        String forced = System.getProperty("systemtest.card.handle");
        cardHandle = forced != null && !forced.isBlank()
                ? forced.trim()
                : cardHandleForType(resp.body(), "HBA");
        if (cardHandle == null || cardHandle.isBlank()) {
            cardHandle = firstMatch(resp.body(), "CardHandle");
        }
        assumeTrue(cardHandle != null && !cardHandle.isBlank(),
                "GetCards returned no card handle (no HBA inserted?) — skipping the signing flow. "
                        + "Set -Dsystemtest.card.handle=… to force one.");
        return cardHandle;
    }

    /** Step 2: read the C.AUT certificate of the selected card. */
    void readCAutCertificate() throws Exception {
        assumeTrue(cardHandle != null, "no card handle from GetCards");
        HttpResponse<String> resp = post(
                "/conn/CertificateService", soapReadCardCertificate(cardHandle), ACTION_READ_CERT);
        assumeCardOperationAvailable(resp);
        assertOkSoap(resp, "ReadCardCertificateResponse");
        // The C.AUT certificate echoes back inside an X509DataInfoList / X509Certificate element.
        assertTrue(resp.body().contains("X509Certificate"),
                "expected an X509Certificate in the ReadCardCertificate response: " + resp.body());
    }

    /** Step 3: sign a SHA-256 hash with C.AUT via ExternalAuthenticate. */
    void externalAuthenticate() throws Exception {
        assumeTrue(cardHandle != null, "no card handle from GetCards");
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest("e-prescription-to-be-authenticated".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> resp = post(
                "/conn/AuthSignatureService", soapExternalAuthenticate(cardHandle, hash), ACTION_EXTERNAL_AUTH);
        assumeCardOperationAvailable(resp);
        assertOkSoap(resp, "ExternalAuthenticateResponse");
        assertTrue(resp.body().contains("SignatureObject") && resp.body().contains("Base64Signature"),
                "expected a Base64Signature in the ExternalAuthenticate response: " + resp.body());
    }

    /** Step 4: obtain a job number for the signing batch. */
    String getJobNumber() throws Exception {
        HttpResponse<String> resp = post(
                "/conn/SignatureService", soapGetJobNumber(), ACTION_GET_JOB_NUMBER);
        assertHttpOkNoFault(resp);
        jobNumber = firstMatch(resp.body(), "JobNumber");
        assertTrue(jobNumber != null && !jobNumber.isBlank(),
                "expected a JobNumber in the GetJobNumber response: " + resp.body());
        return jobNumber;
    }

    /** Step 5: produce a qualified signature over the prescription document. */
    void signDocument() throws Exception {
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
        assumeCardOperationAvailable(resp);
        assertOkSoap(resp, "SignDocumentResponse");
        // A successful SignDocument returns the signature in one of several shapes depending on the
        // signature form: an enveloping CAdES/PKCS#7 signature comes back as SignatureObject/
        // Base64Signature (what the HBA QES produces here), while an enveloped/document form returns
        // DocumentWithSignature/Base64Data. Accept any of them as a produced signature.
        assertTrue(resp.body().contains("Base64Signature")
                        || resp.body().contains("DocumentWithSignature")
                        || resp.body().contains("Base64Data"),
                "expected a signature in the SignDocument response: " + resp.body());
    }

    /** Run all five steps in order. */
    void runFullFlow() throws Exception {
        selectHbaCardHandle();
        readCAutCertificate();
        externalAuthenticate();
        getJobNumber();
        signDocument();
    }

    // ─── SOAP envelope builders ──────────────────────────────────────────────────────────────

    private static String envelope(String bodyContent) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="%s"
                                  xmlns:ctx="%s"
                                  xmlns:cc="%s"
                                  xmlns:dss="%s">
                  <soapenv:Header/>
                  <soapenv:Body>
                %s
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(NS_SOAP, NS_CTX, NS_CCOMMON, NS_DSS, bodyContent);
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
        // AuthSignatureService binds ExternalAuthenticate to the SignatureService v7.4 namespace,
        // and its BinaryString child lives in that SAME v7.4 namespace (the WSDL imports the v7.4
        // SignatureService schema). Sending BinaryString in v7.5 makes the server reject it with an
        // "unexpected element {…/v7.5}BinaryString, expected {…/v7.4}BinaryString" unmarshalling fault.
        return envelope("""
                    <sig74:ExternalAuthenticate xmlns:sig74="%s">
                      <cc:CardHandle>%s</cc:CardHandle>
                      %s
                      <sig74:BinaryString>
                        <dss:Base64Data MimeType="application/octet-stream">%s</dss:Base64Data>
                      </sig74:BinaryString>
                    </sig74:ExternalAuthenticate>
                """.formatted(NS_SIG_7_4, xml(cardHandle), CONTEXT, base64(hash)));
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
                      <sig:Crypt>ECC</sig:Crypt>
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
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

    /**
     * Card-bound operations need a real provisioned card/key reachable by the server (an HBA with
     * C.AUT/QES, behind either a PC/SC reader or a paired SICCT terminal). When the server has no such
     * card it answers with a SOAP fault (e.g. "No card found for handle", "no requested certificate
     * could be read"); treat that as an environment that cannot exercise the signing flow and
     * {@code skip} rather than fail.
     */
    private static void assumeCardOperationAvailable(HttpResponse<String> resp) {
        boolean fault = resp.body().contains("Fault") && resp.body().contains("faultstring");
        assumeTrue(!fault,
                "card-bound operation faulted — no provisioned card reachable by the server; "
                        + "skipping the signing flow (insert a card / set -Dsystemtest.card.handle=…): "
                        + resp.body());
    }

    /**
     * The {@code CardHandle} of the first {@code <Card>} block whose {@code CardType} equals
     * {@code cardType} (e.g. {@code HBA}), or {@code null} if none is present. Splits the response into
     * per-card blocks so the handle and type of the same card are matched together.
     */
    private static String cardHandleForType(String xml, String cardType) {
        Matcher cards = Pattern.compile(
                        "<(?:[\\w.-]+:)?Card>(.*?)</(?:[\\w.-]+:)?Card>", Pattern.DOTALL)
                .matcher(xml);
        while (cards.find()) {
            String card = cards.group(1);
            String type = firstMatch(card, "CardType");
            if (cardType.equalsIgnoreCase(type)) {
                return firstMatch(card, "CardHandle");
            }
        }
        return null;
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
