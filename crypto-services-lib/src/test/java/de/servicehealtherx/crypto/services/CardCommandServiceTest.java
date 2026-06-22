package de.servicehealtherx.crypto.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.servicehealtherx.crypto.CryptoProvider;
import jakarta.enterprise.inject.Instance;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.StringJoiner;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CardCommandServiceTest {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    static {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(new BouncyCastleProvider());
        }
    }

    private CryptoProvider provider;
    private CardCommandService service;
    private KeyPair poppKeyPair;
    private X509Certificate poppCert;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        provider = mock(CryptoProvider.class);
        when(provider.ownsCard("egk-1")).thenReturn(true);

        Instance<CryptoProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(i -> List.of(provider).iterator());

        service = new CardCommandService();
        service.cryptoProviders = providers;
        service.auditLogger = mock(AuditLogger.class);
        // No TrustService wired: the signer-trust check is skipped (signature is still enforced).
        Instance<de.servicehealtherx.crypto.TrustService> noTrust = mock(Instance.class);
        when(noTrust.isUnsatisfied()).thenReturn(true);
        service.trustService = noTrust;

        // ES256 signing identity standing in for the PoPP service.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        poppKeyPair = kpg.generateKeyPair();
        poppCert = selfSigned(poppKeyPair, "PoPP-Service");
    }

    @Test
    void secureSendApdu_verifies_signature_and_executes_all_steps_in_order() {
        byte[] resp1 = {(byte) 0x6F, 0x10, (byte) 0x90, 0x00};
        byte[] resp2 = {(byte) 0x90, 0x00};
        when(provider.transmitApdu(eq("egk-1"), any())).thenReturn(resp1, resp2);

        service.startCardSession("egk-1", "mandant-A");
        String jws = signedScenario("sess-1", 0, 1000,
                step("00a4040c", "9000"),
                step("00b0000000", "9000"));

        CardCommandService.ApduResponse result = service.secureSendApdu(jws);

        assertEquals(2, result.responseApdus().size());
        assertArrayEquals(resp1, result.responseApdus().get(0));
        assertArrayEquals(resp2, result.responseApdus().get(1));
        assertTrue(result.timeSpanMillis() >= 0);
    }

    @Test
    void secureSendApdu_rejects_tampered_signature() {
        when(provider.transmitApdu(eq("egk-1"), any())).thenReturn(new byte[] {(byte) 0x90, 0x00});
        service.startCardSession("egk-1", "mandant-A");

        String jws = signedScenario("sess-1", 0, 0, step("00a4040c", "9000"));
        // Flip a byte in the payload segment, leaving the signature unchanged.
        String[] parts = jws.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 1)
                + (parts[1].endsWith("A") ? "B" : "A");
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThrows(SecurityException.class, () -> service.secureSendApdu(tampered));
    }

    @Test
    void secureSendApdu_rejects_unexpected_status_word() {
        when(provider.transmitApdu(eq("egk-1"), any())).thenReturn(new byte[] {(byte) 0x6A, (byte) 0x82});
        service.startCardSession("egk-1", "mandant-A");

        String jws = signedScenario("sess-1", 0, 0, step("00a4040c", "9000"));

        assertThrows(SecurityException.class, () -> service.secureSendApdu(jws));
    }

    @Test
    void secureSendApdu_enforces_sequence_counter_against_replay() {
        when(provider.transmitApdu(eq("egk-1"), any())).thenReturn(new byte[] {(byte) 0x90, 0x00});
        service.startCardSession("egk-1", "mandant-A");

        // First scenario (counter 0) establishes the baseline; counter 1 is the valid successor.
        service.secureSendApdu(signedScenario("sess-1", 0, 1000, step("00a4040c", "9000")));
        service.secureSendApdu(signedScenario("sess-1", 1, 1000, step("00a4040c", "9000")));

        // Replaying counter 1 (expected 2) is rejected.
        assertThrows(SecurityException.class,
                () -> service.secureSendApdu(signedScenario("sess-1", 1, 1000, step("00a4040c", "9000"))));
    }

    @Test
    void secureSendApdu_without_open_session_fails() {
        assertThrows(IllegalStateException.class,
                () -> service.secureSendApdu(signedScenario("sess-1", 0, 0, step("00a4040c", "9000"))));
    }

    @Test
    void secureSendApdu_rejects_non_jws_input() {
        service.startCardSession("egk-1", "mandant-A");
        assertThrows(IllegalArgumentException.class, () -> service.secureSendApdu("not.a"));
        assertThrows(IllegalArgumentException.class, () -> service.secureSendApdu(""));
    }

    @Test
    void startCardSession_rejects_second_session_on_same_card() {
        service.startCardSession("egk-1", "mandant-A");
        assertThrows(IllegalStateException.class, () -> service.startCardSession("egk-1", "mandant-B"));
    }

    @Test
    void startCardSession_rejects_unknown_card_handle() {
        assertThrows(IllegalArgumentException.class, () -> service.startCardSession("ghost", "mandant-A"));
    }

    @Test
    void stopCardSession_releases_lock_and_closes_session() {
        String sessionId = service.startCardSession("egk-1", "mandant-A");
        service.stopCardSession(sessionId);

        assertTrue(service.openSessions().isEmpty());
        assertNotNull(service.startCardSession("egk-1", "mandant-A"));
    }

    @Test
    void stopCardSession_rejects_unknown_session() {
        assertThrows(IllegalArgumentException.class, () -> service.stopCardSession("not-a-session"));
    }

    // ── helpers: build a real ES256-signed PoPP ConnectorScenarioMessage ──────────────────────────

    private record Step(String commandApduHex, List<String> expectedStatusWords) {
    }

    private static Step step(String commandApduHex, String... expectedStatusWords) {
        return new Step(commandApduHex, List.of(expectedStatusWords));
    }

    private String signedScenario(String clientSessionId, int sequenceCounter, int timeSpan, Step... steps) {
        try {
            String x5c = Base64.getEncoder().encodeToString(poppCert.getEncoded());
            String header = B64URL.encodeToString(
                    ("{\"typ\":\"JWT\",\"alg\":\"ES256\",\"x5c\":[\"" + x5c + "\"],\"stpl\":\"AA==\"}")
                            .getBytes(StandardCharsets.UTF_8));
            String payload = B64URL.encodeToString(
                    payloadJson(clientSessionId, sequenceCounter, timeSpan, steps).getBytes(StandardCharsets.UTF_8));

            byte[] signingInput = (header + "." + payload).getBytes(StandardCharsets.US_ASCII);
            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initSign(poppKeyPair.getPrivate());
            ecdsa.update(signingInput);
            String signature = B64URL.encodeToString(derToRaw(ecdsa.sign()));
            return header + "." + payload + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String payloadJson(String clientSessionId, int sequenceCounter, int timeSpan, Step... steps) {
        StringJoiner stepsJson = new StringJoiner(",", "[", "]");
        for (Step s : steps) {
            StringJoiner sw = new StringJoiner(",", "[", "]");
            s.expectedStatusWords().forEach(w -> sw.add("\"" + w + "\""));
            stepsJson.add("{\"commandApdu\":\"" + s.commandApduHex() + "\",\"expectedStatusWords\":" + sw + "}");
        }
        return "{\"message\":{\"type\":\"StandardScenario\",\"version\":\"1.0.0\","
                + "\"clientSessionId\":\"" + clientSessionId + "\","
                + "\"sequenceCounter\":" + sequenceCounter + ",\"timeSpan\":" + timeSpan + ","
                + "\"steps\":" + stepsJson + "}}";
    }

    /** Convert a DER-encoded {@code SEQUENCE{INTEGER r, INTEGER s}} signature to JOSE {@code R||S}. */
    private static byte[] derToRaw(byte[] der) {
        ASN1Sequence seq = ASN1Sequence.getInstance(der);
        byte[] r = toFixed(((ASN1Integer) seq.getObjectAt(0)).getPositiveValue().toByteArray());
        byte[] s = toFixed(((ASN1Integer) seq.getObjectAt(1)).getPositiveValue().toByteArray());
        byte[] raw = new byte[64];
        System.arraycopy(r, 0, raw, 0, 32);
        System.arraycopy(s, 0, raw, 32, 32);
        return raw;
    }

    private static byte[] toFixed(byte[] value) {
        byte[] out = new byte[32];
        if (value.length >= 32) {
            System.arraycopy(value, value.length - 32, out, 0, 32);
        } else {
            System.arraycopy(value, 0, out, 32 - value.length, value.length);
        }
        return out;
    }

    private static X509Certificate selfSigned(KeyPair kp, String cn) throws Exception {
        X500Name dn = new X500Name("CN=" + cn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(System.nanoTime()),
                Date.from(now), Date.from(now.plusSeconds(3600)), dn, kp.getPublic());
        X509CertificateHolder holder = builder.build(new JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(kp.getPrivate()));
        return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder);
    }
}
