package de.servicehealtherx.systemtests.consumer.security;

import de.gematik.idp.tests.Afo;
import de.servicehealtherx.systemtests.consumer.support.ConsumerEnvelopes;
import de.servicehealtherx.systemtests.consumer.support.ConsumerNamespaces;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSoapClient;
import de.servicehealtherx.systemtests.consumer.support.ConsumerSystemTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * ISO/IEC 25010 <b>Security</b> (integrity) — tampered ciphertext must not decrypt.
 *
 * <p>Encrypts a payload, flips a byte of the resulting CMS ciphertext, and asserts the consumer's
 * {@code DecryptDocument} rejects it (AEAD authentication failure) rather than returning the
 * plaintext — the integrity guarantee behind PL_TUC_HYBRID_DECIPHER (A_17467). Skips when no
 * card/key is provisioned.
 */
class CryptoRoundTripIntegrityTest extends ConsumerSystemTest {

    @Test
    @Afo("A_17467")
    @DisplayName("DecryptDocument rejects tampered ciphertext, never yielding plaintext — A_17467")
    void tamperedCiphertext_isRejected() throws Exception {
        byte[] plaintext = "integrity-protected-payload".getBytes(StandardCharsets.UTF_8);

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

        byte[] ciphertext = ConsumerSoapClient.decodeBase64(ciphertextBase64);
        // Flip a byte in the middle of the CMS structure to corrupt the authenticated content.
        ciphertext[ciphertext.length / 2] ^= 0x01;
        String tamperedBase64 = ConsumerSoapClient.base64(ciphertext);

        HttpResponse<String> dec = client.post(
                ConsumerNamespaces.EP_CRYPT,
                ConsumerEnvelopes.decryptDocument(
                        ConsumerSoapClient.CARD_HANDLE, "ECC", tamperedBase64),
                ConsumerNamespaces.ACTION_DECRYPT_DOCUMENT);

        // The decrypt must fail (fault); and under no circumstance may the plaintext appear.
        ConsumerSoapClient.assertSoapFault(dec);
        assertTrue(!dec.body().contains(ConsumerSoapClient.base64(plaintext)),
                "tampered decrypt must never return the original plaintext: " + dec.body());
    }
}
