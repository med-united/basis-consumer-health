package de.servicehealtherx.quarkus.mtls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-JUnit tests for the self-managed mTLS Certificate Authority (no Quarkus container). */
class MtlsCertificateAuthorityTest {

    private static final char[] PW = "test-pass".toCharArray();
    private static final String CA_DN = "CN=Test Root CA, O=ServiceHealtheRX, C=DE";

    @Test
    void generatesSelfSignedRootCa() throws Exception {
        MtlsCertificateAuthority ca = MtlsCertificateAuthority.generate(CA_DN);
        X509Certificate root = ca.getCaCertificate();

        // Self-signed: issuer == subject, and it verifies under its own public key.
        assertEquals(root.getSubjectX500Principal(), root.getIssuerX500Principal());
        assertDoesNotThrow(() -> root.verify(root.getPublicKey()));
        // CA:TRUE (basicConstraints pathLen >= 0 => -1 means "no limit but is a CA").
        assertTrue(root.getBasicConstraints() >= 0 || root.getBasicConstraints() == Integer.MAX_VALUE,
                "root certificate must be a CA");
        // keyCertSign bit (index 5) set.
        assertTrue(root.getKeyUsage()[5], "root CA must assert keyCertSign");
    }

    @Test
    void loadOrCreatePersistsAndReloadsSameCa(@TempDir Path dir) throws Exception {
        Path caP12 = dir.resolve("ca.p12");

        MtlsCertificateAuthority created = MtlsCertificateAuthority.loadOrCreate(caP12, PW, CA_DN);
        assertTrue(Files.exists(caP12), "ca.p12 should be written on first use");
        byte[] firstBytes = Files.readAllBytes(caP12);

        MtlsCertificateAuthority reloaded = MtlsCertificateAuthority.loadOrCreate(caP12, PW, CA_DN);
        // Same certificate (not regenerated) and the file is untouched.
        assertArrayEquals(created.getCaCertificate().getEncoded(), reloaded.getCaCertificate().getEncoded());
        assertArrayEquals(firstBytes, Files.readAllBytes(caP12));
    }

    @Test
    void signsCsrWithCaAndChainValidates() throws Exception {
        MtlsCertificateAuthority ca = MtlsCertificateAuthority.generate(CA_DN);
        String csrPem = newCsrPem("CN=client-system-1, O=ServiceHealtheRX, C=DE");

        String leafPem = ca.signCsr(csrPem, 30);
        X509Certificate leaf = parseCert(leafPem);

        // Issued by our CA and signature verifies under the CA public key.
        assertEquals(ca.getCaCertificate().getSubjectX500Principal(), leaf.getIssuerX500Principal());
        assertDoesNotThrow(() -> leaf.verify(ca.getCaCertificate().getPublicKey()));
        // Leaf is not a CA and carries clientAuth EKU.
        assertEquals(-1, leaf.getBasicConstraints(), "leaf must not be a CA");
        assertTrue(leaf.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2"), "leaf must allow clientAuth");
    }

    @Test
    void rejectsNonCsrInput() {
        MtlsCertificateAuthority ca = MtlsCertificateAuthority.generate(CA_DN);
        assertThrows(IllegalArgumentException.class, () -> ca.signCsr("not a csr", 30));
        assertThrows(IllegalArgumentException.class, () -> ca.signCsr("", 30));
    }

    @Test
    void createsLoadableIdentityP12WithChain() throws Exception {
        MtlsCertificateAuthority ca = MtlsCertificateAuthority.generate(CA_DN);
        char[] p12Pw = "p12-secret".toCharArray();

        byte[] p12 = ca.createIdentityBytes("CN=client-2, O=ServiceHealtheRX, C=DE", p12Pw, 60);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new java.io.ByteArrayInputStream(p12), p12Pw);

        assertTrue(ks.isKeyEntry(MtlsCertificateAuthority.IDENTITY_ALIAS));
        assertNotNull(ks.getKey(MtlsCertificateAuthority.IDENTITY_ALIAS, p12Pw), "private key present");
        java.security.cert.Certificate[] chain = ks.getCertificateChain(MtlsCertificateAuthority.IDENTITY_ALIAS);
        assertEquals(2, chain.length, "chain is [leaf, CA]");

        X509Certificate leaf = (X509Certificate) chain[0];
        X509Certificate root = (X509Certificate) chain[1];
        assertDoesNotThrow(() -> leaf.verify(root.getPublicKey()), "leaf signed by CA in chain");
        assertArrayEquals(ca.getCaCertificate().getEncoded(), root.getEncoded());
    }

    @Test
    void exportsTrustStoreWithOnlyCaCertificate() throws Exception {
        MtlsCertificateAuthority ca = MtlsCertificateAuthority.generate(CA_DN);
        KeyStore ts = ca.toTrustStore();

        List<String> aliases = java.util.Collections.list(ts.aliases());
        assertEquals(1, aliases.size());
        assertTrue(ts.isCertificateEntry(MtlsCertificateAuthority.TRUST_ALIAS));
        assertArrayEquals(ca.getCaCertificate().getEncoded(),
                ts.getCertificate(MtlsCertificateAuthority.TRUST_ALIAS).getEncoded());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

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
        // sanity: the PEM round-trips back to a CSR
        assertNotNull(new org.bouncycastle.openssl.PEMParser(new StringReader(sw.toString())).readObject());
        return sw.toString();
    }

    private static X509Certificate parseCert(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(pem.getBytes()));
    }
}
