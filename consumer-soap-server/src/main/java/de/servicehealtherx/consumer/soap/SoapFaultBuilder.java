package de.servicehealtherx.consumer.soap;

import jakarta.xml.ws.soap.SOAPFaultException;

import javax.xml.namespace.QName;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPFault;

public final class SoapFaultBuilder {

    private static final String GEMATIK_NS = "http://ws.gematik.de/tel/error/v2.0";
    private static final int DEFAULT_ERROR_CODE = 4001;

    private SoapFaultBuilder() {}

    public static SOAPFaultException fatal(String errorCode, String message) {
        return build("Fatal", errorCode, message);
    }

    public static SOAPFaultException error(String errorCode, String message) {
        return build("Error", errorCode, message);
    }

    public static SOAPFaultException keyUnavailable(String alias) {
        return fatal(String.valueOf(DEFAULT_ERROR_CODE),
            "Key source unavailable for alias: " + alias + " (code 4001)");
    }

    private static SOAPFaultException build(String severity, String errorCode, String message) {
        try {
            var factory = MessageFactory.newInstance();
            var soapMessage = factory.createMessage();
            SOAPFault fault = soapMessage.getSOAPBody().addFault();
            fault.setFaultCode(new QName(GEMATIK_NS, "Status", "tel"));
            fault.setFaultString(severity + ": [" + errorCode + "] " + message);
            return new SOAPFaultException(fault);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build SOAP fault", e);
        }
    }
}
