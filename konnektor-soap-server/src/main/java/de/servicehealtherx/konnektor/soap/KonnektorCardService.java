package de.servicehealtherx.konnektor.soap;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.buildError;
import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.okStatus;

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
import de.servicehealtherx.crypto.model.PinStatusResult;
import de.servicehealtherx.crypto.model.PinVerificationResult;
import de.servicehealtherx.crypto.services.SignatureService;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.inject.Inject;
import jakarta.jws.WebService;

import java.math.BigInteger;

@CXFEndpoint(value = "/conn/CardService")
@WebService(portName = "CardServicePort", serviceName = "CardService", targetNamespace = "http://ws.gematik.de/conn/CardService/WSDL/v8.1", endpointInterface = "de.gematik.ws.conn.cardservice.wsdl.v8_1.CardServicePortType")
public class KonnektorCardService implements CardServicePortType {

    @Inject
    SignatureService signatureService;

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
            PinVerificationResult result = signatureService.verifyPin(
                    parameter.getCardHandle(), parameter.getPinTyp(), "konnektor-soap");

            PinResponseType response = new PinResponseType();
            response.setStatus(okStatus());
            response.setPinResult(toPinResult(result.status()));
            if (result.triesRemaining() >= 0) {
                response.setLeftTries(BigInteger.valueOf(result.triesRemaining()));
            }
            return response;
        } catch (Exception e) {
            throw new FaultMessage("VerifyPin failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    private static PinResultEnum toPinResult(PinVerificationResult.Status status) {
        return switch (status) {
            case VERIFIED -> PinResultEnum.OK;
            case WRONG -> PinResultEnum.REJECTED;
            case BLOCKED -> PinResultEnum.NOWBLOCKED;
            case TRANSPORT_PIN -> PinResultEnum.TRANSPORT_PIN;
            case ERROR -> PinResultEnum.ERROR;
        };
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
            PinStatusResult result = signatureService.getPinStatus(
                    parameter.getCardHandle(), parameter.getPinTyp(), "konnektor-soap");

            GetPinStatusResponse response = new GetPinStatusResponse();
            response.setStatus(okStatus());
            response.setPinStatus(toPinStatus(result.status()));
            if (result.triesRemaining() >= 0) {
                response.setLeftTries(BigInteger.valueOf(result.triesRemaining()));
            }
            return response;
        } catch (Exception e) {
            throw new FaultMessage("GetPinStatus failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    private static PinStatusEnum toPinStatus(PinStatusResult.Status status) throws FaultMessage {
        return switch (status) {
            case VERIFIED -> PinStatusEnum.VERIFIED;
            case VERIFIABLE -> PinStatusEnum.VERIFIABLE;
            case TRANSPORT_PIN -> PinStatusEnum.TRANSPORT_PIN;
            case EMPTY_PIN -> PinStatusEnum.EMPTY_PIN;
            case BLOCKED -> PinStatusEnum.BLOCKED;
            // The v8.1 CardService WSDL on this classpath has no DISABLED literal; a disabled
            // verification requirement and any unmapped status word both surface as a fault.
            case DISABLED -> throw new FaultMessage(
                    "GetPinStatus failed: PIN verification requirement is disabled",
                    buildError("PIN verification requirement disabled"));
            case ERROR -> throw new FaultMessage(
                    "GetPinStatus failed: card returned an unmapped PIN status",
                    buildError("Unmapped card PIN status"));
        };
    }
}
