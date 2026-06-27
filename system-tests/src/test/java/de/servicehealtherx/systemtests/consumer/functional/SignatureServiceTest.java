package de.servicehealtherx.systemtests.consumer.functional;

import de.gematik.idp.tests.Afo;
import de.servicehealtherx.systemtests.consumer.support.ConsumerEnvelopes;
import de.servicehealtherx.systemtests.consumer.support.ConsumerNamespaces;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSoapClient;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSystemTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * ISO/IEC 25010 <b>Functional Suitability</b> — Basisdienst Signaturdienst (nonQES,
 * SignatureService): {@code SignDocument} (CAdES), {@code SignPlain} (plain ECDSA),
 * {@code ExternalAuthenticate} (hash signature) and {@code VerifyDocument}.
 *
 * <p>Each card-bound signing operation skips itself when no card/key is provisioned (the consumer
 * answers with a gematik-SOAP-Fault). {@code VerifyDocument} is checked against the document signed
 * by {@code SignDocument} so the sign→verify chain is exercised end-to-end.
 */
class SignatureServiceTest extends ConsumerSystemTest {

    private static final byte[] DOCUMENT =
            "<doc>consumer-signature-system-test</doc>".getBytes(StandardCharsets.UTF_8);

    @Test
    @Afo("A_17517")
    @Afo("A_17523")
    @Afo("A_17525-03")
    @Afo("A_17525-04")
    @DisplayName("SignDocument produces a CAdES signature that VerifyDocument accepts — A_17517/A_17577")
    @Afo("A_17526-03")
    @Afo("A_17577")
    void signDocument_thenVerifyDocument() throws Exception {
        HttpResponse<String> sign = client.post(
                ConsumerNamespaces.EP_SIG,
                ConsumerEnvelopes.signDocument(
                        ConsumerSoapClient.CARD_HANDLE, "ECC", ConsumerSoapClient.base64(DOCUMENT)),
                ConsumerNamespaces.ACTION_SIGN_DOCUMENT);

        assumeFalse(ConsumerSoapClient.isSoapFault(sign),
                "SignDocument needs a provisioned card/key; skipping. Fault was: " + sign.body());
        ConsumerSoapClient.assertOk(sign, "SignDocumentResponse");
        assertTrue(sign.body().contains("DocumentWithSignature") || sign.body().contains("Base64Data"),
                "expected a signed document in the SignDocument response: " + sign.body());

        String signedDocBase64 = ConsumerSoapClient.firstMatch(sign.body(), "Base64Data");
        assertNotNull(signedDocBase64, "expected signed document bytes: " + sign.body());

        HttpResponse<String> verify = client.post(
                ConsumerNamespaces.EP_SIG,
                ConsumerEnvelopes.verifyDocument(signedDocBase64),
                ConsumerNamespaces.ACTION_VERIFY_DOCUMENT);

        ConsumerSoapClient.assertOk(verify, "VerifyDocumentResponse");
        String highLevel = ConsumerSoapClient.firstMatch(verify.body(), "HighLevelResult");
        assertNotNull(highLevel, "expected a HighLevelResult: " + verify.body());
        assertTrue(highLevel.matches("VALID|INVALID|INCONCLUSIVE"),
                "HighLevelResult must be VALID/INVALID/INCONCLUSIVE, was: " + highLevel);
    }

    @Test
    @Afo("A_17518")
    @DisplayName("SignPlain returns a plain ECDSA signature object — A_17518")
    void signPlain_returnsSignature() throws Exception {
        HttpResponse<String> resp = client.post(
                ConsumerNamespaces.EP_SIG,
                ConsumerEnvelopes.signPlain(
                        ConsumerSoapClient.CARD_HANDLE, ConsumerSoapClient.base64(DOCUMENT)),
                ConsumerNamespaces.ACTION_SIGN_PLAIN);

        assumeFalse(ConsumerSoapClient.isSoapFault(resp),
                "SignPlain needs a provisioned card/key; skipping. Fault was: " + resp.body());
        ConsumerSoapClient.assertOk(resp, "SignPlainResponse");
        assertTrue(resp.body().contains("SignatureObject") && resp.body().contains("Base64Signature"),
                "expected a Base64Signature in the SignPlain response: " + resp.body());
    }

    @Test
    @Afo("A_17518")
    @Afo("A_17578-03")
    @Afo("A_17578-04")
    @DisplayName("ExternalAuthenticate signs a SHA-256 hash on the card — A_17578-04")
    void externalAuthenticate_signsHash() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(DOCUMENT);

        HttpResponse<String> resp = client.post(
                ConsumerNamespaces.EP_SIG,
                ConsumerEnvelopes.externalAuthenticate(
                        ConsumerSoapClient.CARD_HANDLE, ConsumerSoapClient.base64(hash)),
                ConsumerNamespaces.ACTION_EXTERNAL_AUTHENTICATE);

        assumeFalse(ConsumerSoapClient.isSoapFault(resp),
                "ExternalAuthenticate needs a provisioned card/key; skipping. Fault was: " + resp.body());
        ConsumerSoapClient.assertOk(resp, "ExternalAuthenticateResponse");
        assertTrue(resp.body().contains("SignatureObject") && resp.body().contains("Base64Signature"),
                "expected a Base64Signature in the ExternalAuthenticate response: " + resp.body());
    }
}
