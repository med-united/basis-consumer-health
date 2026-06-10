package de.servicehealtherx.crypto.signer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
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
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SimpleAttributeTableGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

public class PadesSignature {

    // ETSI EN 319 142-1: signature dictionary uses Adobe.PPKLite filter and
    // ETSI.CAdES.detached subfilter. /Contents holds a detached CMS SignedData
    // (no eContent) whose MessageDigest is computed over the /ByteRange.
    private static final int CONTENTS_RESERVED_BYTES = 32 * 1024;

    private final String providerName;

    public PadesSignature() {
        // "EHBA" is registered at JCA position 1 by EhbaCryptoStartup before
        // any request handling; JcaContentSignerBuilder.setProvider("EHBA")
        // routes SHA256withECDSA to EHBACardECDSASignatureSpi → MSE + PSO on
        // the card. Tests that sign with a software key pass "BC".
        this("EHBA");
    }

    public PadesSignature(String providerName) {
        this.providerName = providerName;
    }

    public byte[] signPades(
        byte[] pdfBytes,
        PrivateKey privateKey,
        X509Certificate signerCert,
        List<X509Certificate> certChain,
        OCSPResp ocspResponse
    ) throws Exception {

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {

            PDSignature pdSignature = new PDSignature();
            pdSignature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            pdSignature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            pdSignature.setName(commonName(signerCert));
            pdSignature.setSignDate(Calendar.getInstance());

            CadesDetachedSigner signer = new CadesDetachedSigner(
                providerName, privateKey, signerCert, certChain, ocspResponse
            );

            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(CONTENTS_RESERVED_BYTES);
            document.addSignature(pdSignature, signer, options);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // Incremental save preserves the original PDF bytes — required so
            // /ByteRange covers exactly the pre-existing document plus the new
            // signature dictionary, and PDF/A conformance of the input survives.
            document.saveIncremental(out);
            return out.toByteArray();
        }
    }

    private static String commonName(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();
        for (String rdn : dn.split(",")) {
            String trimmed = rdn.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return dn;
    }

    private static final class CadesDetachedSigner implements SignatureInterface {

        private final String providerName;
        private final PrivateKey privateKey;
        private final X509Certificate signerCert;
        private final List<X509Certificate> certChain;
        private final OCSPResp ocspResponse;

        CadesDetachedSigner(
            String providerName,
            PrivateKey privateKey,
            X509Certificate signerCert,
            List<X509Certificate> certChain,
            OCSPResp ocspResponse
        ) {
            this.providerName = providerName;
            this.privateKey = privateKey;
            this.signerCert = signerCert;
            this.certChain = certChain;
            this.ocspResponse = ocspResponse;
        }

        @Override
        public byte[] sign(InputStream content) throws IOException {
            try {
                byte[] toBeSigned = content.readAllBytes();

                CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
                ContentSigner contentSigner = new DebuggingContentSigner(new JcaContentSignerBuilder("SHA256withECDSA")
                    .setProvider(providerName)
                    .build(privateKey));

                DigestCalculatorProvider digestCalcProvider = new JcaDigestCalculatorProviderBuilder().setProvider("BC").build();

                ASN1EncodableVector signedAttrs = buildSignedAttributes(signerCert);
                ASN1EncodableVector unsignedAttrs = new ASN1EncodableVector();
                if (ocspResponse != null) {
                    unsignedAttrs.add(buildRevocationValuesAttribute(ocspResponse));
                }

                JcaSignerInfoGeneratorBuilder signerBuilder = new JcaSignerInfoGeneratorBuilder(digestCalcProvider);
                signerBuilder.setSignedAttributeGenerator(
                    new DefaultSignedAttributeTableGenerator(new AttributeTable(signedAttrs))
                );
                signerBuilder.setUnsignedAttributeGenerator(
                    new SimpleAttributeTableGenerator(new AttributeTable(unsignedAttrs))
                );

                gen.addSignerInfoGenerator(signerBuilder.build(contentSigner, signerCert));
                gen.addCertificates(new JcaCertStore(certChain));

                // encapsulate=false → detached: eContent is absent; the
                // MessageDigest signed-attribute is computed over the PDF
                // byte-range bytes that PDFBox just streamed in.
                CMSSignedData signedData = gen.generate(new CMSProcessableByteArray(toBeSigned), false);
                return signedData.getEncoded();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("PAdES signing failed", e);
            }
        }
    }

    private static ASN1EncodableVector buildSignedAttributes(X509Certificate signerCert) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] certHash = sha256.digest(signerCert.getEncoded());
        ESSCertIDv2 essCertID = new ESSCertIDv2(
            new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256),
            certHash
        );
        SigningCertificateV2 signingCertV2 = new SigningCertificateV2(
            new ESSCertIDv2[]{essCertID}
        );
        ASN1EncodableVector attrs = new ASN1EncodableVector();
        attrs.add(new Attribute(
            CMSAttributes.contentType,
            new DERSet(new ASN1ObjectIdentifier("1.2.840.113549.1.7.1"))
        ));
        attrs.add(new Attribute(
            CMSAttributes.signingTime,
            new DERSet(new Time(new Date()))
        ));
        attrs.add(new Attribute(
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.47"),
            new DERSet(signingCertV2)
        ));
        return attrs;
    }

    private static Attribute buildRevocationValuesAttribute(OCSPResp ocspResponse)
        throws IOException {
        try {
            BasicOCSPResp basicResp = (BasicOCSPResp) ocspResponse.getResponseObject();
            byte[] basicOcspBytes = basicResp.getEncoded();

            ASN1EncodableVector ocspVals = new ASN1EncodableVector();
            ocspVals.add(ASN1Primitive.fromByteArray(basicOcspBytes));

            ASN1EncodableVector revocationValues = new ASN1EncodableVector();
            revocationValues.add(new DERTaggedObject(true, 1, new DERSequence(ocspVals)));

            return new Attribute(
                new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.24"),
                new DERSet(new DERSequence(revocationValues))
            );
        } catch (OCSPException e) {
            throw new IOException(e);
        }
    }
}
