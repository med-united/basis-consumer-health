package de.servicehealtherx.crypto.services.popp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import de.servicehealtherx.crypto.signer.card.EcdsaDerEncoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Verifies and parses a PoPP signed scenario — the {@code signedScenario} JWS carried in
 * CardService {@code SecureSendAPDU} (gematik api-popp 3.0.0, {@code ConnectorScenarioMessage}).
 *
 * <p>The JWS is a compact serialization {@code header.payload.signature}:
 * <ul>
 *   <li><b>header</b> — {@code {"typ":"JWT","alg":"ES256","x5c":["MII…"],"stpl":"…"}}; the
 *       signature verification key is the end-entity certificate in {@code x5c[0]}.</li>
 *   <li><b>payload</b> — {@code {"message": StandardScenarioMessage}} with the ordered
 *       {@code steps[]} of command APDUs and their expected status words.</li>
 *   <li><b>signature</b> — ES256 (ECDSA P-256 + SHA-256) over {@code ASCII(header) + "." +
 *       ASCII(payload)}, in JOSE {@code R||S} form.</li>
 * </ul>
 *
 * <p>This class verifies the cryptographic signature against {@code x5c[0]} and parses the scenario;
 * certificate <em>chain</em> trust (TUC_PKI_018 against the TSL) is layered on by the caller.
 */
public final class PoppScenarioVerifier {

    private static final JsonFactory JSON = new JsonFactory();

    /** Verify the JWS signature against its embedded {@code x5c} certificate and parse the scenario. */
    public PoppScenario verify(String signedScenario) {
        if (signedScenario == null || signedScenario.isBlank()) {
            throw new IllegalArgumentException("signedScenario must not be empty");
        }
        String[] segments = signedScenario.split("\\.");
        if (segments.length != 3) {
            throw new IllegalArgumentException(
                    "signedScenario is not a compact JWS (expected header.payload.signature)");
        }

        byte[] headerJson = base64Url(segments[0], "JWS header");
        byte[] payloadJson = base64Url(segments[1], "JWS payload");
        byte[] rawSignature = base64Url(segments[2], "JWS signature");

        Header header = parseHeader(headerJson);
        if (!"ES256".equals(header.alg)) {
            throw new IllegalArgumentException("Unsupported JWS alg '" + header.alg + "'; only ES256 is supported");
        }
        X509Certificate signerCert = decodeCertificate(header.x5c0);

        verifySignature(signerCert, segments[0], segments[1], rawSignature);

        StandardScenario message = parsePayload(payloadJson);
        return new PoppScenario(
                message.clientSessionId, message.sequenceCounter, message.timeSpan, message.steps, signerCert);
    }

    private static void verifySignature(X509Certificate cert, String headerB64, String payloadB64,
            byte[] joseSignature) {
        // ES256 signatures are the fixed-width R||S concatenation; JCA's SHA256withECDSA expects DER.
        byte[] signingInput = (headerB64 + "." + payloadB64).getBytes(StandardCharsets.US_ASCII);
        try {
            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initVerify(cert.getPublicKey());
            ecdsa.update(signingInput);
            if (!ecdsa.verify(EcdsaDerEncoder.rawToDer(joseSignature))) {
                throw new SecurityException("PoPP signed scenario signature is invalid");
            }
        } catch (GeneralSecurityException e) {
            throw new SecurityException("PoPP signed scenario signature verification failed: " + e.getMessage(), e);
        }
    }

    private static X509Certificate decodeCertificate(String x5c0) {
        if (x5c0 == null || x5c0.isBlank()) {
            throw new IllegalArgumentException("JWS header has no x5c signing certificate");
        }
        try {
            // RFC 7515 x5c entries are base64 (not base64url) of the DER certificate.
            byte[] der = Base64.getDecoder().decode(x5c0);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (IllegalArgumentException | java.security.cert.CertificateException e) {
            throw new IllegalArgumentException("Invalid x5c certificate in JWS header: " + e.getMessage(), e);
        }
    }

    private static byte[] base64Url(String segment, String what) {
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " is not valid base64url: " + e.getMessage(), e);
        }
    }

    // ── JSON parsing (jackson-core streaming; databind is not on the classpath) ───────────────────

    private record Header(String alg, String x5c0) {
    }

    private record StandardScenario(String clientSessionId, int sequenceCounter, int timeSpan,
            List<ScenarioStep> steps) {
    }

    private static Header parseHeader(byte[] json) {
        try (JsonParser p = JSON.createParser(json)) {
            String alg = null;
            String x5c0 = null;
            expect(p.nextToken(), JsonToken.START_OBJECT, "JWS header");
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "alg" -> alg = p.getValueAsString();
                    case "x5c" -> {
                        expect(p.currentToken(), JsonToken.START_ARRAY, "x5c");
                        if (p.nextToken() != JsonToken.END_ARRAY) {
                            x5c0 = p.getValueAsString(); // first (end-entity) certificate
                            while (p.nextToken() != JsonToken.END_ARRAY) {
                                // skip remaining chain certificates
                            }
                        }
                    }
                    default -> p.skipChildren();
                }
            }
            return new Header(alg, x5c0);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed JWS header JSON: " + e.getMessage(), e);
        }
    }

    private static StandardScenario parsePayload(byte[] json) {
        try (JsonParser p = JSON.createParser(json)) {
            expect(p.nextToken(), JsonToken.START_OBJECT, "JWS payload");
            StandardScenario message = null;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                if ("message".equals(field)) {
                    message = parseMessage(p);
                } else {
                    p.skipChildren();
                }
            }
            if (message == null) {
                throw new IllegalArgumentException("PoPP scenario payload has no 'message'");
            }
            return message;
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed PoPP scenario payload JSON: " + e.getMessage(), e);
        }
    }

    private static StandardScenario parseMessage(JsonParser p) throws IOException {
        expect(p.currentToken(), JsonToken.START_OBJECT, "message");
        String clientSessionId = null;
        Integer sequenceCounter = null;
        int timeSpan = 0;
        List<ScenarioStep> steps = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "clientSessionId" -> clientSessionId = p.getValueAsString();
                case "sequenceCounter" -> sequenceCounter = p.getIntValue();
                case "timeSpan" -> timeSpan = p.getIntValue();
                case "steps" -> {
                    expect(p.currentToken(), JsonToken.START_ARRAY, "steps");
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        steps.add(parseStep(p));
                    }
                }
                default -> p.skipChildren();
            }
        }
        if (clientSessionId == null || sequenceCounter == null) {
            throw new IllegalArgumentException("PoPP scenario message missing clientSessionId or sequenceCounter");
        }
        return new StandardScenario(clientSessionId, sequenceCounter, timeSpan, steps);
    }

    private static ScenarioStep parseStep(JsonParser p) throws IOException {
        expect(p.currentToken(), JsonToken.START_OBJECT, "scenario step");
        byte[] commandApdu = null;
        List<String> expected = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "commandApdu" -> commandApdu = hexToBytes(p.getValueAsString());
                case "expectedStatusWords" -> {
                    expect(p.currentToken(), JsonToken.START_ARRAY, "expectedStatusWords");
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        expected.add(p.getValueAsString().toLowerCase());
                    }
                }
                default -> p.skipChildren();
            }
        }
        if (commandApdu == null) {
            throw new IllegalArgumentException("PoPP scenario step has no commandApdu");
        }
        return new ScenarioStep(commandApdu, List.copyOf(expected));
    }

    private static void expect(JsonToken actual, JsonToken wanted, String where) {
        if (actual != wanted) {
            throw new IllegalArgumentException("Malformed PoPP scenario: expected " + wanted + " for " + where
                    + " but found " + actual);
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty() || (hex.length() % 2) != 0) {
            throw new IllegalArgumentException("commandApdu is not a valid hex string");
        }
        try {
            return java.util.HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("commandApdu is not a valid hex string: " + e.getMessage(), e);
        }
    }
}
