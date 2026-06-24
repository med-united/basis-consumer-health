package de.servicehealtherx.konnektor.soap;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.buildError;
import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.okStatus;

import de.gematik.ws.conn.cardservice.v8_2.ChangePin;
import de.gematik.ws.conn.cardservice.v8_2.DisablePin;
import de.gematik.ws.conn.cardservice.v8_2.EnablePin;
import de.gematik.ws.conn.cardservice.v8_2.GetPinStatus;
import de.gematik.ws.conn.cardservice.v8_2.GetPinStatusResponse;
import de.gematik.ws.conn.cardservice.v8_2.PinStatusEnum;
import de.gematik.ws.conn.cardservice.v8_2.SecureSendAPDU;
import de.gematik.ws.conn.cardservice.v8_2.SecureSendAPDUResponse;
import de.gematik.ws.conn.cardservice.v8_2.SignedScenarioResponseType;
import de.gematik.ws.conn.cardservice.v8_2.StartCardSession;
import de.gematik.ws.conn.cardservice.v8_2.StartCardSessionResponse;
import de.gematik.ws.conn.cardservice.v8_2.StopCardSession;
import de.gematik.ws.conn.cardservice.v8_2.StopCardSessionResponse;
import de.gematik.ws.conn.cardservice.v8_2.UnblockPin;
import de.gematik.ws.conn.cardservice.v8_2.VerifyPin;
import de.gematik.ws.conn.cardservice.wsdl.v8_2.CardServicePortType;
import de.gematik.ws.conn.cardservice.wsdl.v8_2.FaultMessage;
import de.gematik.ws.conn.cardservicecommon.v2.PinResponseType;
import de.gematik.ws.conn.cardservicecommon.v2.PinResultEnum;
import de.servicehealtherx.crypto.model.PinStatusResult;
import de.servicehealtherx.crypto.model.PinVerificationResult;
import de.servicehealtherx.crypto.services.CardCommandService;
import de.servicehealtherx.crypto.services.SignatureService;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.inject.Inject;
import jakarta.jws.WebService;

import java.math.BigInteger;
import java.util.HexFormat;

@CXFEndpoint(value = "/conn/CardService")
@WebService(portName = "CardServicePort", serviceName = "CardService", targetNamespace = "http://ws.gematik.de/conn/CardService/WSDL/v8.2", endpointInterface = "de.gematik.ws.conn.cardservice.wsdl.v8_2.CardServicePortType")
public class KonnektorCardService implements CardServicePortType {

    @Inject
    SignatureService signatureService;

    @Inject
    CardCommandService cardCommandService;

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
    public PinResponseType enablePin(EnablePin parameter) throws FaultMessage {
        try {
            PinResponseType response = new PinResponseType();
            response.setStatus(okStatus());
            response.setPinResult(PinResultEnum.OK);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("EnablePin failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public PinResponseType disablePin(DisablePin parameter) throws FaultMessage {
        try {
            PinResponseType response = new PinResponseType();
            response.setStatus(okStatus());
            response.setPinResult(PinResultEnum.OK);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("DisablePin failed: " + e.getMessage(), buildError(e.getMessage()));
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
            // The CardService WSDL on this classpath has no DISABLED literal; a disabled
            // verification requirement and any unmapped status word both surface as a fault.
            case DISABLED -> throw new FaultMessage(
                    "GetPinStatus failed: PIN verification requirement is disabled",
                    buildError("PIN verification requirement disabled"));
            case ERROR -> throw new FaultMessage(
                    "GetPinStatus failed: card returned an unmapped PIN status",
                    buildError("Unmapped card PIN status"));
        };
    }

    @Override
    public StartCardSessionResponse startCardSession(StartCardSession parameter) throws FaultMessage {
        try {
            String cardHandle = parameter.getCardHandle();
            if (cardHandle == null || cardHandle.isBlank()) {
                throw new IllegalArgumentException("CardHandle must not be empty");
            }
            String sessionId = cardCommandService.startCardSession(cardHandle, sessionHolder(parameter));

            StartCardSessionResponse response = new StartCardSessionResponse();
            response.setStatus(okStatus());
            response.setSessionId(sessionId);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("StartCardSession failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public StopCardSessionResponse stopCardSession(StopCardSession parameter) throws FaultMessage {
        try {
            String sessionId = parameter.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("SessionId must not be empty");
            }
            cardCommandService.stopCardSession(sessionId);

            StopCardSessionResponse response = new StopCardSessionResponse();
            response.setStatus(okStatus());
            return response;
        } catch (Exception e) {
            throw new FaultMessage("StopCardSession failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public SecureSendAPDUResponse secureSendAPDU(SecureSendAPDU parameter) throws FaultMessage {
        try {
            // Forwards the signed APDU scenario (compact JWS) to the inserted card via the PC/SC or
            // SICCT provider holding the open session, returning the collected ResponseAPDUs.
            CardCommandService.ApduResponse result =
                    cardCommandService.secureSendApdu(parameter.getSignedScenario());

            SignedScenarioResponseType signedScenarioResponse = new SignedScenarioResponseType();
            SignedScenarioResponseType.ResponseApduList apduList =
                    new SignedScenarioResponseType.ResponseApduList();
            for (byte[] apdu : result.responseApdus()) {
                // Schema requires lower-case hex of at least two octets (pattern ([0-9a-fA-F]{2}){2,}).
                apduList.getResponseApdu().add(HexFormat.of().formatHex(apdu));
            }
            signedScenarioResponse.setResponseApduList(apduList);
            signedScenarioResponse.setTimeSpan(BigInteger.valueOf(result.timeSpanMillis()));

            SecureSendAPDUResponse response = new SecureSendAPDUResponse();
            response.setStatus(okStatus());
            response.setSignedScenarioResponse(signedScenarioResponse);
            return response;
        } catch (Exception e) {
            throw new FaultMessage("SecureSendAPDU failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    /**
     * The session lock holder identity for an eGK session (TUC_KON_223). Derived from the calling
     * context's MandantId so concurrent callers from different mandants are distinguishable; falls
     * back to a fixed label when no context is supplied.
     */
    private static String sessionHolder(StartCardSession parameter) {
        if (parameter.getContext() != null && parameter.getContext().getMandantId() != null) {
            return parameter.getContext().getMandantId();
        }
        return "konnektor-soap";
    }
}
