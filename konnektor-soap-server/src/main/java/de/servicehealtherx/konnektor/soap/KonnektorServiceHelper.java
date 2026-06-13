package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.connectorcommon.v5.Status;
import de.gematik.ws.tel.error.v2.Error;
import de.servicehealtherx.crypto.KeyAlias;

import javax.xml.datatype.DatatypeFactory;
import java.math.BigInteger;
import java.util.GregorianCalendar;

final class KonnektorServiceHelper {
    private KonnektorServiceHelper() {
    }

    static Status okStatus() {
        Status s = new Status();
        s.setResult("OK");
        return s;
    }

    static Status errorStatus(String message) {
        Status s = new Status();
        s.setResult("Error");
        s.setError(buildError(message));
        return s;
    }

    static Error buildError(String message) {
        Error error = new Error();
        error.setMessageID("4001");
        try {
            error.setTimestamp(DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar()));
        } catch (Exception ignored) {
        }
        Error.Trace trace = new Error.Trace();
        trace.setEventID("4001");
        trace.setInstance("konnektor-soap-server");
        trace.setLogReference("");
        trace.setCompType("KonnektorSoapServer");
        trace.setCode(BigInteger.valueOf(4001));
        trace.setSeverity("Error");
        trace.setErrorType("TechnicalError");
        trace.setErrorText(message != null ? message : "Unknown error");
        error.getTrace().add(trace);
        return error;
    }

    static KeyAlias toKeyAlias(String cardHandle) {
        String sanitized = cardHandle.toLowerCase()
                .replaceAll("[^a-z0-9\\-_/]", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return new KeyAlias(sanitized.isEmpty() ? "default" : sanitized);
    }
}
