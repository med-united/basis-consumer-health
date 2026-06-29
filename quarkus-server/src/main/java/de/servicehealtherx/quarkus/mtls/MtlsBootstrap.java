package de.servicehealtherx.quarkus.mtls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.logging.Logger;

/**
 * Generates the mTLS PKI materials on disk <em>before</em> Quarkus boots, so the HTTPS connector
 * configured in {@code application.properties} (TLS registry bucket {@code mtls}) finds its key
 * store and trust store when it binds.
 *
 * <p>Three PKCS#12 files are produced under {@code <certsDir>/mtls/}, each created only if missing
 * (so operator-supplied material and the CA survive restarts):
 * <ul>
 *   <li>{@code ca.p12} — the self-signed root CA (key + certificate); the signing authority used by
 *       the {@link MtlsCertManagement} JMX bean to issue client certificates.</li>
 *   <li>{@code server.p12} — the server's TLS identity, a leaf certificate issued by the root CA.</li>
 *   <li>{@code truststore.p12} — a one-entry trust store holding the CA certificate, against which
 *       presented client certificates are validated.</li>
 * </ul>
 *
 * <p>Because this runs before the Quarkus configuration system is available (it is invoked from
 * {@link MtlsMain#main}), it reads its few settings from environment variables with sane defaults
 * that mirror {@code application.properties}:
 * <ul>
 *   <li>{@code MTLS_CERTS_DIR} (default {@code certs}) — must match {@code quarkus.crypto.p12.certs-dir}</li>
 *   <li>{@code MTLS_KEYSTORE_PASSWORD} (default {@code changeit}) — must match the TLS bucket password</li>
 *   <li>{@code MTLS_SERVER_CN} (default {@code localhost}) — CN/SAN of the server TLS certificate</li>
 * </ul>
 */
public final class MtlsBootstrap {

    private static final Logger LOG = Logger.getLogger(MtlsBootstrap.class.getName());

    static final String SUBDIR = "mtls";
    static final String CA_FILE = "ca.p12";
    static final String SERVER_FILE = "server.p12";
    static final String TRUSTSTORE_FILE = "truststore.p12";

    static final String DEFAULT_CERTS_DIR = "certs";
    static final String DEFAULT_PASSWORD = "changeit";
    static final String DEFAULT_SERVER_CN = "localhost";
    static final String CA_DN = "CN=Basis Consumer Health mTLS Root CA, O=ServiceHealtheRX, C=DE";

    private static final int SERVER_VALIDITY_DAYS = 825; // CA/Browser-forum cap for TLS server certs

    private MtlsBootstrap() {
    }

    /** Ensure ca/server/truststore PKCS#12 exist under {@code <MTLS_CERTS_DIR>/mtls/}. */
    public static void ensureMaterials() {
        Path mtlsDir = Path.of(env("MTLS_CERTS_DIR", DEFAULT_CERTS_DIR), SUBDIR);
        char[] password = env("MTLS_KEYSTORE_PASSWORD", DEFAULT_PASSWORD).toCharArray();
        String serverCn = env("MTLS_SERVER_CN", DEFAULT_SERVER_CN);
        ensureMaterials(mtlsDir, password, serverCn);
    }

    /**
     * Ensure the PKI materials exist in {@code mtlsDir}. Idempotent: existing files are left intact.
     *
     * @return the loaded or freshly created root CA (handy for callers/tests)
     */
    public static MtlsCertificateAuthority ensureMaterials(Path mtlsDir, char[] password, String serverCn) {
        try {
            Files.createDirectories(mtlsDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create mTLS directory " + mtlsDir, e);
        }

        Path caP12 = mtlsDir.resolve(CA_FILE);
        Path serverP12 = mtlsDir.resolve(SERVER_FILE);
        Path trustP12 = mtlsDir.resolve(TRUSTSTORE_FILE);

        boolean caExisted = Files.exists(caP12);
        MtlsCertificateAuthority ca = MtlsCertificateAuthority.loadOrCreate(caP12, password, CA_DN);
        if (!caExisted) {
            LOG.info("[mTLS] generated self-signed root CA -> " + caP12);
        }

        if (!Files.exists(trustP12)) {
            ca.writeTrustStore(trustP12, password);
            LOG.info("[mTLS] wrote CA trust store -> " + trustP12);
        }

        if (!Files.exists(serverP12)) {
            KeyStore serverKs = ca.createIdentity(
                    "CN=" + serverCn + ", O=ServiceHealtheRX, C=DE", password, SERVER_VALIDITY_DAYS);
            store(serverKs, serverP12, password);
            LOG.info("[mTLS] issued server TLS certificate (CN=" + serverCn + ") -> " + serverP12);
        }
        return ca;
    }

    private static void store(KeyStore ks, Path path, char[] password) {
        try (var out = Files.newOutputStream(path)) {
            ks.store(out, password);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write keystore " + path, e);
        }
    }

    private static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }
}
