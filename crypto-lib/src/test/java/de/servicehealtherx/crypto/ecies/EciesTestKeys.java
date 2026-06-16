package de.servicehealtherx.crypto.ecies;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;

/** Test fixtures for ECIES tests: brainpool/RSA key pairs and self-signed certs via Bouncy Castle. */
public final class EciesTestKeys {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private EciesTestKeys() {
    }

    public static KeyPair generate(String curveName) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            kpg.initialize(new ECGenParameterSpec(curveName));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate " + curveName + " key pair", e);
        }
    }

    public static KeyPair brainpoolP256r1() {
        return generate("brainpoolP256r1");
    }

    public static KeyPair rsa2048() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }

    public static X509Certificate selfSigned(KeyPair keyPair, String commonName) {
        try {
            X500Name dn = new X500Name("CN=" + commonName);
            Instant now = Instant.now();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    dn,
                    BigInteger.valueOf(System.nanoTime()),
                    Date.from(now),
                    Date.from(now.plusSeconds(3600)),
                    dn,
                    keyPair.getPublic());
            String sigAlg = keyPair.getPublic().getAlgorithm().startsWith("RSA")
                    ? "SHA256withRSA" : "SHA256withECDSA";
            ContentSigner signer = new JcaContentSignerBuilder(sigAlg)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(keyPair.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build self-signed certificate", e);
        }
    }
}
