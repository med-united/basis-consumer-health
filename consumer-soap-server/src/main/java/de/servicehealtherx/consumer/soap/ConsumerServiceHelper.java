package de.servicehealtherx.consumer.soap;

import de.gematik.ws.consumer.consumercommon.v2.DocumentType;
import de.gematik.ws.consumer.consumercommon.v2.Status;
import de.gematik.ws.tel.error.v2.Error;
import de.servicehealtherx.crypto.KeyAlias;
import oasis.names.tc.dss._1_0.core.schema.Base64Data;

import javax.xml.datatype.DatatypeFactory;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.GregorianCalendar;

final class ConsumerServiceHelper {

    private ConsumerServiceHelper() {}

    static Status okStatus() {
        Status status = new Status();
        status.setResult("OK");
        return status;
    }

    static Error buildError(String message) {
        Error error = new Error();
        error.setMessageID("4001");
        try {
            error.setTimestamp(DatatypeFactory.newInstance()
                .newXMLGregorianCalendar(new GregorianCalendar()));
        } catch (Exception ignored) {}

        Error.Trace trace = new Error.Trace();
        trace.setEventID("4001");
        trace.setInstance("consumer-soap-server");
        trace.setLogReference("");
        trace.setCompType("ConsumerSoapServer");
        trace.setCode(BigInteger.valueOf(4001));
        trace.setSeverity("Error");
        trace.setErrorType("TechnicalError");
        trace.setErrorText(message != null ? message : "Unknown error");
        error.getTrace().add(trace);
        return error;
    }

    static KeyAlias toKeyAlias(String cardHandle) {
        if (cardHandle == null || cardHandle.isBlank()) {
            return new KeyAlias("sicct/default");
        }
        String sanitized = cardHandle.toLowerCase()
            .replaceAll("[^a-z0-9\\-_]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
        return new KeyAlias("sicct/" + (sanitized.isEmpty() ? "default" : sanitized));
    }

    static byte[] extractDocumentBytes(DocumentType doc) {
        if (doc == null) {
            return new byte[0];
        }
        if (doc.getBase64XML() != null) {
            return doc.getBase64XML();
        }
        if (doc.getBase64Data() != null && doc.getBase64Data().getValue() != null) {
            return doc.getBase64Data().getValue();
        }
        return new byte[0];
    }

    static DocumentType wrapBytes(byte[] data) {
        DocumentType doc = new DocumentType();
        Base64Data base64Data = new Base64Data();
        base64Data.setValue(data);
        doc.setBase64Data(base64Data);
        return doc;
    }

    static X509Certificate parseCertificate(byte[] der) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }
}
