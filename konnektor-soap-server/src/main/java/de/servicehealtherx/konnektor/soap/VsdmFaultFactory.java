package de.servicehealtherx.konnektor.soap;

import java.math.BigInteger;
import java.util.GregorianCalendar;

import javax.xml.datatype.DatatypeFactory;

import de.gematik.ws.conn.vsds.vsdservice.v5_2.FaultMessage;
import de.gematik.ws.tel.error.v2.Error;
import de.servicehealtherx.apdu.vsdm.VsdmErrorCode;
import de.servicehealtherx.apdu.vsdm.VsdmReadException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Maps a {@link VsdmReadException} to the gematik SOAP {@link FaultMessage}. Every fault carries an
 * {@code Error} with Severity {@code Fatal}, a single {@code Trace} (EventID + LogReference), the
 * VSDM/OM error code, and a short non-PII text. No stack traces or insured data are exposed
 * (FR-024, FR-025, VSDM-A_2682).
 */
@ApplicationScoped
public class VsdmFaultFactory {

    public FaultMessage fault(VsdmReadException e) {
        int code = e.errorCode();
        return new FaultMessage(messageFor(code), buildError(code, textFor(code, e.detail())));
    }

    private static Error buildError(int code, String text) {
        Error error = new Error();
        error.setMessageID("VSDM");
        try {
            error.setTimestamp(DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar()));
        } catch (Exception ignored) {
            // timestamp is best-effort; never fail fault construction
        }
        Error.Trace trace = new Error.Trace();
        trace.setEventID(code > 0 ? Integer.toString(code) : "VSDM");
        trace.setInstance("konnektor-soap-server");
        trace.setLogReference("");
        trace.setCompType("FM_VSDM");
        trace.setCode(BigInteger.valueOf(code > 0 ? code : 4001));
        trace.setSeverity("Fatal");
        trace.setErrorType("Technical");
        trace.setErrorText(text);
        error.getTrace().add(trace);
        return error;
    }

    private static String messageFor(int code) {
        return "ReadVSD failed (" + (code > 0 ? code : "rejected") + ")";
    }

    private static String textFor(int code, String detail) {
        String base = switch (code) {
            case VsdmErrorCode.VSD_INCONSISTENT -> "VSD nicht konsistent";
            case VsdmErrorCode.VSD_READ_FAILED -> "Verarbeiten der Versichertendaten gescheitert";
            case VsdmErrorCode.SMB_NOT_ENABLED -> "SM-B nicht freigeschaltet";
            case VsdmErrorCode.HBA_NOT_ENABLED -> "HBA nicht freigeschaltet";
            case VsdmErrorCode.EGK_CERT_REVOKED -> "Authentifizierungszertifikat der eGK gesperrt";
            case VsdmErrorCode.EGK_CERT_INVALID -> "Authentifizierungszertifikat der eGK ungueltig";
            case VsdmErrorCode.HCA_BLOCKED -> "Gesundheitsanwendung auf eGK gesperrt";
            case VsdmErrorCode.ONLINE_CHECK_NOT_SUPPORTED -> "Online-Pruefung nicht unterstuetzt (lokaler Dienst)";
            case VsdmErrorCode.RECEIPT_NOT_SUPPORTED -> "Pruefungsnachweis nicht unterstuetzt (lokaler Dienst)";
            case VsdmErrorCode.CARD_BUSY -> "eGK wird bereits verwendet";
            case VsdmErrorCode.TIMEOUT -> "Zeitueberschreitung beim Lesen der VSD";
            case VsdmErrorCode.UNSUPPORTED_CARD_GENERATION -> "eGK-Generation nicht unterstuetzt";
            default -> "Ungueltige Anfrage";
        };
        return detail == null ? base : base + " (" + detail + ")";
    }
}
