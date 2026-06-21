package de.servicehealtherx.crypto.signer.card;

import de.servicehealtherx.crypto.signer.CadesSignature;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the card-backed signing bridge end to end without a real card: a {@link CardSigner}
 * backed by a software EC key (emitting the raw {@code R||S} a card would) is routed through the
 * {@code "EHBA"} provider by {@link CadesSignature}, and the resulting CAdES CMS verifies.
 */
class CardSigningCadesTest {

    private static final int P256_FIELD_BYTES = 32;

    private static KeyPair ecKeyPair;
    private static X509Certificate ecCert;

    @BeforeAll
    static void setUp() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if (Security.getProvider(EhbaProvider.NAME) == null) {
            Security.addProvider(new EhbaProvider());
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC", "BC");
        gen.initialize(256);
        ecKeyPair = gen.generateKeyPair();
        ecCert = selfSignedCert("CN=Test Card QES", ecKeyPair, "SHA256withECDSA");
    }

    @Test
    void cardSigner_produces_verifiable_enveloping_cades() throws Exception {
        byte[] content = "Qualified e-prescription payload".getBytes(StandardCharsets.UTF_8);

        byte[] cms = new CadesSignature().signCades(
                content, true, new CardSigningPrivateKey(softwareCardSigner()), ecCert, List.of(ecCert), null);

        // Parses as a real CMS SignedData (the failure mode the bug produced was "d2i_CMS header too long").
        CMSSignedData signed = new CMSSignedData(cms);
        SignerInformation signer = signed.getSignerInfos().getSigners().iterator().next();
        assertTrue(signer.verify(
                new JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(ecCert)),
                "CAdES signature produced via the EHBA card bridge must verify");
    }

    @Test
    void der_encoding_round_trips_a_raw_signature() {
        byte[] raw = new byte[2 * P256_FIELD_BYTES];
        Arrays.fill(raw, 0, P256_FIELD_BYTES, (byte) 0x7F);          // r: positive, no zero pad
        Arrays.fill(raw, P256_FIELD_BYTES, raw.length, (byte) 0x80); // s: high bit set → needs zero pad

        byte[] der = EcdsaDerEncoder.rawToDer(raw);

        ASN1Sequence seq = ASN1Sequence.getInstance(der);
        BigInteger r = ((ASN1Integer) seq.getObjectAt(0)).getValue();
        BigInteger s = ((ASN1Integer) seq.getObjectAt(1)).getValue();
        assertEquals(new BigInteger(1, Arrays.copyOfRange(raw, 0, P256_FIELD_BYTES)), r);
        assertEquals(new BigInteger(1, Arrays.copyOfRange(raw, P256_FIELD_BYTES, raw.length)), s);
    }

    @Test
    void der_encoding_rejects_odd_length() {
        assertThrows(IllegalArgumentException.class, () -> EcdsaDerEncoder.rawToDer(new byte[31]));
    }

    /** A {@link CardSigner} that signs in software but returns the raw {@code R||S} a card emits. */
    private static CardSigner softwareCardSigner() {
        return toBeSigned -> {
            try {
                Signature s = Signature.getInstance("SHA256withECDSA", "BC");
                s.initSign(ecKeyPair.getPrivate());
                s.update(toBeSigned);
                return derToRaw(s.sign(), P256_FIELD_BYTES);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /** Convert a DER {@code SEQUENCE { INTEGER r, INTEGER s }} into fixed-length {@code R||S}. */
    private static byte[] derToRaw(byte[] der, int fieldBytes) {
        ASN1Sequence seq = ASN1Sequence.getInstance(der);
        BigInteger r = ((ASN1Integer) seq.getObjectAt(0)).getValue();
        BigInteger s = ((ASN1Integer) seq.getObjectAt(1)).getValue();
        byte[] raw = new byte[2 * fieldBytes];
        toFixed(r, raw, 0, fieldBytes);
        toFixed(s, raw, fieldBytes, fieldBytes);
        return raw;
    }

    private static void toFixed(BigInteger value, byte[] out, int offset, int len) {
        byte[] b = value.toByteArray();
        int srcOff = 0;
        int srcLen = b.length;
        if (srcLen > len) { // drop a leading sign byte
            srcOff = srcLen - len;
            srcLen = len;
        }
        System.arraycopy(b, srcOff, out, offset + (len - srcLen), srcLen);
    }

    private static X509Certificate selfSignedCert(String dn, KeyPair keyPair, String sigAlgo) throws Exception {
        X500Name subject = new X500Name(dn);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(System.currentTimeMillis()),
                Date.from(Instant.parse("2025-01-01T00:00:00Z")),
                Date.from(Instant.parse("2030-01-01T00:00:00Z")),
                subject,
                keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(
                builder.build(new JcaContentSignerBuilder(sigAlgo).setProvider("BC").build(keyPair.getPrivate())));
    }
}
