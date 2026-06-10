package de.servicehealtherx.crypto.signer;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XadesSignatureTest {

    private static final String SAMPLE_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<Document><Content>Hello XAdES</Content></Document>";

    private static KeyPair ecKeyPair;
    private static X509Certificate ecCert;
    private static KeyPair rsaKeyPair;
    private static X509Certificate rsaCert;

    @BeforeAll
    static void setUp() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        ecKeyPair = generateKeyPair("EC", 256);
        ecCert = selfSignedCert("CN=Test EC Signer", ecKeyPair, "SHA256withECDSA");

        rsaKeyPair = generateKeyPair("RSA", 2048);
        rsaCert = selfSignedCert("CN=Test RSA Signer", rsaKeyPair, "SHA256withRSA");
    }

    // ── Enveloped (encapsulate=false) ───────────────────────────────────────

    @Test
    void enveloped_ec_produces_valid_xades() throws Exception {
        byte[] signed = signer().signXades(
            xml(SAMPLE_XML), false, ecKeyPair.getPrivate(), ecCert,
            List.of(ecCert), null
        );

        Document doc = parse(signed);
        assertSignaturePresent(doc);
        assertXadesQualifyingPropertiesPresent(doc);
        assertSigningCertificateV2Present(doc);
        assertSigningTimePresent(doc);
        verifyXmlSignature(doc, ecCert);
    }

    @Test
    void enveloped_rsa_produces_valid_xades() throws Exception {
        byte[] signed = signer().signXades(
            xml(SAMPLE_XML), false, rsaKeyPair.getPrivate(), rsaCert,
            List.of(rsaCert), null
        );

        Document doc = parse(signed);
        assertSignaturePresent(doc);
        verifyXmlSignature(doc, rsaCert);
    }

    @Test
    void enveloped_preserves_original_content() throws Exception {
        byte[] signed = signer().signXades(
            xml(SAMPLE_XML), false, ecKeyPair.getPrivate(), ecCert,
            List.of(ecCert), null
        );

        Document doc = parse(signed);
        NodeList content = doc.getElementsByTagNameNS("*", "Content");
        assertEquals(1, content.getLength(), "Original <Content> element must survive");
        assertEquals("Hello XAdES", content.item(0).getTextContent());
    }

    @Test
    void enveloped_signature_is_child_of_document_root() throws Exception {
        byte[] signed = signer().signXades(
            xml(SAMPLE_XML), false, ecKeyPair.getPrivate(), ecCert,
            List.of(ecCert), null
        );

        Document doc = parse(signed);
        NodeList sigs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertEquals(1, sigs.getLength());
        assertEquals("Document", sigs.item(0).getParentNode().getLocalName(),
            "Signature must be a child of the document root element");
    }

    // ── Enveloping (encapsulate=true) ───────────────────────────────────────

    @Test
    void enveloping_ec_produces_valid_xades() throws Exception {
        byte[] signed = signer().signXades(
            xml(SAMPLE_XML), true, ecKeyPair.getPrivate(), ecCert,
            List.of(ecCert), null
        );

        Document doc = parse(signed);
        assertSignaturePresent(doc);
        assertXadesQualifyingPropertiesPresent(doc);
        verifyXmlSignature(doc, ecCert);
    }

    @Test
    void enveloping_embeds_original_content_in_object() throws Exception {
        byte[] signed = signer().signXades(
            xml(SAMPLE_XML), true, ecKeyPair.getPrivate(), ecCert,
            List.of(ecCert), null
        );

        Document doc = parse(signed);
        NodeList objects = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Object");
        assertTrue(objects.getLength() >= 1, "ds:Object elements must be present");

        NodeList content = doc.getElementsByTagNameNS("*", "Content");
        assertEquals(1, content.getLength(), "Original <Content> must be inside ds:Object");
        assertEquals("Hello XAdES", content.item(0).getTextContent());
    }

    // ── OCSP revocation values ──────────────────────────────────────────────

    @Test
    void enveloped_without_ocsp_omits_unsigned_properties() throws Exception {
        byte[] signed = signer().signXades(
            xml(SAMPLE_XML), false, ecKeyPair.getPrivate(), ecCert,
            List.of(ecCert), null
        );

        Document doc = parse(signed);
        NodeList up = doc.getElementsByTagNameNS(
            "http://uri.etsi.org/01903/v1.3.2#", "UnsignedProperties"
        );
        assertEquals(0, up.getLength(), "UnsignedProperties must be absent when no OCSP given");
    }

    // ── Certificate chain ───────────────────────────────────────────────────

    @Test
    void keyinfo_contains_signer_certificate() throws Exception {
        byte[] signed = signer().signXades(
            xml(SAMPLE_XML), false, ecKeyPair.getPrivate(), ecCert,
            List.of(ecCert), null
        );

        Document doc = parse(signed);
        NodeList x509Certs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "X509Certificate");
        assertTrue(x509Certs.getLength() >= 1, "ds:X509Certificate must be present in KeyInfo");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static XadesSignature signer() {
        return new XadesSignature("BC");
    }

    private static byte[] xml(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(bytes));
    }

    private static void assertSignaturePresent(Document doc) {
        NodeList sigs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertEquals(1, sigs.getLength(), "Exactly one ds:Signature must be present");
    }

    private static void assertXadesQualifyingPropertiesPresent(Document doc) {
        NodeList qp = doc.getElementsByTagNameNS(
            "http://uri.etsi.org/01903/v1.3.2#", "QualifyingProperties"
        );
        assertEquals(1, qp.getLength(), "xades:QualifyingProperties must be present");
    }

    private static void assertSigningCertificateV2Present(Document doc) {
        NodeList sc = doc.getElementsByTagNameNS(
            "http://uri.etsi.org/01903/v1.3.2#", "SigningCertificateV2"
        );
        assertEquals(1, sc.getLength(), "xades:SigningCertificateV2 must be present");
    }

    private static void assertSigningTimePresent(Document doc) {
        NodeList st = doc.getElementsByTagNameNS(
            "http://uri.etsi.org/01903/v1.3.2#", "SigningTime"
        );
        assertEquals(1, st.getLength(), "xades:SigningTime must be present");
        assertNotNull(st.item(0).getTextContent());
        assertTrue(st.item(0).getTextContent().length() > 10, "SigningTime must not be empty");
    }

    private static void verifyXmlSignature(Document doc, X509Certificate cert) throws Exception {
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        NodeList sigNodes = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertEquals(1, sigNodes.getLength());

        // Register all elements with Id attributes as XML IDs so references resolve
        registerIdAttributes(doc);

        DOMValidateContext valCtx = new DOMValidateContext(cert.getPublicKey(), sigNodes.item(0));
        valCtx.setProperty("org.jcp.xml.dsig.internal.dom.SignatureProvider",
            Security.getProvider("BC"));

        XMLSignature xmlSig = fac.unmarshalXMLSignature(valCtx);
        assertTrue(xmlSig.validate(valCtx), "XML signature must be cryptographically valid");
    }

    private static void registerIdAttributes(Document doc) {
        // Ensure any element with an "Id" attribute is registered as an XML ID
        // so that "#id" URI references resolve during validation.
        registerIdAttributesRecursive(doc.getDocumentElement());
    }

    private static void registerIdAttributesRecursive(org.w3c.dom.Element el) {
        if (el.hasAttribute("Id")) {
            el.setIdAttribute("Id", true);
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof org.w3c.dom.Element child) {
                registerIdAttributesRecursive(child);
            }
        }
    }

    private static KeyPair generateKeyPair(String algorithm, int keySize) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance(algorithm, "BC");
        gen.initialize(keySize);
        return gen.generateKeyPair();
    }

    private static X509Certificate selfSignedCert(
        String dn, KeyPair keyPair, String sigAlgo
    ) throws Exception {
        X500Name subject = new X500Name(dn);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            Date.from(Instant.parse("2025-01-01T00:00:00Z")),
            Date.from(Instant.parse("2030-01-01T00:00:00Z")),
            subject,
            keyPair.getPublic()
        );
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

        return new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(
                builder.build(new JcaContentSignerBuilder(sigAlgo).setProvider("BC").build(keyPair.getPrivate()))
            );
    }
}
