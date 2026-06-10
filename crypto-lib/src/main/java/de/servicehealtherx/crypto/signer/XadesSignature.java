package de.servicehealtherx.crypto.signer;

import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLObject;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * XAdES-BES (ETSI EN 319 132) XML Advanced Electronic Signature.
 *
 * Supports two placement modes:
 *   encapsulate=false → Enveloped: ds:Signature inserted into the signed document
 *   encapsulate=true  → Enveloping: original XML embedded in ds:Object inside signature
 */
public class XadesSignature {

    private static final String XADES_NS = "http://uri.etsi.org/01903/v1.3.2#";
    private static final String XMLDSIG_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String ECDSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256";
    private static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    private static final String SIGNED_PROPERTIES_TYPE = "http://uri.etsi.org/01903#SignedProperties";

    private final String providerName;

    public XadesSignature() {
        // "EHBA" provider registered at JCA position 1 by EhbaCryptoStartup;
        // routes ECDSA operations to card via MSE + PSO. Pass "BC" for software keys.
        this("EHBA");
    }

    public XadesSignature(String providerName) {
        this.providerName = providerName;
    }

    public byte[] signXades(
        byte[] xmlBytes,
        boolean encapsulate,
        PrivateKey privateKey,
        X509Certificate signerCert,
        List<X509Certificate> certChain,
        OCSPResp ocspResponse
    ) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();

        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");

        String signatureId = "Sig-" + UUID.randomUUID();
        String signedPropsId = "SP-" + UUID.randomUUID();
        String contentId = "Obj-" + UUID.randomUUID();

        List<XMLObject> objects = new ArrayList<>();
        List<Reference> refs = new ArrayList<>();
        Document signingDoc;

        if (encapsulate) {
            // Enveloping: create a fresh wrapper document; adopt original XML root as ds:Object
            Document contentDoc = db.parse(new ByteArrayInputStream(xmlBytes));
            signingDoc = db.newDocument();
            Element wrapper = signingDoc.createElement("SignatureRoot");
            signingDoc.appendChild(wrapper);

            // contentId goes on the ds:Object element itself (second arg to newXMLObject),
            // NOT on the inner content node — duplicating it would create two elements
            // with the same ID, which the validator rejects.
            Element adoptedRoot = (Element) signingDoc.adoptNode(contentDoc.getDocumentElement());
            objects.add(fac.newXMLObject(
                List.of(new DOMStructure(adoptedRoot)), contentId, null, null
            ));

            refs.add(fac.newReference(
                "#" + contentId,
                fac.newDigestMethod(DigestMethod.SHA256, null),
                List.of(fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null)),
                null, null
            ));
        } else {
            // Enveloped: signature inserted into the existing document root
            signingDoc = db.parse(new ByteArrayInputStream(xmlBytes));
            refs.add(fac.newReference(
                "",
                fac.newDigestMethod(DigestMethod.SHA256, null),
                List.of(
                    fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                    fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null)
                ),
                null, null
            ));
        }

        // Build QualifyingProperties in signingDoc so setIdAttribute registers in the
        // same document the signing engine will query via getElementById.
        Element qualifyingPropsEl = buildQualifyingProperties(
            signingDoc, signatureId, signedPropsId, signerCert, ocspResponse
        );
        ((Element) qualifyingPropsEl.getElementsByTagNameNS(XADES_NS, "SignedProperties").item(0))
            .setIdAttribute("Id", true);
        objects.add(fac.newXMLObject(
            List.of(new DOMStructure(qualifyingPropsEl)), null, null, null
        ));

        // Mandatory XAdES reference to SignedProperties (covers signing time and cert hash)
        refs.add(fac.newReference(
            "#" + signedPropsId,
            fac.newDigestMethod(DigestMethod.SHA256, null),
            List.of(fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null)),
            SIGNED_PROPERTIES_TYPE,
            null
        ));

        SignedInfo signedInfo = fac.newSignedInfo(
            fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
            fac.newSignatureMethod(signatureAlgorithm(signerCert), null),
            refs
        );

        KeyInfoFactory kif = fac.getKeyInfoFactory();
        KeyInfo keyInfo = kif.newKeyInfo(List.of(kif.newX509Data(List.of(signerCert))));

        XMLSignature xmlSig = fac.newXMLSignature(signedInfo, keyInfo, objects, signatureId, null);

        Element signatureParent = signingDoc.getDocumentElement();
        DOMSignContext signCtx = new DOMSignContext(privateKey, signatureParent);
        signCtx.setDefaultNamespacePrefix("ds");

        Provider provider = Security.getProvider(providerName);
        if (provider != null) {
            signCtx.setProperty("org.jcp.xml.dsig.internal.dom.SignatureProvider", provider);
        }

        xmlSig.sign(signCtx);
        return serialize(signingDoc);
    }

    private static Element buildQualifyingProperties(
        Document doc,
        String signatureId,
        String signedPropsId,
        X509Certificate signerCert,
        OCSPResp ocspResponse
    ) throws Exception {
        Element qp = doc.createElementNS(XADES_NS, "xades:QualifyingProperties");
        qp.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xades", XADES_NS);
        qp.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:ds", XMLDSIG_NS);
        qp.setAttribute("Target", "#" + signatureId);

        Element sp = doc.createElementNS(XADES_NS, "xades:SignedProperties");
        sp.setAttribute("Id", signedPropsId);
        qp.appendChild(sp);

        Element ssp = doc.createElementNS(XADES_NS, "xades:SignedSignatureProperties");
        sp.appendChild(ssp);

        Element signingTime = doc.createElementNS(XADES_NS, "xades:SigningTime");
        signingTime.setTextContent(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC))
        );
        ssp.appendChild(signingTime);

        ssp.appendChild(buildSigningCertificateV2(doc, signerCert));

        if (ocspResponse != null) {
            Element up = doc.createElementNS(XADES_NS, "xades:UnsignedProperties");
            Element usp = doc.createElementNS(XADES_NS, "xades:UnsignedSignatureProperties");
            usp.appendChild(buildRevocationValues(doc, ocspResponse));
            up.appendChild(usp);
            qp.appendChild(up);
        }

        return qp;
    }

    private static Element buildSigningCertificateV2(
        Document doc, X509Certificate signerCert
    ) throws Exception {
        byte[] certHash = MessageDigest.getInstance("SHA-256").digest(signerCert.getEncoded());

        Element sigCertV2 = doc.createElementNS(XADES_NS, "xades:SigningCertificateV2");
        Element cert = doc.createElementNS(XADES_NS, "xades:Cert");

        Element certDigest = doc.createElementNS(XADES_NS, "xades:CertDigest");
        Element digestMethod = doc.createElementNS(XMLDSIG_NS, "ds:DigestMethod");
        digestMethod.setAttribute("Algorithm", DigestMethod.SHA256);
        Element digestValue = doc.createElementNS(XMLDSIG_NS, "ds:DigestValue");
        digestValue.setTextContent(Base64.getEncoder().encodeToString(certHash));
        certDigest.appendChild(digestMethod);
        certDigest.appendChild(digestValue);
        cert.appendChild(certDigest);
        sigCertV2.appendChild(cert);
        return sigCertV2;
    }

    private static Element buildRevocationValues(Document doc, OCSPResp ocspResponse) throws Exception {
        try {
            BasicOCSPResp basicResp = (BasicOCSPResp) ocspResponse.getResponseObject();
            byte[] basicOcspBytes = basicResp.getEncoded();

            Element revValues = doc.createElementNS(XADES_NS, "xades:RevocationValues");
            Element ocspValues = doc.createElementNS(XADES_NS, "xades:OCSPValues");
            Element encapsulated = doc.createElementNS(XADES_NS, "xades:EncapsulatedOCSPValue");
            encapsulated.setTextContent(Base64.getEncoder().encodeToString(basicOcspBytes));
            ocspValues.appendChild(encapsulated);
            revValues.appendChild(ocspValues);
            return revValues;
        } catch (OCSPException e) {
            throw new Exception("Failed to encode OCSP response for XAdES RevocationValues", e);
        }
    }

    private static String signatureAlgorithm(X509Certificate cert) {
        return switch (cert.getPublicKey().getAlgorithm()) {
            case "EC" -> ECDSA_SHA256;
            case "RSA" -> RSA_SHA256;
            default -> throw new IllegalArgumentException(
                "Unsupported key algorithm: " + cert.getPublicKey().getAlgorithm()
            );
        };
    }

    private static byte[] serialize(Document doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }
}
