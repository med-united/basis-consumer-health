package de.servicehealtherx.quarkus.mtls;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Registers the {@link MtlsCertManagementMBean} on the platform MBean server and backs it with the
 * {@link MtlsCertificateAuthority}.
 *
 * <p>On startup it ensures the PKI materials exist under {@code <certsDir>/mtls/} (the same
 * directory {@link MtlsBootstrap} uses) — so the CA is available for issuing certificates in every
 * profile, including {@code dev} and {@code @QuarkusTest}, regardless of whether the app was
 * launched via {@link MtlsMain}.
 *
 * <p>Follows the JMX registration pattern of
 * {@code de.servicehealtherx.crypto.p12.P12CertManagement}.
 */
@ApplicationScoped
@Startup
public class MtlsCertManagement implements MtlsCertManagementMBean {

    private static final Logger LOG = Logger.getLogger(MtlsCertManagement.class);
    private static final String OBJECT_NAME =
            "de.servicehealtherx:module=quarkus-server,name=MtlsCertManagement";

    @ConfigProperty(name = "quarkus.crypto.p12.certs-dir", defaultValue = MtlsBootstrap.DEFAULT_CERTS_DIR)
    String certsDir;

    @ConfigProperty(name = "mtls.keystore.password", defaultValue = MtlsBootstrap.DEFAULT_PASSWORD)
    String keystorePassword;

    @ConfigProperty(name = "mtls.server.cn", defaultValue = MtlsBootstrap.DEFAULT_SERVER_CN)
    String serverCn;

    private MtlsCertificateAuthority ca;

    @PostConstruct
    void init() {
        Path mtlsDir = Path.of(certsDir, MtlsBootstrap.SUBDIR);
        this.ca = MtlsBootstrap.ensureMaterials(mtlsDir, keystorePassword.toCharArray(), serverCn);
        registerMBean();
    }

    private void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (!server.isRegistered(name)) {
                server.registerMBean(this, name);
                LOG.infof("[JMX] registered %s", OBJECT_NAME);
            }
        } catch (Exception e) {
            LOG.errorf(e, "[JMX] failed to register %s", OBJECT_NAME);
        }
    }

    @PreDestroy
    void deregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (Exception e) {
            LOG.warnf(e, "[JMX] failed to deregister %s", OBJECT_NAME);
        }
    }

    @Override
    public String getRootCertificatePem() {
        return ca.getCaCertificatePem();
    }

    @Override
    public String signCsr(String csrPem, int validityDays) {
        return ca.signCsr(csrPem, validityDays(validityDays));
    }

    @Override
    public byte[] createP12(String subjectDn, String password, int validityDays) {
        requireSubject(subjectDn);
        requirePassword(password);
        return ca.createIdentityBytes(subjectDn, password.toCharArray(), validityDays(validityDays));
    }

    @Override
    public String createP12File(String subjectDn, String password, int validityDays) {
        requireSubject(subjectDn);
        requirePassword(password);
        byte[] p12 = ca.createIdentityBytes(subjectDn, password.toCharArray(), validityDays(validityDays));
        Path dir = Path.of(certsDir, MtlsBootstrap.SUBDIR, "issued");
        Path file = dir.resolve(safeFileName(subjectDn) + "-" + Instant.now().toEpochMilli() + ".p12");
        try {
            Files.createDirectories(dir);
            Files.write(file, p12);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write issued P12 to " + file, e);
        }
        LOG.infof("[mTLS] issued client P12 for '%s' -> %s", subjectDn, file);
        return file.toAbsolutePath().toString();
    }

    private static void requireSubject(String subjectDn) {
        if (subjectDn == null || subjectDn.isBlank()) {
            throw new IllegalArgumentException("subjectDn must not be blank (e.g. \"CN=client-1, O= ..., C=DE\")");
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty");
        }
    }

    private static int validityDays(int requested) {
        return requested > 0 ? requested : 365;
    }

    /** Reduce a DN to a filesystem-safe stem (no path traversal). */
    private static String safeFileName(String subjectDn) {
        String stem = subjectDn.replaceAll("(?i)^cn=", "").split(",")[0].trim();
        String safe = stem.replaceAll("[^a-zA-Z0-9\\-_.]", "-").replaceAll("-{2,}", "-");
        return safe.isBlank() ? "identity" : safe;
    }
}
