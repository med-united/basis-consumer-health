package de.servicehealtherx.systemtests.consumer.support;

import static de.servicehealtherx.systemtests.consumer.support.ConsumerNamespaces.*;

/**
 * Builders for the eight Basis-Consumer SOAP request envelopes (Certificate, Encryption and
 * Signature services). Every element is namespace-qualified because all consumer XSDs declare
 * {@code elementFormDefault="qualified"}; the prefixes used here are local to the test and need
 * not match the server's.
 *
 * <p>Prefix legend: {@code con}=ConsumerCommon, {@code certcmn}=CertificateServiceCommon,
 * {@code cert}=CertificateService, {@code crypt}=EncryptionService, {@code sig}=SignatureService,
 * {@code dss}=OASIS-DSS.
 */
public final class ConsumerEnvelopes {

    private ConsumerEnvelopes() {}

    /** Wrap a body fragment in a SOAP 1.1 envelope that declares every consumer prefix. */
    public static String envelope(String bodyContent) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="%s"
                                  xmlns:con="%s"
                                  xmlns:certcmn="%s"
                                  xmlns:cert="%s"
                                  xmlns:crypt="%s"
                                  xmlns:sig="%s"
                                  xmlns:dss="%s">
                  <soapenv:Header/>
                  <soapenv:Body>
                %s
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(SOAP, CONSUMER, CERTCMN, CERT, CRYPT, SIG, DSS, bodyContent);
    }

    // ─── CertificateService ─────────────────────────────────────────────────────────────────

    public static String readCertificate(String cardHandle, String certRef, String crypt) {
        return envelope("""
                    <cert:ReadCertificate>
                      <con:CardHandle>%s</con:CardHandle>
                      <cert:CertRefList>
                        <cert:CertRef>%s</cert:CertRef>
                      </cert:CertRefList>
                      <cert:Crypt>%s</cert:Crypt>
                    </cert:ReadCertificate>
                """.formatted(xml(cardHandle), xml(certRef), xml(crypt)));
    }

    public static String verifyCertificate(String certificateBase64) {
        return envelope("""
                    <cert:VerifyCertificate>
                      <certcmn:X509Certificate>%s</certcmn:X509Certificate>
                    </cert:VerifyCertificate>
                """.formatted(certificateBase64));
    }

    // ─── EncryptionService ──────────────────────────────────────────────────────────────────

    public static String encryptDocument(String cardHandle, String crypt, String plaintextBase64) {
        return envelope("""
                    <crypt:EncryptDocument>
                      <crypt:RecipientKeys>
                        <crypt:CertificateOnCard>
                          <con:CardHandle>%s</con:CardHandle>
                          <crypt:Crypt>%s</crypt:Crypt>
                        </crypt:CertificateOnCard>
                      </crypt:RecipientKeys>
                      <con:Document>
                        <dss:Base64Data MimeType="application/octet-stream">%s</dss:Base64Data>
                      </con:Document>
                    </crypt:EncryptDocument>
                """.formatted(xml(cardHandle), xml(crypt), plaintextBase64));
    }

    public static String decryptDocument(String cardHandle, String crypt, String ciphertextBase64) {
        return envelope("""
                    <crypt:DecryptDocument>
                      <crypt:PrivateKeyOnCard>
                        <con:CardHandle>%s</con:CardHandle>
                        <crypt:Crypt>%s</crypt:Crypt>
                      </crypt:PrivateKeyOnCard>
                      <con:Document>
                        <dss:Base64Data MimeType="application/octet-stream">%s</dss:Base64Data>
                      </con:Document>
                    </crypt:DecryptDocument>
                """.formatted(xml(cardHandle), xml(crypt), ciphertextBase64));
    }

    // ─── SignatureService ───────────────────────────────────────────────────────────────────

    public static String signDocument(String cardHandle, String crypt, String documentBase64) {
        // PrivateKeyOnCard is defined in EncryptionService.xsd (CRYPT namespace) and referenced
        // by SignDocument, hence the crypt: prefix on it.
        return envelope("""
                    <sig:SignDocument>
                      <crypt:PrivateKeyOnCard>
                        <con:CardHandle>%s</con:CardHandle>
                        <crypt:Crypt>%s</crypt:Crypt>
                      </crypt:PrivateKeyOnCard>
                      <sig:SignRequest RequestID="req-1">
                        <sig:Document ShortText="system-test">
                          <con:Base64XML>%s</con:Base64XML>
                        </sig:Document>
                      </sig:SignRequest>
                    </sig:SignDocument>
                """.formatted(xml(cardHandle), xml(crypt), documentBase64));
    }

    public static String signPlain(String cardHandle, String dataBase64) {
        return envelope("""
                    <sig:SignPlain>
                      <con:CardHandle>%s</con:CardHandle>
                      <sig:PlainSignRequest RequestID="req-1">
                        <sig:BinaryString>
                          <dss:Base64Data MimeType="application/octet-stream">%s</dss:Base64Data>
                        </sig:BinaryString>
                      </sig:PlainSignRequest>
                    </sig:SignPlain>
                """.formatted(xml(cardHandle), dataBase64));
    }

    public static String externalAuthenticate(String cardHandle, String hashBase64) {
        return envelope("""
                    <sig:ExternalAuthenticate>
                      <con:CardHandle>%s</con:CardHandle>
                      <sig:BinaryString>
                        <dss:Base64Data MimeType="application/octet-stream">%s</dss:Base64Data>
                      </sig:BinaryString>
                    </sig:ExternalAuthenticate>
                """.formatted(xml(cardHandle), hashBase64));
    }

    public static String verifyDocument(String signedDocumentBase64) {
        return envelope("""
                    <sig:VerifyDocument>
                      <sig:Document>
                        <dss:Base64Data MimeType="application/octet-stream">%s</dss:Base64Data>
                      </sig:Document>
                    </sig:VerifyDocument>
                """.formatted(signedDocumentBase64));
    }

    private static String xml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
