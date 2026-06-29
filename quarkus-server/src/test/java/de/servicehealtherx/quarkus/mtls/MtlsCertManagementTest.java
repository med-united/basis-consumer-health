package de.servicehealtherx.quarkus.mtls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.management.ObjectName;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link MtlsCertManagement} (JMX registration + CA-backed operations) against the real CA,
 * without booting Quarkus — mirroring {@code CxfJmxPerformanceFeatureTest} in this module, which
 * stays Quarkus-free because the full app build is not available in every environment.
 */
class MtlsCertManagementTest {

    private static final ObjectName OBJECT_NAME = objectName();

    @TempDir
    Path certsDir;

    private MtlsCertManagement bean;

    @BeforeEach
    void setUp() {
        bean = new MtlsCertManagement();
        bean.certsDir = certsDir.toString();
        bean.keystorePassword = "changeit";
        bean.serverCn = "localhost";
        bean.init();
    }

    @AfterEach
    void tearDown() {
        bean.deregisterMBean();
    }

    @Test
    void initBootstrapsPkiMaterialsAndRegistersMBean() throws Exception {
        Path mtls = certsDir.resolve("mtls");
        assertTrue(Files.exists(mtls.resolve("ca.p12")), "ca.p12 generated");
        assertTrue(Files.exists(mtls.resolve("server.p12")), "server.p12 generated");
        assertTrue(Files.exists(mtls.resolve("truststore.p12")), "truststore.p12 generated");
        assertTrue(ManagementFactory.getPlatformMBeanServer().isRegistered(OBJECT_NAME),
                "MtlsCertManagement MBean must be registered");
    }

    @Test
    void exposesRootCertificateAsPem() {
        assertTrue(bean.getRootCertificatePem().contains("BEGIN CERTIFICATE"));
    }

    @Test
    void signsCsrSoItChainsToTheRootCa() throws Exception {
        String leafPem = bean.signCsr(newCsrPem("CN=ws-client, O=ServiceHealtheRX, C=DE"), 30);
        X509Certificate leaf = parseCert(leafPem);
        X509Certificate root = parseCert(bean.getRootCertificatePem());
        assertDoesNotThrow(() -> leaf.verify(root.getPublicKey()), "issued cert chains to CA");
    }

    @Test
    void createsLoadableP12Identity() throws Exception {
        char[] pw = "p12pw".toCharArray();
        byte[] p12 = bean.createP12("CN=ws-client-2, O=ServiceHealtheRX, C=DE", "p12pw", 30);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(p12), pw);
        assertNotNull(ks.getKey(MtlsCertificateAuthority.IDENTITY_ALIAS, pw));
    }

    @Test
    void createsP12FileUnderIssuedDirectory() {
        String path = bean.createP12File("CN=ws-client-3, O=ServiceHealtheRX, C=DE", "p12pw", 30);
        assertTrue(Files.exists(Path.of(path)), "issued P12 file written: " + path);
        assertTrue(path.contains("issued"), "written under the issued/ directory");
    }

    @Test
    void rejectsBlankArguments() {
        assertThrows(IllegalArgumentException.class, () -> bean.createP12("", "pw", 30));
        assertThrows(IllegalArgumentException.class, () -> bean.createP12("CN=x", "", 30));
        assertThrows(IllegalArgumentException.class, () -> bean.createP12File("  ", "pw", 30));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static ObjectName objectName() {
        try {
            return new ObjectName("de.servicehealtherx:module=quarkus-server,name=MtlsCertManagement");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate parseCert(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes()));
    }

    private static String newCsrPem(String subjectDn) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(kp.getPrivate());
        PKCS10CertificationRequest csr =
                new JcaPKCS10CertificationRequestBuilder(new X500Name(subjectDn), kp.getPublic()).build(signer);
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(csr);
        }
        return sw.toString();
    }
}
