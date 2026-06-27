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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * ISO/IEC 25010 <b>Functional Suitability</b> — Basisdienst Verschlüsselungsdienst
 * (EncryptionService), implementing the hybrid encipher/decipher system processes.
 *
 * <p>Drives the full {@code EncryptDocument} → {@code DecryptDocument} round-trip and asserts the
 * recovered plaintext is byte-identical to the input — the functional-correctness criterion for
 * PL_TUC_HYBRID_ENCIPHER / PL_TUC_HYBRID_DECIPHER. Skips when no card/key is provisioned.
 */
class EncryptionServiceTest extends ConsumerSystemTest {

    @Test
    @Afo("A_17466")
    @Afo("A_17467")
    @Afo("A_17477")
    @Afo("A_17510-05")
    @Afo("A_17515-03")
    @DisplayName("EncryptDocument → DecryptDocument round-trips the plaintext byte-identically — A_17466/67")
    void encryptThenDecrypt_recoversPlaintext() throws Exception {
        byte[] plaintext = "consumer-encryption-roundtrip-payload".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> enc = client.post(
                ConsumerNamespaces.EP_CRYPT,
                ConsumerEnvelopes.encryptDocument(
                        ConsumerSoapClient.CARD_HANDLE, "ECC", ConsumerSoapClient.base64(plaintext)),
                ConsumerNamespaces.ACTION_ENCRYPT_DOCUMENT);

        assumeFalse(ConsumerSoapClient.isSoapFault(enc),
                "EncryptDocument needs a provisioned card/key; skipping. Fault was: " + enc.body());
        ConsumerSoapClient.assertOk(enc, "EncryptDocumentResponse");

        String ciphertextBase64 = ConsumerSoapClient.firstMatch(enc.body(), "Base64Data");
        assertNotNull(ciphertextBase64, "expected ciphertext Base64Data: " + enc.body());

        HttpResponse<String> dec = client.post(
                ConsumerNamespaces.EP_CRYPT,
                ConsumerEnvelopes.decryptDocument(
                        ConsumerSoapClient.CARD_HANDLE, "ECC", ciphertextBase64),
                ConsumerNamespaces.ACTION_DECRYPT_DOCUMENT);

        ConsumerSoapClient.assertOk(dec, "DecryptDocumentResponse");
        String recoveredBase64 = ConsumerSoapClient.firstMatch(dec.body(), "Base64Data");
        assertNotNull(recoveredBase64, "expected recovered plaintext Base64Data: " + dec.body());

        assertArrayEquals(plaintext, ConsumerSoapClient.decodeBase64(recoveredBase64),
                "decrypted plaintext must equal the original input");
    }
}
