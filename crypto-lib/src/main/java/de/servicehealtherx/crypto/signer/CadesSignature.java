package de.servicehealtherx.crypto.signer;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.SimpleAttributeTableGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

public class CadesSignature {

    // ─────────────────────────────────────────────────────────────────────────
    // Haupt-Methode: CAdES-BES + OCSP
    //   encapsulate=true → enveloping (eContent embedded inside the CMS)
    //   encapsulate=false → detached (eContent absent; verifier needs the
    //                                  original bytes alongside the CMS,
    //                                  cross-checked via messageDigest)
    // Driven by the SOAP IncludeEContent flag.
    // ─────────────────────────────────────────────────────────────────────────
    public byte[] signCades(
        byte[] content,                  // zu signierendes Dokument
        boolean encapsulate,             // true = enveloping, false = detached
        PrivateKey privateKey,           // eHBA privater ECDSA-Schlüssel
        X509Certificate signerCert,      // eHBA-Zertifikat (QES)
        List<X509Certificate> certChain, // CA-Zertifikatskette
        OCSPResp ocspResponse            // frische OCSP-Antwort (< 15 min)
    ) throws Exception {

        CMSTypedData msg = new CMSProcessableByteArray(
            new ASN1ObjectIdentifier("1.2.840.113549.1.7.1"), // id-data
            content
        );

        // Zertifikatsspeicher für CMS certificates-Feld
        JcaCertStore certStore = new JcaCertStore(certChain);

        // ContentSigner with Debug-Wrapper – ECDSA mit SHA-256 (eHBA QES path via the "EHBA"
        // provider, registered by EhbaCryptoStartup at position 1).
        ContentSigner contentSigner = new DebuggingContentSigner(new JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider("EHBA")
            .build(privateKey));

        // SignerInfoGenerator mit CAdES-Pflichtattributen
        CMSSignedDataGenerator generatorWithSigner = addSignerWithCadesAttributes(contentSigner, signerCert, ocspResponse);
        generatorWithSigner.addCertificates(certStore);
        CMSSignedData signedData = generatorWithSigner.generate(msg, encapsulate);
        // signedData = addTimestamp(signedData); // Optional: id-aa-signingTimeStamp (CAdES-A)

        return signedData.getEncoded();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SignerInfo mit allen CAdES-Attributen aufbauen
    // ─────────────────────────────────────────────────────────────────────────
    private CMSSignedDataGenerator addSignerWithCadesAttributes(
        ContentSigner contentSigner,
        X509Certificate signerCert,
        OCSPResp ocspResponse
    ) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        DigestCalculatorProvider digestCalcProvider = new JcaDigestCalculatorProviderBuilder().setProvider("BC").build();

        // SigningCertificateV2 (CAdES-Pflicht, ersetzt SigningCertificate)
        ASN1EncodableVector signedAttrs = buildSignedAttributes(signerCert);

        // OCSP als unsigned attribute (RevocationValues)
        ASN1EncodableVector unsignedAttrs = new ASN1EncodableVector();
        if (ocspResponse != null) {
            unsignedAttrs.add(buildRevocationValuesAttribute(ocspResponse));
        }

        // SignerInfoGenerator zusammenbauen
        JcaSignerInfoGeneratorBuilder signerBuilder = new JcaSignerInfoGeneratorBuilder(digestCalcProvider);
        signerBuilder.setSignedAttributeGenerator(new DefaultSignedAttributeTableGenerator(new AttributeTable(signedAttrs)));
        signerBuilder.setUnsignedAttributeGenerator(new SimpleAttributeTableGenerator(new AttributeTable(unsignedAttrs)));
        gen.addSignerInfoGenerator(signerBuilder.build(contentSigner, signerCert));

        return gen;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Signed Attributes: content-type + message-digest + signing-time
    //                  + id-aa-signingCertificateV2  (CAdES-Pflicht!)
    // ─────────────────────────────────────────────────────────────────────────
    private ASN1EncodableVector buildSignedAttributes(X509Certificate signerCert) throws Exception {
        // SHA-256 Hash des Signaturzertifikats
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] certHash = sha256.digest(signerCert.getEncoded());

        // ESSCertIDv2 = Hash-Algo + Cert-Hash (+ optional IssuerSerial)
        AlgorithmIdentifier algorithmIdentifier = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
        ESSCertIDv2 essCertID = new ESSCertIDv2(algorithmIdentifier, certHash);

        // SigningCertificateV2 Attribut (OID 1.2.840.113549.1.9.16.2.47)
        SigningCertificateV2 signingCertV2 = new SigningCertificateV2(new ESSCertIDv2[]{essCertID});

        ASN1EncodableVector attrs = new ASN1EncodableVector();

        // content-type (1.2.840.113549.1.9.3)
        attrs.add(new Attribute(
            CMSAttributes.contentType,
            new DERSet(new ASN1ObjectIdentifier("1.2.840.113549.1.7.1"))
        ));

        Date date = new Date();
        // date = new SimpleDateFormat("yyyy-MM-dd")
        //        .parse("2026-01-01");

        // signing-time (1.2.840.113549.1.9.5)
        attrs.add(new Attribute(CMSAttributes.signingTime, new DERSet(new Time(date))));

        // id-aa-signingCertificateV2 (1.2.840.113549.1.9.16.2.47)
        attrs.add(new Attribute(
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.47"),
            new DERSet(signingCertV2)
        ));

        // Hinweis: message-digest wird automatisch von BouncyCastle hinzugefügt
        return attrs;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unsigned Attribute: id-aa-ets-revocationValues mit OCSP Response
    // OID: 1.2.840.113549.1.9.16.2.24
    // ─────────────────────────────────────────────────────────────────────────
    private Attribute buildRevocationValuesAttribute(OCSPResp ocspResponse)
        throws IOException {

        // RevocationValues ::= SEQUENCE {
        //   crlVals        [0] SEQUENCE OF CertificateList OPTIONAL,
        //   ocspVals       [1] SEQUENCE OF BasicOCSPResponse OPTIONAL,
        //   otherRevVals   [2] OtherRevVals OPTIONAL
        // }

        // BasicOCSPResponse aus OCSPResp extrahieren
        BasicOCSPResp basicResp;
        try {
            basicResp = (BasicOCSPResp) ocspResponse.getResponseObject();
            byte[] basicOcspBytes = basicResp.getEncoded();

            // ocspVals [1] SEQUENCE OF BasicOCSPResponse
            ASN1EncodableVector ocspVals = new ASN1EncodableVector();
            ocspVals.add(ASN1Primitive.fromByteArray(basicOcspBytes));

            ASN1EncodableVector revocationValues = new ASN1EncodableVector();
            revocationValues.add(new DERTaggedObject(true, 1, new DERSequence(ocspVals)));

            return new Attribute(
                new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.24"),
                new DERSet(new DERSequence(revocationValues))
            );
        } catch (OCSPException e) {
            throw new RuntimeException(e);
        }
    }

    public static CMSSignedData addTimestamp(CMSSignedData signedData) throws Exception {
        SignerInformation signer = signedData.getSignerInfos().getSigners().iterator().next();

        // Timestamp über den rohen Signaturwert holen
        byte[] tsToken = TiTimestamp.fetchTimestamp(signer.getSignature());

        // Als unsigned attribute hinzufügen
        AttributeTable unsignedAttrs = signer.getUnsignedAttributes();
        ASN1EncodableVector v = unsignedAttrs != null
            ? unsignedAttrs.toASN1EncodableVector()
            : new ASN1EncodableVector();

        v.add(new Attribute(
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.14"),
            new DERSet(ASN1Primitive.fromByteArray(tsToken))
        ));

        SignerInformation newSigner = SignerInformation.replaceUnsignedAttributes(signer, new AttributeTable(v));
        return CMSSignedData.replaceSigners(signedData, new SignerInformationStore(List.of(newSigner)));
    }
}
