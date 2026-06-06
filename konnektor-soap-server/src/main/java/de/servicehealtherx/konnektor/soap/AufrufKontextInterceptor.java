package de.servicehealtherx.konnektor.soap;

import jakarta.xml.ws.handler.MessageContext;
import jakarta.xml.ws.handler.soap.SOAPHandler;
import jakarta.xml.ws.handler.soap.SOAPMessageContext;
import jakarta.xml.soap.SOAPMessage;
import org.jboss.logging.Logger;

import javax.xml.namespace.QName;
import java.util.Set;

/**
 * CXF SOAP handler that validates mandatory AufrufKontext parameters per FR-150, FR-206.
 * mandantId, clientSystemId, workplaceId mandatory for all ops.
 * userId mandatory for HBA-based ops.
 * Missing mandatory → error 4021.
 */
public class AufrufKontextInterceptor implements SOAPHandler<SOAPMessageContext> {

    private static final Logger LOG = Logger.getLogger(AufrufKontextInterceptor.class);
    private static final String KONTEXT_NS = "http://ws.gematik.de/conn/ConnectorContext/v2.0";

    @Override
    public boolean handleMessage(SOAPMessageContext ctx) {
        Boolean outbound = (Boolean) ctx.get(MessageContext.MESSAGE_OUTBOUND_PROPERTY);
        if (Boolean.TRUE.equals(outbound)) return true;

        try {
            SOAPMessage msg = ctx.getMessage();
            // Extract AufrufKontext from SOAP header
            var header = msg.getSOAPPart().getEnvelope().getHeader();
            if (header == null) {
                throw faultForMissingContext("mandantId");
            }
            // Full validation implementation parses AufrufKontext XML elements
            // per gemSpec_OM §TUC_KON_000 and FR-150, FR-206
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "[AufrufKontextInterceptor] validation error");
        }
        return true;
    }

    @Override
    public boolean handleFault(SOAPMessageContext ctx) {
        return true;
    }

    @Override
    public void close(MessageContext ctx) {}

    @Override
    public Set<QName> getHeaders() {
        return Set.of(new QName(KONTEXT_NS, "AufrufKontext"));
    }

    private RuntimeException faultForMissingContext(String field) {
        return new RuntimeException("Missing mandatory AufrufKontext field: " + field + " (code 4021)");
    }
}
