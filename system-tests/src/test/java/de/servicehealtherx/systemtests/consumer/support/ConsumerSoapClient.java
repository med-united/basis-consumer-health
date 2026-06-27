package de.servicehealtherx.systemtests.consumer.support;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thin black-box SOAP client shared by the Basis-Consumer system tests. It posts hand-written SOAP
 * 1.1 envelopes over plain HTTP to the running CXF endpoints (no generated stubs, no Quarkus
 * runtime), so each test reflects exactly what an external primary system puts on the wire.
 *
 * <p>The target server is taken from the {@code systemtest.base.url} system property
 * (default {@code http://localhost:8080/ws}); a card handle for the card-bound crypto operations
 * is taken from {@code systemtest.card.handle} (default {@code card-1}).
 */
public final class ConsumerSoapClient {

    public static final String BASE_URL =
            System.getProperty("systemtest.base.url", "http://localhost:8080/ws");

    /** Card handle used by the card-bound operations; maps to key alias {@code sicct/<handle>}. */
    public static final String CARD_HANDLE =
            System.getProperty("systemtest.card.handle", "card-1");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** POST a SOAP envelope to {@code BASE_URL + path} with the given (unquoted) SOAPAction. */
    public HttpResponse<String> post(String path, String soap, String soapAction)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "text/xml; charset=UTF-8")
                .header("SOAPAction", '"' + soapAction + '"')
                .POST(HttpRequest.BodyPublishers.ofString(soap, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** GET a URL (e.g. {@code ?wsdl}); used by the interoperability conformance test. */
    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Whether any consumer endpoint answers at all (used to skip the suite when no server runs). */
    public boolean serverReachable() {
        try {
            HttpResponse<String> resp = post(
                    ConsumerNamespaces.EP_CERT,
                    ConsumerEnvelopes.readCertificate("probe", "C.AUT", "ECC"),
                    ConsumerNamespaces.ACTION_READ_CERTIFICATE);
            return resp.statusCode() > 0;
        } catch (ConnectException e) {
            return false;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // ─── response classification ──────────────────────────────────────────────────────────────

    /** A SOAP Fault is present (the consumer maps every operation error to a gematik-SOAP-Fault). */
    public static boolean isSoapFault(HttpResponse<String> resp) {
        String b = resp.body();
        return b.contains("Fault>") || b.contains(":Fault") || b.contains("<faultstring")
                || b.contains("faultcode");
    }

    // ─── assertions ───────────────────────────────────────────────────────────────────────────

    /** Assert HTTP 200, no SOAP Fault, and that the expected response element is present. */
    public static void assertOk(HttpResponse<String> resp, String expectedResponseElement) {
        assertFalse(isSoapFault(resp), "unexpected SOAP Fault: " + resp.body());
        assertTrue(resp.statusCode() == 200,
                "expected HTTP 200 but got " + resp.statusCode() + ": " + resp.body());
        assertTrue(resp.body().contains(expectedResponseElement),
                "expected <" + expectedResponseElement + "> in response, got: " + resp.body());
    }

    /** Assert a SOAP Fault came back (the contract for invalid / failing requests). */
    public static void assertSoapFault(HttpResponse<String> resp) {
        assertTrue(isSoapFault(resp),
                "expected a SOAP Fault but got status " + resp.statusCode() + ": " + resp.body());
    }

    // ─── small text helpers (namespace-agnostic, like the konnektor flow tests) ────────────────

    /** First text content of an element with the given local name, ignoring any namespace prefix. */
    public static String firstMatch(String xml, String localName) {
        Matcher m = Pattern.compile(
                        "<(?:[\\w.-]+:)?" + Pattern.quote(localName) + "\\b[^>]*>(.*?)</(?:[\\w.-]+:)?"
                                + Pattern.quote(localName) + ">",
                        Pattern.DOTALL)
                .matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    public static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] decodeBase64(String value) {
        return Base64.getDecoder().decode(value.trim());
    }
}
