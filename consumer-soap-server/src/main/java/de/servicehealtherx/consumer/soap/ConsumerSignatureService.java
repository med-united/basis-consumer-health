package de.servicehealtherx.consumer.soap;

import de.gematik.ws.consumer.consumercommon.v2.Status;
import de.gematik.ws.consumer.signatureservice.v3.BinaryDocumentType;
import de.gematik.ws.consumer.signatureservice.v3.DocumentType;
import de.gematik.ws.consumer.signatureservice.v3.ExternalAuthenticate;
import de.gematik.ws.consumer.signatureservice.v3.ExternalAuthenticateResponse;
import de.gematik.ws.consumer.signatureservice.v3.PlainSignRequest;
import de.gematik.ws.consumer.signatureservice.v3.PlainSignResponse;
import de.gematik.ws.consumer.signatureservice.v3.SignDocument;
import de.gematik.ws.consumer.signatureservice.v3.SignDocumentResponse;
import de.gematik.ws.consumer.signatureservice.v3.SignPlain;
import de.gematik.ws.consumer.signatureservice.v3.SignPlainResponse;
import de.gematik.ws.consumer.signatureservice.v3.SignRequest;
import de.gematik.ws.consumer.signatureservice.v3.SignResponse;
import de.gematik.ws.consumer.signatureservice.v3.VerificationResultType;
import de.gematik.ws.consumer.signatureservice.v3.VerifyDocument;
import de.gematik.ws.consumer.signatureservice.v3.VerifyDocumentResponse;
import de.gematik.ws.consumer.signatureservice.wsdl.v3_2.FaultMessage;
import de.gematik.ws.consumer.signatureservice.wsdl.v3_2.SignatureServicePortType;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.SignatureService;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import oasis.names.tc.dss._1_0.core.schema.Base64Data;
import oasis.names.tc.dss._1_0.core.schema.Base64Signature;
import oasis.names.tc.dss._1_0.core.schema.SignatureObject;

import javax.xml.datatype.DatatypeFactory;
import java.util.GregorianCalendar;

import static de.servicehealtherx.consumer.soap.ConsumerServiceHelper.*;

@CXFEndpoint(value = "/ws/consumer/SignatureService")
@WebService(portName = "SignatureServicePort", serviceName = "SignatureService", targetNamespace = "http://ws.gematik.de/consumer/SignatureService/WSDL/v3.2", endpointInterface = "de.gematik.ws.consumer.signatureservice.wsdl.v3_2.SignatureServicePortType")
public class ConsumerSignatureService implements SignatureServicePortType {

    @Inject
    SignatureService signatureService;

    @Override
    public SignDocumentResponse signDocument(SignDocument parameter) throws FaultMessage {
        try {
            KeyAlias alias = toKeyAlias(parameter.getPrivateKeyOnCard().getCardHandle());
            SignDocumentResponse response = new SignDocumentResponse();

            for (SignRequest req : parameter.getSignRequest()) {
                byte[] docBytes = extractSignDocumentBytes(req.getDocument());
                boolean eccPreferred = "ECC".equals(parameter.getPrivateKeyOnCard().getCrypt());

                SignatureService.SignRequest internalReq = new SignatureService.SignRequest(
                        alias, SignatureService.SignatureFormat.CADES, docBytes,
                        "SHA256withECDSA", "consumer-soap", false, eccPreferred);

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
    public SignPlainResponse signPlain(SignPlain parameter) throws FaultMessage {
        try {
            KeyAlias alias = toKeyAlias(parameter.getCardHandle());
            SignPlainResponse response = new SignPlainResponse();

            for (PlainSignRequest req : parameter.getPlainSignRequest()) {
                byte[] data = extractBinaryBytes(req.getBinaryString());
                byte[] signature = signatureService.signPlain(alias, data, "consumer-soap");

                PlainSignResponse plainResponse = new PlainSignResponse();
                plainResponse.setStatus(okStatus());
                plainResponse.setRequestID(req.getRequestID());

                Base64Signature base64Sig = new Base64Signature();
                base64Sig.setValue(signature);
                SignatureObject sigObj = new SignatureObject();
                sigObj.setBase64Signature(base64Sig);
                plainResponse.setSignatureObject(sigObj);

                response.getPlainSignResponse().add(plainResponse);
            }
            return response;
        } catch (Exception e) {
            throw new FaultMessage("SignPlain failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public ExternalAuthenticateResponse externalAuthenticate(ExternalAuthenticate parameter) throws FaultMessage {
        try {
            KeyAlias alias = toKeyAlias(parameter.getCardHandle());
            byte[] hashBytes = extractBinaryBytes(parameter.getBinaryString());
            String signatureType = parameter.getOptionalInputs() != null
                    ? parameter.getOptionalInputs().getSignatureType()
                    : null;

            SignatureService.SignHashRequest req = new SignatureService.SignHashRequest(
                    alias, signatureType, hashBytes, "consumer-soap");

            byte[] signature = signatureService.externalAuthenticate(req);

            ExternalAuthenticateResponse response = new ExternalAuthenticateResponse();
            response.setStatus(okStatus());

            Base64Signature base64Sig = new Base64Signature();
            base64Sig.setValue(signature);
            SignatureObject sigObj = new SignatureObject();
            sigObj.setBase64Signature(base64Sig);
            response.setSignatureObject(sigObj);

            return response;
        } catch (Exception e) {
            throw new FaultMessage("ExternalAuthenticate failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public VerifyDocumentResponse verifyDocument(VerifyDocument parameter) throws FaultMessage {
        try {
            byte[] docBytes = parameter.getDocument() != null
                    ? extractSignDocumentBytes(parameter.getDocument())
                    : new byte[0];

            SignatureService.VerifyRequest req = new SignatureService.VerifyRequest(
                    docBytes, SignatureService.SignatureFormat.CADES, "consumer-soap");

            SignatureService.VerifyResult result = signatureService.verifyDocument(req);

            VerifyDocumentResponse response = new VerifyDocumentResponse();
            response.setStatus(okStatus());
            response.setVerificationResult(toVerificationResultType(result));
            return response;
        } catch (FaultMessage e) {
            throw e;
        } catch (Exception e) {
            throw new FaultMessage("VerifyDocument failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    private static byte[] extractSignDocumentBytes(DocumentType doc) {
        if (doc == null)
            return new byte[0];
        if (doc.getBase64XML() != null)
            return doc.getBase64XML();
        if (doc.getBase64Data() != null && doc.getBase64Data().getValue() != null) {
            return doc.getBase64Data().getValue();
        }
        return new byte[0];
    }

    private static byte[] extractBinaryBytes(BinaryDocumentType doc) {
        if (doc == null || doc.getBase64Data() == null)
            return new byte[0];
        return doc.getBase64Data().getValue() != null ? doc.getBase64Data().getValue() : new byte[0];
    }

    private static VerificationResultType toVerificationResultType(SignatureService.VerifyResult result)
            throws Exception {
        VerificationResultType vr = new VerificationResultType();
        vr.setHighLevelResult(switch (result.result()) {
            case VALID -> "VALID";
            case INVALID -> "INVALID";
            default -> "INCONCLUSIVE";
        });
        vr.setTimestampType("SYSTEM_TIMESTAMP");
        vr.setTimestamp(DatatypeFactory.newInstance()
                .newXMLGregorianCalendar(new GregorianCalendar()));
        return vr;
    }
}
