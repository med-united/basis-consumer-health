package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.cardterminalservice.v1.EjectCard;
import de.gematik.ws.conn.cardterminalservice.v1.EjectCardResponse;
import de.gematik.ws.conn.cardterminalservice.v1.RequestCard;
import de.gematik.ws.conn.cardterminalservice.v1.RequestCardResponse;
import de.gematik.ws.conn.cardterminalservice.wsdl.v1_1.CardTerminalServicePortType;
import de.gematik.ws.conn.cardterminalservice.wsdl.v1_1.FaultMessage;
import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalManager;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.*;

@CXFEndpoint(value = "/conn/CardTerminalService")
@WebService(portName = "CardTerminalServicePort", serviceName = "CardTerminalService", targetNamespace = "http://ws.gematik.de/conn/CardTerminalService/WSDL/v1.1", endpointInterface = "de.gematik.ws.conn.cardterminalservice.wsdl.v1_1.CardTerminalServicePortType")
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
public class KonnektorCardTerminalService implements CardTerminalServicePortType {

    @Inject
    SicctTerminalManager sicctTerminalManager;

    @Override
    public RequestCardResponse requestCard(RequestCard parameter) throws FaultMessage {
        try {
            RequestCardResponse response = new RequestCardResponse();
            response.setStatus(okStatus());
            response.setAlreadyInserted(false);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("RequestCard failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public EjectCardResponse ejectCard(EjectCard parameter) throws FaultMessage {
        try {
            EjectCardResponse response = new EjectCardResponse();
            response.setStatus(okStatus());
            return response;
        } catch (Exception e) {
            throw new FaultMessage("EjectCard failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }
}
