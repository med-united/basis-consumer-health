package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.signatureservice.v7.DocumentType;
import de.gematik.ws.conn.signatureservice.v7.GetJobNumber;
import de.gematik.ws.conn.signatureservice.v7.GetJobNumberResponse;
import de.gematik.ws.conn.signatureservice.v7.SignDocument;
import de.gematik.ws.conn.signatureservice.v7.SignDocumentResponse;
import de.gematik.ws.conn.signatureservice.v7.SignRequest;
import de.gematik.ws.conn.signatureservice.v7.SignResponse;
import de.gematik.ws.conn.signatureservice.v7.StopSignature;
import de.gematik.ws.conn.signatureservice.v7.StopSignatureResponse;
import de.gematik.ws.conn.signatureservice.v7.VerificationResultType;
import de.gematik.ws.conn.signatureservice.v7.VerifyDocument;
import de.gematik.ws.conn.signatureservice.v7.VerifyDocumentResponse;
import de.gematik.ws.conn.signatureservice.wsdl.v7_4.FaultMessage;
import de.gematik.ws.conn.signatureservice.wsdl.v7_4.SignatureServicePortType;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.SignatureService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import oasis.names.tc.dss._1_0.core.schema.Base64Data;

import javax.xml.datatype.DatatypeFactory;
import java.util.GregorianCalendar;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.*;

@ApplicationScoped
@WebService(
    portName = "SignatureServicePort",
    serviceName = "SignatureService",
    targetNamespace = "http://ws.gematik.de/conn/SignatureService/WSDL/v7.4",
    endpointInterface = "de.gematik.ws.conn.signatureservice.wsdl.v7_4.SignatureServicePortType"
)
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
public class KonnektorSignatureService implements SignatureServicePortType {

    @Inject
    SignatureService signatureService;

    @Override
    public SignDocumentResponse signDocument(SignDocument parameter) throws FaultMessage {
        try {
            KeyAlias alias = toKeyAlias(parameter.getCardHandle());
            boolean eccPreferred = "ECC".equals(parameter.getCrypt());
            SignDocumentResponse response = new SignDocumentResponse();

            for (SignRequest req : parameter.getSignRequest()) {
                byte[] docBytes = extractDocumentBytes(req.getDocument());
                SignatureService.SignRequest internalReq = new SignatureService.SignRequest(
                    alias, SignatureService.SignatureFormat.CADES, docBytes,
                    "SHA256withECDSA", "konnektor-soap", false, eccPreferred);

                SignatureService.SignResult result = signatureService.signDocument(internalReq);

                SignResponse signResponse = new SignResponse();
                signResponse.setStatus(okStatus());
                signResponse.setRequestID(req.getRequestID());

                SignResponse.OptionalOutputs outputs = new SignResponse.OptionalOutputs();
                DocumentType signedDoc = new DocumentType();
                Base64Data base64Data = new Base64Data();
                base64Data.setValue(result.signedDocument());
                signedDoc.setBase64Data(base64Data);
                outputs.setDocumentWithSignature(signedDoc);
                signResponse.setOptionalOutputs(outputs);

                response.getSignResponse().add(signResponse);
            }
            return response;
        } catch (Exception e) {
            throw new FaultMessage("SignDocument failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public GetJobNumberResponse getJobNumber(GetJobNumber parameter) throws FaultMessage {
        GetJobNumberResponse response = new GetJobNumberResponse();
        response.setJobNumber("0");
        return response;
    }

    @Override
    public VerifyDocumentResponse verifyDocument(VerifyDocument parameter) throws FaultMessage {
        try {
            byte[] docBytes = parameter.getDocument() != null
                ? extractDocumentBytes(parameter.getDocument()) : new byte[0];

            SignatureService.VerifyRequest req = new SignatureService.VerifyRequest(
                docBytes, SignatureService.SignatureFormat.CADES, "konnektor-soap");

            SignatureService.VerifyResult result = signatureService.verifyDocument(req);

            VerifyDocumentResponse response = new VerifyDocumentResponse();
            response.setStatus(okStatus());
            response.setVerificationResult(toVerificationResultType(result));
            return response;
        } catch (Exception e) {
            throw new FaultMessage("VerifyDocument failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public StopSignatureResponse stopSignature(StopSignature parameter) throws FaultMessage {
        StopSignatureResponse response = new StopSignatureResponse();
        response.setStatus(okStatus());
        return response;
    }

    private static byte[] extractDocumentBytes(DocumentType doc) {
        if (doc == null) return new byte[0];
        if (doc.getBase64XML() != null) return doc.getBase64XML();
        if (doc.getBase64Data() != null && doc.getBase64Data().getValue() != null) {
            return doc.getBase64Data().getValue();
        }
        return new byte[0];
    }

    private static VerificationResultType toVerificationResultType(SignatureService.VerifyResult result) {
        VerificationResultType vr = new VerificationResultType();
        vr.setHighLevelResult(switch (result.result()) {
            case VALID -> "VALID";
            case INVALID -> "INVALID";
            default -> "INCONCLUSIVE";
        });
        vr.setTimestampType("SYSTEM_TIMESTAMP");
        try {
            vr.setTimestamp(DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar()));
        } catch (Exception ignored) {}
        return vr;
    }
}
