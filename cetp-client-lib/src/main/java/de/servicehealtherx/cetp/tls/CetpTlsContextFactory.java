package de.servicehealtherx.cetp.tls;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Optional;

/**
 * Builds the TLS client context for CETP delivery (gemSpec_Kon TIP1-A_4595, TIP1-A_5009,
 * A_21760-02). The konnektor acts as TLS client: it authenticates with the SmkCSAKAut / C.AK.AUT
 * identity (when a {@link KonnektorClientIdentity} {@link KeyManagerFactory} is available and the
 * peer requests it) and validates the client system's server certificate with the <b>PKIX</b>
 * path-validation algorithm against a configured client-system trust store.
 *
 * <p>This deliberately validates against an operator-managed client-system trust store via plain
 * PKIX — not the TI trust space (gemLibPki TUC_PKI_018 is not used here).
 */
@ApplicationScoped
public class CetpTlsContextFactory {

    private static final Logger LOG = Logger.getLogger(CetpTlsContextFactory.class);

    @Inject
    @KonnektorClientIdentity
    Instance<KeyManagerFactory> clientIdentity;

    @ConfigProperty(name = "cetp.tls.truststore.path")
    Optional<String> trustStorePath;

    @ConfigProperty(name = "cetp.tls.truststore.password")
    Optional<String> trustStorePassword;

    private volatile SSLContext sslContext;

    public SSLSocketFactory socketFactory() {
        return context().getSocketFactory();
    }

    /**
     * Build a factory backed by an explicit client-system trust store (no CDI). Used for tests and
     * for programmatic wiring; the trust store is loaded from the classpath or the filesystem.
     */
    public static CetpTlsContextFactory withTrustStore(String trustStorePath, String trustStorePassword) {
        CetpTlsContextFactory factory = new CetpTlsContextFactory();
        factory.trustStorePath = Optional.ofNullable(trustStorePath);
        factory.trustStorePassword = Optional.ofNullable(trustStorePassword);
        return factory;
    }

    SSLContext context() {
        SSLContext local = sslContext;
        if (local == null) {
            synchronized (this) {
                if (sslContext == null) {
                    sslContext = build();
                }
                local = sslContext;
            }
        }
        return local;
    }

    private SSLContext build() {
        try {
            KeyManager[] keyManagers = (clientIdentity != null && clientIdentity.isResolvable())
                    ? clientIdentity.get().getKeyManagers()
                    : null; // server-authentication only (valid CETP variant)
            if (keyManagers == null) {
                LOG.info("No KonnektorClientIdentity available — CETP TLS uses server authentication only");
            }

            // PKIX trust over the configured client-system trust store (TIP1-A_5009).
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init(loadTrustStore());

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build CETP TLS context", e);
        }
    }

    /** The configured client-system trust store, or {@code null} to fall back to the JDK default trust. */
    private KeyStore loadTrustStore() throws Exception {
        if (trustStorePath.isEmpty() || trustStorePath.get().isBlank()) {
            return null;
        }
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = openTrustStore(trustStorePath.get())) {
            trustStore.load(in, trustStorePassword.orElse("").toCharArray());
        }
        return trustStore;
    }

    private static InputStream openTrustStore(String location) throws Exception {
        InputStream classpath = CetpTlsContextFactory.class.getResourceAsStream(location);
        if (classpath != null) {
            return classpath;
        }
        return Files.newInputStream(Path.of(location));
    }
}
