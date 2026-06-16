package de.servicehealtherx.crypto.services;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.TrustService;
import de.servicehealtherx.crypto.ecies.ElcCryptogram;
import de.servicehealtherx.crypto.ecies.jce.SoftwareElcDecryptor;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import jakarta.enterprise.inject.Instance;
import org.bouncycastle.asn1.cms.AuthEnvelopedData;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.KeyTransRecipientInfo;
import org.bouncycastle.asn1.cms.RecipientInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Orchestration-level integration test for {@link EncryptionService}: trust verification, ECIES
 * delegation, and the {@code CryptoProvider} "ELC" decrypt seam (mocked at the infrastructure
 * boundary; the ECIES crypto itself is the real implementation).
 */
class EncryptionServiceEciesIT {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final KeyAlias alias = new KeyAlias("p12/test/enc");
    private final byte[] document = "confidential patient record".getBytes(StandardCharsets.UTF_8);

    private EncryptionService service;
    private KeyPair recipient;
    private X509Certificate recipientCert;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        recipient = generateEc("brainpoolP256r1");
        recipientCert = selfSigned(recipient, "egk-holder", "SHA256withECDSA");

        TrustService trustService = mock(TrustService.class);
        when(trustService.verify(any(X509Certificate.class), eq(false)))
                .thenReturn(new TrustService.VerificationResult(true, "ok"));

        CryptoProvider provider = mock(CryptoProvider.class);
        when(provider.getAvailability(alias)).thenReturn(KeyStoreAvailability.AVAILABLE);
        when(provider.decrypt(any(CryptoOperationRequest.class))).thenAnswer(inv -> {
            CryptoOperationRequest req = inv.getArgument(0);
            byte[] transportKey = new SoftwareElcDecryptor((ECPrivateKey) recipient.getPrivate())
                    .unwrapTransportKey(ElcCryptogram.parse(req.data));
            return new CryptoOperationResult(req.alias, transportKey, null, "ELC");
        });

        Instance<CryptoProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(i -> List.of(provider).iterator());

        service = new EncryptionService();
        service.trustService = trustService;
        service.auditLogger = mock(AuditLogger.class);
        service.cryptoProviders = providers;
    }

    @Test
    void encrypts_with_the_mandated_oid_and_round_trips() throws Exception {
        byte[] envelope = service.encryptDocument(new EncryptionService.EncryptRequest(
                alias, List.of(recipientCert), document, "application/octet-stream", "tester", true));

        AuthEnvelopedData aed = AuthEnvelopedData.getInstance(ContentInfo.getInstance(envelope).getContent());
        KeyTransRecipientInfo ktri = (KeyTransRecipientInfo)
                RecipientInfo.getInstance(aed.getRecipientInfos().getObjectAt(0)).getInfo();
        assertEquals("1.2.276.0.76.4.222", ktri.getKeyEncryptionAlgorithm().getAlgorithm().getId());

        byte[] recovered = service.decryptDocument(
                new EncryptionService.DecryptRequest(alias, envelope, "tester"));
        assertArrayEquals(document, recovered);
    }

    @Test
    void rejects_rsa_recipient() throws Exception {
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        rsaGen.initialize(2048);
        X509Certificate rsaCert = selfSigned(rsaGen.generateKeyPair(), "rsa", "SHA256withRSA");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.encryptDocument(
                new EncryptionService.EncryptRequest(
                        alias, List.of(rsaCert), document, "t", "tester", true)));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("unsuitable recipient"));
    }

    private static KeyPair generateEc(String curve) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(new ECGenParameterSpec(curve));
        return kpg.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair kp, String cn, String sigAlg) throws Exception {
        X500Name dn = new X500Name("CN=" + cn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(System.nanoTime()),
                Date.from(now), Date.from(now.plusSeconds(3600)), dn, kp.getPublic());
        X509CertificateHolder holder = builder.build(new JcaContentSignerBuilder(sigAlg)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(kp.getPrivate()));
        return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder);
    }
}
