package de.servicehealtherx.cetp.delivery;

import de.servicehealtherx.cetp.testsupport.FakeEventSink;
import de.servicehealtherx.cetp.tls.CetpTlsContextFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mTLS delivery (User Story 4): server-cert PKIX validation against the client-system trust store
 * (TIP1-A_5009), plain TCP fallback (FR-027), and failed-attempt counting on handshake failure
 * (FR-028). Keystores are generated at test time with {@code keytool} (no secrets committed).
 */
class CetpMtlsIT {

    private static final byte[] BODY = "<Event/>".getBytes(StandardCharsets.UTF_8);
    private static final String PASS = "changeit";

    @TempDir
    static Path certDir;

    private static Path serverKs;
    private static Path untrustedKs;
    private static Path trustStore;

    @BeforeAll
    static void generateKeystores() throws Exception {
        serverKs = certDir.resolve("server.p12");
        untrustedKs = certDir.resolve("untrusted.p12");
        trustStore = certDir.resolve("truststore.p12");

        // Trusted server identity (CN/SAN 127.0.0.1) + a separate untrusted identity.
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1",
                "-keystore", serverKs.toString(), "-storetype", "PKCS12",
                "-storepass", PASS, "-keypass", PASS);
        keytool("-genkeypair", "-alias", "untrusted", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1",
                "-keystore", untrustedKs.toString(), "-storetype", "PKCS12",
                "-storepass", PASS, "-keypass", PASS);

        // Trust store that trusts only the server identity.
        Path serverCert = certDir.resolve("server.crt");
        keytool("-exportcert", "-alias", "server", "-keystore", serverKs.toString(),
                "-storepass", PASS, "-rfc", "-file", serverCert.toString());
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", serverCert.toString(),
                "-keystore", trustStore.toString(), "-storetype", "PKCS12", "-storepass", PASS);
    }

    private static void keytool(String... args) throws Exception {
        String keytoolBin = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(keytoolBin);
        cmd.addAll(java.util.Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }

    /** Builds a server-side SSLContext from a generated keystore (server identity for the fake sink). */
    private static SSLContext serverContext(Path keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, PASS.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** A sender configured for TLS against the generated trust store. */
    private static CetpEventSender tlsSender() {
        CetpEventSender sender = new CetpEventSender();
        sender.timeoutMs = 4000;
        sender.tlsMandatory = true;
        sender.tlsContextFactory = CetpTlsContextFactory.withTrustStore(trustStore.toString(), PASS);
        return sender;
    }

    @Test
    void test_TIP1_A_5009_delivery_over_tls_to_trusted_sink_succeeds() throws Exception {
        try (FakeEventSink sink = FakeEventSink.tls(serverContext(serverKs), false)) {
            CetpEventSender sender = tlsSender();

            boolean ok = sender.send(sink.eventTo(), BODY).get(8, TimeUnit.SECONDS);

            assertTrue(ok, "delivery to a trusted TLS sink must succeed");
            byte[] frame = sink.awaitFrame(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertTrue(new String(frame, StandardCharsets.UTF_8).startsWith("CETP"));
        }
    }

    @Test
    void test_TIP1_A_5009_untrusted_server_cert_refused_via_pkix() throws Exception {
        // Server presents a certificate that does NOT chain to the trust store → PKIX rejects it.
        try (FakeEventSink sink = FakeEventSink.tls(serverContext(untrustedKs), false)) {
            CetpEventSender sender = tlsSender();

            boolean ok = sender.send(sink.eventTo(), BODY).get(8, TimeUnit.SECONDS);

            assertFalse(ok, "delivery to an untrusted TLS sink must be refused (FR-028 failed attempt)");
        }
    }

    @Test
    void test_FR_027_plain_tcp_when_tls_not_mandatory() throws Exception {
        try (FakeEventSink sink = FakeEventSink.plain()) {
            CetpEventSender sender = new CetpEventSender();
            sender.timeoutMs = 4000;
            sender.tlsMandatory = false;
            sender.tlsContextFactory = CetpTlsContextFactory.withTrustStore(null, "");

            boolean ok = sender.send(sink.eventTo(), BODY).get(5, TimeUnit.SECONDS);

            assertTrue(ok, "plain-TCP delivery must succeed when TLS is not mandatory");
            assertNotNull(sink.awaitFrame(5, TimeUnit.SECONDS));
        }
    }
}
