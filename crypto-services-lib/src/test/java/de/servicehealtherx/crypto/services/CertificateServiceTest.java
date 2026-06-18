package de.servicehealtherx.crypto.services;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.TrustService;
import jakarta.enterprise.inject.Instance;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Orchestration-level unit test for {@link CertificateService}: provider selection and DER read for
 * {@code readCertificate} (gemSpec_Kon ReadCardCertificate), and trust mapping for
 * {@code verifyCertificate} (TUC_KON_037). Infrastructure boundaries (CryptoProvider, TrustService)
 * are mocked; the certificate is a real X.509.
 */
class CertificateServiceTest {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final KeyAlias alias = new KeyAlias("p12/test/aut");

    private CertificateService service;
    private TrustService trustService;
    private CryptoProvider provider;
    private X509Certificate cert;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        cert = selfSigned(generateEc(), "test-holder");

        trustService = mock(TrustService.class);
        provider = mock(CryptoProvider.class);
        when(provider.getAvailability(alias)).thenReturn(KeyStoreAvailability.AVAILABLE);

        Instance<CryptoProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(i -> List.of(provider).iterator());

        service = new CertificateService();
        service.trustService = trustService;
        service.auditLogger = mock(AuditLogger.class);
        service.cryptoProviders = providers;
    }

    @Test
    void readCertificate_returns_der_from_resolved_provider() throws Exception {
        when(provider.readCertificate(eq(alias), eq("C.AUT"), eq("ECC"))).thenReturn(cert);

        byte[] der = service.readCertificate(new CertificateService.ReadCertRequest(
                alias, CertificateService.CertRef.C_AUT, CertificateService.CryptAlgorithm.ECC, "tester"));

        assertArrayEquals(cert.getEncoded(), der);
    }

    @Test
    void readCertificate_maps_osig_to_c_sig() {
        when(provider.readCertificate(eq(alias), eq("C.SIG"), eq("RSA"))).thenReturn(cert);

        service.readCertificate(new CertificateService.ReadCertRequest(
                alias, CertificateService.CertRef.C_OSIG, CertificateService.CryptAlgorithm.RSA, "tester"));

        org.mockito.Mockito.verify(provider).readCertificate(alias, "C.SIG", "RSA");
    }

    @Test
    void readCertificate_fails_when_no_provider_available() {
        when(provider.getAvailability(alias)).thenReturn(KeyStoreAvailability.UNAVAILABLE);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.readCertificate(
                new CertificateService.ReadCertRequest(
                        alias, CertificateService.CertRef.C_AUT, CertificateService.CryptAlgorithm.ECC, "tester")));
        assertTrue(ex.getMessage().contains("No available key source"));
    }

    @Test
    void verifyCertificate_maps_valid_result() {
        when(trustService.verify(any(X509Certificate.class), eq(false)))
                .thenReturn(new TrustService.VerificationResult(true, "ok"));

        CertificateService.VerifyCertResult result = service.verifyCertificate(cert, "tester");

        assertEquals("VALID", result.result());
        assertEquals("ok", result.detail());
    }

    @Test
    void verifyCertificate_maps_invalid_result() {
        when(trustService.verify(any(X509Certificate.class), eq(false)))
                .thenReturn(new TrustService.VerificationResult(false, "expired"));

        CertificateService.VerifyCertResult result = service.verifyCertificate(cert, "tester");

        assertEquals("INVALID", result.result());
    }

    @Test
    void verifyCertificate_returns_inconclusive_on_error() {
        when(trustService.verify(any(X509Certificate.class), eq(false)))
                .thenThrow(new RuntimeException("trust store down"));

        CertificateService.VerifyCertResult result = service.verifyCertificate(cert, "tester");

        assertEquals("INCONCLUSIVE", result.result());
        assertTrue(result.roles().isEmpty());
    }

    private static KeyPair generateEc() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(new java.security.spec.ECGenParameterSpec("brainpoolP256r1"));
        return kpg.generateKeyPair();
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
