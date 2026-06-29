package de.servicehealtherx.quarkus.mtls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * A small, self-contained X.509 Certificate Authority used to bootstrap mutual-TLS for the SOAP
 * webservices. It owns a <em>self-signed root CA</em> (key pair + certificate) and can:
 *
 * <ul>
 *   <li>generate that root CA on first use and persist it as a PKCS#12 keystore
 *       ({@link #loadOrCreate}),</li>
 *   <li>issue a leaf certificate for a client/server from a PKCS#10 CSR ({@link #signCsr}),</li>
 *   <li>mint a complete identity — fresh key pair + CA-signed certificate — as a PKCS#12 keystore
 *       ({@link #createIdentity}),</li>
 *   <li>export the CA certificate as a one-entry trust store so the TLS layer trusts every
 *       certificate this CA issues ({@link #toTrustStore}).</li>
 * </ul>
 *
 * <p>This is intentionally a single, dependency-light class (no CDI) so it can run <em>before</em>
 * Quarkus boots (see {@link MtlsBootstrap}) as well as from the {@link MtlsCertManagement} JMX bean.
 * All certificates use RSA-2048 / SHA-256 for broad client compatibility.
 */
public final class MtlsCertificateAuthority {

    /** Keystore entry alias under which the root CA key + certificate are stored. */
    public static final String CA_ALIAS = "mtls-root-ca";
    /** Keystore entry alias under which a minted leaf identity's key + chain are stored. */
    public static final String IDENTITY_ALIAS = "mtls-identity";
    /** Trust-store entry alias for the exported CA certificate. */
    public static final String TRUST_ALIAS = "mtls-root-ca";

    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String KEYSTORE_TYPE = "PKCS12";

    static {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final PrivateKey caPrivateKey;
    private final X509Certificate caCertificate;
    private final X500Name caSubject;
    private final SecureRandom random = new SecureRandom();

    private MtlsCertificateAuthority(PrivateKey caPrivateKey, X509Certificate caCertificate) {
        this.caPrivateKey = caPrivateKey;
        this.caCertificate = caCertificate;
        try {
            // The issuer of every leaf must DER-match the CA's subject exactly, or PKCS#12 chain
            // validation rejects it — so take the encoded subject, never a re-parsed RFC2253 string.
            this.caSubject = new org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(caCertificate).getSubject();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read CA subject", e);
        }
    }

    /**
     * Load the root CA from {@code caKeystore}, or — if the file does not yet exist — generate a new
     * self-signed root CA, write it to {@code caKeystore} (PKCS#12) and return it. The directory is
     * created if necessary; the keystore is written with owner-only permissions where supported.
     *
     * @param caKeystore path to {@code ca.p12}
     * @param password   keystore + key password
     * @param caDn       distinguished name for a freshly generated CA, e.g.
     *                   {@code "CN=Basis Consumer Health mTLS Root CA, O=ServiceHealtheRX, C=DE"}
     */
    public static MtlsCertificateAuthority loadOrCreate(Path caKeystore, char[] password, String caDn) {
        try {
            if (Files.exists(caKeystore)) {
                return load(caKeystore, password);
            }
            MtlsCertificateAuthority ca = generate(caDn);
            ca.persist(caKeystore, password);
            return ca;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load or create the mTLS root CA at " + caKeystore, e);
        }
    }

    private static MtlsCertificateAuthority load(Path caKeystore, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        try (var in = Files.newInputStream(caKeystore)) {
            ks.load(in, password);
        }
        PrivateKey key = (PrivateKey) ks.getKey(CA_ALIAS, password);
        X509Certificate cert = (X509Certificate) ks.getCertificate(CA_ALIAS);
        if (key == null || cert == null) {
            throw new IllegalStateException("Keystore " + caKeystore + " has no '" + CA_ALIAS + "' CA entry");
        }
        return new MtlsCertificateAuthority(key, cert);
    }

    /** Generate a fresh self-signed root CA (CA:TRUE, keyCertSign|cRLSign), valid for 10 years. */
    public static MtlsCertificateAuthority generate(String caDn) {
        try {
            KeyPair caKeyPair = newKeyPair();
            X500Name dn = new X500Name(caDn);
            Instant now = Instant.now();
            BigInteger serial = new BigInteger(159, new SecureRandom());

            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                            dn,
                            serial,
                            Date.from(now.minus(1, ChronoUnit.DAYS)),
                            Date.from(now.plus(3650, ChronoUnit.DAYS)),
                            dn,
                            caKeyPair.getPublic())
                    .addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
                    .addExtension(Extension.keyUsage, true,
                            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
                    .addExtension(Extension.subjectKeyIdentifier, false,
                            extUtils.createSubjectKeyIdentifier(caKeyPair.getPublic()));

            X509Certificate caCert = sign(builder, caKeyPair.getPrivate());
            return new MtlsCertificateAuthority(caKeyPair.getPrivate(), caCert);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the mTLS root CA", e);
        }
    }

    /** @return the root CA certificate (public material only). */
    public X509Certificate getCaCertificate() {
        return caCertificate;
    }

    /**
     * Issue a leaf certificate signed by the root CA from a PEM-encoded PKCS#10 CSR. The CSR's
     * subject and public key are honoured; the issued certificate carries client+server
     * {@code ExtendedKeyUsage} so it works for mTLS in either direction.
     *
     * @param csrPem       PEM CSR ({@code -----BEGIN CERTIFICATE REQUEST-----})
     * @param validityDays validity period in days from now
     * @return the issued certificate, PEM-encoded
     */
    public String signCsr(String csrPem, int validityDays) {
        try {
            PKCS10CertificationRequest csr = parseCsr(csrPem);
            JcaPKCS10CertificationRequest jcaCsr =
                    new JcaPKCS10CertificationRequest(csr).setProvider(BouncyCastleProvider.PROVIDER_NAME);
            PublicKey csrPublicKey = jcaCsr.getPublicKey();
            X509Certificate leaf = issue(csr.getSubject(), csrPublicKey, validityDays);
            return toPem(leaf);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not sign CSR: " + e.getMessage(), e);
        }
    }

    /**
     * Mint a complete identity: generate a fresh RSA key pair, issue a CA-signed leaf certificate
     * for {@code subjectDn}, and assemble a PKCS#12 keystore holding the private key and the
     * {leaf, CA} certificate chain.
     *
     * @param subjectDn    leaf subject DN, e.g. {@code "CN=client-system-1, O=ServiceHealtheRX, C=DE"}
     * @param password     keystore + key password for the produced PKCS#12
     * @param validityDays validity period in days from now
     */
    public KeyStore createIdentity(String subjectDn, char[] password, int validityDays) {
        try {
            KeyPair keyPair = newKeyPair();
            X509Certificate leaf = issue(new X500Name(subjectDn), keyPair.getPublic(), validityDays);
            KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
            ks.load(null, null);
            ks.setKeyEntry(IDENTITY_ALIAS, keyPair.getPrivate(), password,
                    new Certificate[] {leaf, caCertificate});
            return ks;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create identity for " + subjectDn, e);
        }
    }

    /** Serialise a freshly minted identity straight to PKCS#12 bytes. */
    public byte[] createIdentityBytes(String subjectDn, char[] password, int validityDays) {
        try {
            KeyStore ks = createIdentity(subjectDn, password, validityDays);
            return toBytes(ks, password);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise identity for " + subjectDn, e);
        }
    }

    /** A one-entry PKCS#12 trust store containing only the CA certificate. */
    public KeyStore toTrustStore() {
        try {
            KeyStore ts = KeyStore.getInstance(KEYSTORE_TYPE);
            ts.load(null, null);
            ts.setCertificateEntry(TRUST_ALIAS, caCertificate);
            return ts;
        } catch (Exception e) {
            throw new IllegalStateException("Could not build CA trust store", e);
        }
    }

    /** Write the CA trust store ({@link #toTrustStore()}) to disk as PKCS#12. */
    public void writeTrustStore(Path path, char[] password) {
        writeKeyStore(toTrustStore(), path, password);
    }

    /** The CA certificate, PEM-encoded. */
    public String getCaCertificatePem() {
        return toPem(caCertificate);
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private X509Certificate issue(X500Name subject, PublicKey subjectPublicKey, int validityDays)
            throws Exception {
        Instant now = Instant.now();
        BigInteger serial = new BigInteger(159, random);
        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                        caSubject,
                        serial,
                        Date.from(now.minus(1, ChronoUnit.HOURS)),
                        Date.from(now.plus(validityDays, ChronoUnit.DAYS)),
                        subject,
                        subjectPublicKey)
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                .addExtension(Extension.keyUsage, true,
                        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment))
                .addExtension(Extension.extendedKeyUsage, false,
                        new ExtendedKeyUsage(new KeyPurposeId[] {
                                KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth}))
                .addExtension(Extension.subjectKeyIdentifier, false,
                        extUtils.createSubjectKeyIdentifier(subjectPublicKey))
                .addExtension(Extension.authorityKeyIdentifier, false,
                        extUtils.createAuthorityKeyIdentifier(caCertificate));

        GeneralNames san = subjectAlternativeName(subject);
        if (san != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, san);
        }
        return sign(builder, caPrivateKey);
    }

    /** Derive a DNS SubjectAltName from the subject CN so server certificates validate by hostname. */
    private static GeneralNames subjectAlternativeName(X500Name subject) {
        var cns = subject.getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.CN);
        if (cns.length == 0) {
            return null;
        }
        String cn = org.bouncycastle.asn1.x500.style.IETFUtils.valueToString(cns[0].getFirst().getValue());
        if (cn.isBlank() || cn.contains(" ")) {
            // Spaces mean it is a human/organisation name, not a hostname — no SAN.
            return null;
        }
        return new GeneralNames(new GeneralName(GeneralName.dNSName, cn));
    }

    private void persist(Path caKeystore, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        ks.load(null, null);
        ks.setKeyEntry(CA_ALIAS, caPrivateKey, password, new Certificate[] {caCertificate});
        writeKeyStore(ks, caKeystore, password);
    }

    private static void writeKeyStore(KeyStore ks, Path path, char[] password) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            byte[] bytes = toBytes(ks, password);
            Files.write(path, bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write keystore to " + path, e);
        }
    }

    private static byte[] toBytes(KeyStore ks, char[] password) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        ks.store(out, password);
        return out.toByteArray();
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        gen.initialize(KEY_SIZE, new SecureRandom());
        return gen.generateKeyPair();
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, PrivateKey issuerKey)
            throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(issuerKey);
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(holder);
    }

    private static PKCS10CertificationRequest parseCsr(String csrPem) throws IOException {
        if (csrPem == null || csrPem.isBlank()) {
            throw new IllegalArgumentException("CSR is empty");
        }
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            Object obj = parser.readObject();
            if (obj instanceof PKCS10CertificationRequest req) {
                return req;
            }
            throw new IllegalArgumentException(
                    "Expected a PKCS#10 certificate request (-----BEGIN CERTIFICATE REQUEST-----)");
        }
    }

    private static String toPem(Object pemObject) {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(pemObject);
        } catch (IOException e) {
            throw new IllegalStateException("Could not PEM-encode " + pemObject, e);
        }
        return sw.toString();
    }

    /** The certificate chains this CA would store in a minted identity, leaf first. */
    List<X509Certificate> chainFor(X509Certificate leaf) {
        return List.of(leaf, caCertificate);
    }
}
