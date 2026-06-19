package de.servicehealtherx.konnektor.soap;

import java.util.GregorianCalendar;

import javax.xml.datatype.DatatypeFactory;

import de.gematik.ws.conn.connectorcontext.v2.ContextType;
import de.gematik.ws.conn.vsds.vsdservice.v5.ReadVSD;
import de.gematik.ws.conn.vsds.vsdservice.v5.ReadVSDResponse;
import de.gematik.ws.conn.vsds.vsdservice.v5.VSDStatusType;
import de.gematik.ws.conn.vsds.vsdservice.v5_2.FaultMessage;
import de.gematik.ws.conn.vsds.vsdservice.v5_2.VSDServicePortType;
import de.servicehealtherx.konnektor.vsdm.ReadVsdRequest;
import de.servicehealtherx.konnektor.vsdm.ReadVsdService;
import de.servicehealtherx.konnektor.vsdm.VsdReadResult;
import de.servicehealtherx.konnektor.vsdm.VsdStatus;
import de.servicehealtherx.konnektor.vsdm.VsdmReadException;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.inject.Inject;
import jakarta.jws.WebService;

/**
 * SOAP endpoint for the gematik VSDM {@code ReadVSD} operation (local read only). Delegates the card
 * work to {@link ReadVsdService} and maps the result to {@link ReadVSDResponse}; on any abort it
 * throws a gematik {@link FaultMessage} built by {@link VsdmFaultFactory}.
 *
 * <p>Served at {@code /ws/conn/VSDService}. The response never carries a {@code Pruefungsnachweis}
 * (out of scope); {@code GeschuetzteVersichertendaten} is set only when C2C authorised it (FR-004).
 */
@CXFEndpoint(value = "/conn/VSDService")
@WebService(portName = "VSDServicePort", serviceName = "VSDService",
        targetNamespace = "http://ws.gematik.de/conn/vsds/VSDService/v5.2",
        endpointInterface = "de.gematik.ws.conn.vsds.vsdservice.v5_2.VSDServicePortType")
public class KonnektorVSDService implements VSDServicePortType {

    @Inject
    ReadVsdService readVsdService;

    @Inject
    VsdmFaultFactory faultFactory;

    @Override
    public ReadVSDResponse readVSD(ReadVSD parameter) throws FaultMessage {
        try {
            VsdReadResult result = readVsdService.read(toRequest(parameter));
            return toResponse(result);
        } catch (VsdmReadException e) {
            throw faultFactory.fault(e);
        }
    }

    private static ReadVsdRequest toRequest(ReadVSD p) {
        ContextType ctx = p.getContext();
        return new ReadVsdRequest(
                p.getEhcHandle(),
                p.getHpcHandle(),
                p.isPerformOnlineCheck(),
                p.isReadOnlineReceipt(),
                ctx == null ? null : ctx.getMandantId(),
                ctx == null ? null : ctx.getClientSystemId(),
                ctx == null ? null : ctx.getWorkplaceId(),
                ctx == null ? null : ctx.getUserId());
    }

    private static ReadVSDResponse toResponse(VsdReadResult result) {
        ReadVSDResponse response = new ReadVSDResponse();
        response.setPersoenlicheVersichertendaten(result.personalData());
        response.setAllgemeineVersicherungsdaten(result.generalData());
        result.protectedData().ifPresent(response::setGeschuetzteVersichertendaten);
        response.setVSDStatus(toStatus(result.status()));
        // Pruefungsnachweis intentionally left unset (out of scope, FR-009)
        return response;
    }

    private static VSDStatusType toStatus(VsdStatus status) {
        VSDStatusType type = new VSDStatusType();
        type.setStatus(status.status());
        type.setVersion(status.version());
        try {
            GregorianCalendar gc = GregorianCalendar.from(status.timestamp().toZonedDateTime());
            type.setTimestamp(DatatypeFactory.newInstance().newXMLGregorianCalendar(gc));
        } catch (Exception ignored) {
            // timestamp formatting is best-effort; the read itself succeeded
        }
        return type;
    }
}
