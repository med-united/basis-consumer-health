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
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Black-box system test for the Konnektor {@code EncryptionService} (api-telematik/conn,
 * {@code WSDL/v6.1}), exercised with a <em>KIM</em> (Kommunikation im Medizinwesen) MIME message
 * as the payload — the exact shape a KIM client mail-gateway hands to the connector before putting
 * a message on the wire.
 *
 * <p>It drives the full round-trip a KIM sender/receiver pair performs against a <em>running</em>
 * Konnektor server, in order:
 *
 * <ol>
 *   <li>{@code EventService.GetCards} — discover the inserted cards and pick a card handle.</li>
 *   <li>{@code CertificateService.ReadCardCertificate} for {@code <CertRef>C.ENC</CertRef>} —
 *       read the recipient's <em>encryption</em> certificate of that card.</li>
 *   <li>{@code EncryptionService.EncryptDocument} — TI-ECIES-encrypt the KIM MIME message to that
 *       certificate (gemSpec_Krypt §4.7 / A_17220); yields a CMS {@code AuthEnvelopedData}.</li>
 *   <li>{@code EncryptionService.DecryptDocument} — unwrap the transport key with the card's
 *       private key (referenced by card handle) and recover the original KIM MIME message.</li>
 * </ol>
 *
 * <p>Each step sends a hand-written SOAP envelope over plain HTTP (no generated stubs, no Quarkus
 * runtime) so the test reflects exactly what a KIM client sends on the wire. As a <em>system</em>
 * test it needs a live server with an EC encryption card; when none is reachable — or no card /
 * C.ENC certificate is available — it skips itself via JUnit assumptions rather than failing the
 * reactor build. Target server: {@code -Dsystemtest.base.url} (default
 * {@code http://localhost:8080/ws}); a card handle can be forced with
 * {@code -Dsystemtest.card.handle}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class KimEncryptionSoapFlowTest {

    private static final String BASE_URL =
            System.getProperty("systemtest.base.url", "http://localhost:8080/ws");

    // SOAP / gematik connector XML namespaces.
    private static final String NS_SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String NS_EVENT = "http://ws.gematik.de/conn/EventService/v7.2";
    private static final String NS_CERT = "http://ws.gematik.de/conn/CertificateService/v6.0";
    private static final String NS_CRYPT = "http://ws.gematik.de/conn/EncryptionService/v6.1";
    private static final String NS_CTX = "http://ws.gematik.de/conn/ConnectorContext/v2.0";
    private static final String NS_CCOMMON = "http://ws.gematik.de/conn/ConnectorCommon/v5.0";
    private static final String NS_DSS = "urn:oasis:names:tc:dss:1.0:core:schema";

    // SOAPAction values, taken verbatim from the WSDL @WebMethod(action=...) of each operation.
    private static final String ACTION_GET_CARDS = NS_EVENT + "#GetCards";
    private static final String ACTION_READ_CERT = NS_CERT + "#ReadCardCertificate";
    private static final String ACTION_ENCRYPT = NS_CRYPT + "#EncryptDocument";
    private static final String ACTION_DECRYPT =
            "http://ws.gematik.de/conn/crypt/EncryptionService/v6.1#DecryptDocument";

    /**
     * A minimal but realistic KIM MIME message (RFC 5322 / RFC 2045). In a real deployment the KIM
     * client mail-gateway produces this and hands it to the connector's EncryptionService before
     * the encrypted envelope is sent to the recipient's KIM mailbox.
     */
    private static final byte[] KIM_MIME_MESSAGE = ("""
            MIME-Version: 1.0
            From: praxis.mustermann@arztpraxis.kim.telematik
            To: dr.empfaenger@klinikum.kim.telematik
            Subject: =?UTF-8?Q?Arztbrief_=E2=80=93_Patient_Max_Mustermann?=
            Date: Tue, 17 Jun 2026 10:15:00 +0200
            X-KIM-Dienstkennung: Arztbrief;Senden;V1.5
            Content-Type: text/plain; charset=UTF-8
            Content-Transfer-Encoding: 8bit

            Sehr geehrte Kollegin, sehr geehrter Kollege,

            anbei der Arztbrief zu Patient Max Mustermann (geb. 01.01.1980).
            Diagnose: J06.9 Akute Infektion der oberen Atemwege.

            Mit freundlichen Gruessen
            Praxis Mustermann
            """).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

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
    private String recipientCertB64;
    private String cipherTextB64;

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
                "No server reachable at " + BASE_URL + " — skipping KIM encryption SOAP system test");
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
                "GetCards returned no card handle (no card inserted?) — skipping the KIM flow. "
                        + "Set -Dsystemtest.card.handle=... to force one.");
    }

    @Test
    @Order(2)
    void readCardCertificate_cEnc_returnsRecipientCertificate() throws Exception {
        assumeTrue(cardHandle != null, "no card handle from GetCards");

        HttpResponse<String> resp = post(
                "/conn/CertificateService", soapReadCardCertificate(cardHandle),
                ACTION_READ_CERT);

        assertOkSoap(resp, "ReadCardCertificateResponse");
        recipientCertB64 = firstMatch(resp.body(), "X509Certificate");
        assumeTrue(recipientCertB64 != null && !recipientCertB64.isBlank(),
                "ReadCardCertificate(C.ENC) returned no certificate — skipping the KIM flow: "
                        + resp.body());
    }

    @Test
    @Order(3)
    void encryptDocument_encryptsTheKimMimeMessage() throws Exception {
        assumeTrue(recipientCertB64 != null, "no recipient C.ENC certificate");

        HttpResponse<String> resp = post(
                "/conn/EncryptionService", soapEncryptDocument(recipientCertB64, KIM_MIME_MESSAGE),
                ACTION_ENCRYPT);

        assertOkSoap(resp, "EncryptDocumentResponse");
        cipherTextB64 = firstMatch(resp.body(), "Base64Data");
        assertNotNull(cipherTextB64, "expected a Base64Data ciphertext in the EncryptDocument "
                + "response: " + resp.body());

        byte[] cipher = Base64.getMimeDecoder().decode(cipherTextB64);
        assertFalse(new String(cipher, StandardCharsets.ISO_8859_1).contains("Mustermann"),
                "ciphertext must not echo the KIM plaintext back");
    }

    @Test
    @Order(4)
    void decryptDocument_recoversTheOriginalKimMimeMessage() throws Exception {
        assumeTrue(cardHandle != null, "no card handle from GetCards");
        assumeTrue(cipherTextB64 != null, "no ciphertext from EncryptDocument");

        HttpResponse<String> resp = post(
                "/conn/EncryptionService", soapDecryptDocument(cardHandle, cipherTextB64),
                ACTION_DECRYPT);

        assertOkSoap(resp, "DecryptDocumentResponse");
        String recoveredB64 = firstMatch(resp.body(), "Base64Data");
        assertNotNull(recoveredB64, "expected a Base64Data plaintext in the DecryptDocument "
                + "response: " + resp.body());

        byte[] recovered = Base64.getMimeDecoder().decode(recoveredB64);
        assertArrayEquals(KIM_MIME_MESSAGE, recovered,
                "decrypted document must equal the original KIM MIME message");
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
                        <crt:CertRef>C.ENC</crt:CertRef>
                      </crt:CertRefList>
                      <crt:Crypt>ECC</crt:Crypt>
                    </crt:ReadCardCertificate>
                """.formatted(NS_CERT, xml(cardHandle), CONTEXT));
    }

    private static String soapEncryptDocument(String recipientCertB64, byte[] kimMime) {
        return envelope("""
                    <crypt:EncryptDocument xmlns:crypt="%s">
                      %s
                      <crypt:RecipientKeys>
                        <crypt:Certificate>%s</crypt:Certificate>
                      </crypt:RecipientKeys>
                      <cc:Document>
                        <dss:Base64Data MimeType="message/rfc822">%s</dss:Base64Data>
                      </cc:Document>
                    </crypt:EncryptDocument>
                """.formatted(NS_CRYPT, CONTEXT, recipientCertB64.replaceAll("\\s", ""),
                base64(kimMime)));
    }

    private static String soapDecryptDocument(String cardHandle, String cipherTextB64) {
        return envelope("""
                    <crypt:DecryptDocument xmlns:crypt="%s">
                      %s
                      <crypt:PrivateKeyOnCard>
                        <cc:CardHandle>%s</cc:CardHandle>
                        <crypt:KeyReference>C.ENC</crypt:KeyReference>
                      </crypt:PrivateKeyOnCard>
                      <cc:Document>
                        <dss:Base64Data MimeType="application/pkcs7-mime">%s</dss:Base64Data>
                      </cc:Document>
                    </crypt:DecryptDocument>
                """.formatted(NS_CRYPT, CONTEXT, xml(cardHandle), cipherTextB64.replaceAll("\\s", "")));
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
