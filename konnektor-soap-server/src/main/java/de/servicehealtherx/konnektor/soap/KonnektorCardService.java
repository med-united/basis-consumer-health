package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.cardservice.v8.AuthorizeSmc;
import de.gematik.ws.conn.cardservice.v8.AuthorizeSmcResponse;
import de.gematik.ws.conn.cardservice.v8.ChangePin;
import de.gematik.ws.conn.cardservice.v8.GetPinStatus;
import de.gematik.ws.conn.cardservice.v8.GetPinStatusResponse;
import de.gematik.ws.conn.cardservice.v8.PinStatusEnum;
import de.gematik.ws.conn.cardservice.v8.UnblockPin;
import de.gematik.ws.conn.cardservice.v8.VerifyPin;
import de.gematik.ws.conn.cardservice.wsdl.v8_1.CardServicePortType;
import de.gematik.ws.conn.cardservice.wsdl.v8_1.FaultMessage;
import de.gematik.ws.conn.cardservicecommon.v2.PinResponseType;
import de.gematik.ws.conn.cardservicecommon.v2.PinResultEnum;
import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.*;

@ApplicationScoped
@WebService(
    portName = "CardServicePort",
    serviceName = "CardService",
    targetNamespace = "http://ws.gematik.de/conn/CardService/WSDL/v8.1",
    endpointInterface = "de.gematik.ws.conn.cardservice.wsdl.v8_1.CardServicePortType"
)
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
public class KonnektorCardService implements CardServicePortType {

    @Inject
    SicctTerminalManager sicctTerminalManager;

    @Override
    public PinResponseType changePin(ChangePin parameter) throws FaultMessage {
        try {
            PinResponseType response = new PinResponseType();
            response.setStatus(okStatus());
            response.setPinResult(PinResultEnum.OK);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("ChangePin failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public PinResponseType verifyPin(VerifyPin parameter) throws FaultMessage {
        try {
            PinResponseType response = new PinResponseType();
            response.setStatus(okStatus());
            response.setPinResult(PinResultEnum.OK);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("VerifyPin failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public AuthorizeSmcResponse authorizeSMC(AuthorizeSmc parameter) throws FaultMessage {
        try {
            AuthorizeSmcResponse response = new AuthorizeSmcResponse();
            response.setStatus(okStatus());
            return response;
        } catch (Exception e) {
            throw new FaultMessage("AuthorizeSMC failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public PinResponseType unblockPin(UnblockPin parameter) throws FaultMessage {
        try {
            PinResponseType response = new PinResponseType();
            response.setStatus(okStatus());
            response.setPinResult(PinResultEnum.OK);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("UnblockPin failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public GetPinStatusResponse getPinStatus(GetPinStatus parameter) throws FaultMessage {
        try {
            String cardHandle = parameter.getCardHandle();
            boolean connected = cardHandle != null
                && sicctTerminalManager.getConnections().containsKey(cardHandle);

            GetPinStatusResponse response = new GetPinStatusResponse();
            response.setStatus(okStatus());
            response.setPinStatus(connected ? PinStatusEnum.VERIFIABLE : PinStatusEnum.VERIFIABLE);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("GetPinStatus failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }
}
