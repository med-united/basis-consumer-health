package de.servicehealtherx.systemtests.consumer.support;

/**
 * XML namespaces, CXF endpoint paths and {@code SOAPAction} values of the Basis-Consumer SOAP
 * services, taken verbatim from {@code api-telematik/consumer/*.wsdl} and {@code *.xsd}.
 *
 * <p>These constants are the single source of truth for the hand-written envelopes the consumer
 * system tests put on the wire, so they reflect exactly what an external client system sends to
 * the Basis-Consumer (and not what any generated stub happens to produce).
 */
public final class ConsumerNamespaces {

    private ConsumerNamespaces() {}

    // ─── SOAP 1.1 (the consumer WSDLs use the soap/ — not soap12/ — binding) ──────────────────
    public static final String SOAP = "http://schemas.xmlsoap.org/soap/envelope/";

    // ─── Consumer service / common type namespaces (XSD targetNamespace, elementFormDefault=qualified) ─
    public static final String CONSUMER = "http://ws.gematik.de/consumer/ConsumerCommon/v2.0";
    public static final String CERTCMN = "http://ws.gematik.de/consumer/CertificateServiceCommon/v2.1";
    public static final String CERT = "http://ws.gematik.de/consumer/CertificateService/v3.1";
    public static final String CRYPT = "http://ws.gematik.de/consumer/EncryptionService/v3.0";
    public static final String SIG = "http://ws.gematik.de/consumer/SignatureService/v3.2";
    public static final String DSS = "urn:oasis:names:tc:dss:1.0:core:schema";
    public static final String GERROR = "http://ws.gematik.de/tel/error/v2.0";

    // ─── CXF endpoint paths (@CXFEndpoint value), relative to the base URL ────────────────────
    public static final String EP_CERT = "/consumer/CertificateService";
    public static final String EP_CRYPT = "/consumer/EncryptionService";
    public static final String EP_SIG = "/consumer/SignatureService";

    // ─── SOAPAction values (soap:operation/@soapAction in the WSDL bindings) ──────────────────
    public static final String ACTION_READ_CERTIFICATE =
            "http://ws.gematik.de/consumer/CertificateService/v3.0#ReadCertificate";
    public static final String ACTION_VERIFY_CERTIFICATE =
            "http://ws.gematik.de/consumer/CertificateService/v3.0#VerifyCertificate";
    public static final String ACTION_ENCRYPT_DOCUMENT =
            "http://ws.gematik.de/consumer/EncryptionService/v3.0#EncryptDocument";
    public static final String ACTION_DECRYPT_DOCUMENT =
            "http://ws.gematik.de/consumer/EncryptionService/v3.0#DecryptDocument";
    public static final String ACTION_SIGN_DOCUMENT =
            "http://ws.gematik.de/consumer/SignatureService/v3.2#SignDocument";
    public static final String ACTION_SIGN_PLAIN =
            "http://ws.gematik.de/consumer/SignatureService/v3.2#SignPlain";
    public static final String ACTION_EXTERNAL_AUTHENTICATE =
            "http://ws.gematik.de/consumer/SignatureService/v3.2#ExternalAuthenticate";
    public static final String ACTION_VERIFY_DOCUMENT =
            "http://ws.gematik.de/consumer/SignatureService/v3.2#VerifyDocument";
}
