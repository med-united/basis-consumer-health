package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.signatureservice.v7.ActivateComfortSignature;
import de.gematik.ws.conn.signatureservice.v7.ActivateComfortSignatureResponse;
import de.gematik.ws.conn.signatureservice.v7.DeactivateComfortSignature;
import de.gematik.ws.conn.signatureservice.v7.DeactivateComfortSignatureResponse;
import de.gematik.ws.conn.signatureservice.v7.DocumentType;
import de.gematik.ws.conn.signatureservice.v7.GetJobNumber;
import de.gematik.ws.conn.signatureservice.v7.GetJobNumberResponse;
import de.gematik.ws.conn.signatureservice.v7.GetSignatureMode;
import de.gematik.ws.conn.signatureservice.v7.GetSignatureModeResponse;
import de.gematik.ws.conn.signatureservice.v7.SignDocument;
import de.gematik.ws.conn.signatureservice.v7.SignDocumentResponse;
import de.gematik.ws.conn.signatureservice.v7.SignRequest;
import de.gematik.ws.conn.signatureservice.v7.SignResponse;
import de.gematik.ws.conn.signatureservice.v7.StopSignature;
import de.gematik.ws.conn.signatureservice.v7.StopSignatureResponse;
import de.gematik.ws.conn.signatureservice.v7.VerificationResultType;
import de.gematik.ws.conn.signatureservice.v7.VerifyDocument;
import de.gematik.ws.conn.signatureservice.v7.VerifyDocumentResponse;
import de.gematik.ws.conn.signatureservice.wsdl.v7_5.FaultMessage;
import de.gematik.ws.conn.signatureservice.wsdl.v7_5.SignatureServicePortType;
import de.servicehealtherx.crypto.services.SignatureService;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import oasis.names.tc.dss._1_0.core.schema.Base64Data;
import oasis.names.tc.dss._1_0.core.schema.Base64Signature;
import oasis.names.tc.dss._1_0.core.schema.SignatureObject;

import javax.xml.datatype.DatatypeFactory;
import java.util.GregorianCalendar;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.*;

@CXFEndpoint(value = "/conn/SignatureService")
@WebService(portName = "SignatureServicePort", serviceName = "SignatureService", targetNamespace = "http://ws.gematik.de/conn/SignatureService/WSDL/v7.5", endpointInterface = "de.gematik.ws.conn.signatureservice.wsdl.v7_5.SignatureServicePortType")
public class KonnektorSignatureService implements SignatureServicePortType {

    @Inject
    SignatureService signatureService;

    /** DSS SignatureType (RFC 3275) requesting an XML signature → XAdES. */
    private static final String SIGNATURE_TYPE_XADES = "urn:ietf:rfc:3275";
    /** DSS SignatureType (RFC 3369 / CMS) requesting a CMS signature → CAdES. */
    private static final String SIGNATURE_TYPE_CADES = "urn:ietf:rfc:3369";
    /** DSS SignatureType (ETSI TS 102 778-3) requesting a PDF signature → PAdES. */
    private static final String SIGNATURE_TYPE_PADES = "http://uri.etsi.org/02778/3";

    @Override
    public SignDocumentResponse signDocument(SignDocument parameter) throws FaultMessage {
        try {
            String cardHandle = parameter.getCardHandle();
            SignDocumentResponse response = new SignDocumentResponse();

            for (SignRequest req : parameter.getSignRequest()) {
                byte[] docBytes = extractDocumentBytes(req.getDocument());
                SignatureService.SignatureFormat format = resolveFormat(req);
                boolean includeEContent = resolveIncludeEContent(req);
                byte[] signature = signatureService.signDocumentWithCard(
                        cardHandle, docBytes, format, includeEContent, "konnektor-soap");

                SignResponse signResponse = new SignResponse();
                signResponse.setStatus(okStatus());
                signResponse.setRequestID(req.getRequestID());

                SignResponse.OptionalOutputs outputs = new SignResponse.OptionalOutputs();
                signResponse.setOptionalOutputs(outputs);

                SignatureObject signatureObject = new SignatureObject();
                Base64Signature base64Data = new Base64Signature();
                base64Data.setValue(signature);
                signatureObject.setBase64Signature(base64Data);
                signResponse.setSignatureObject(signatureObject);

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
                    ? extractDocumentBytes(parameter.getDocument())
                    : new byte[0];

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

    /**
     * Map the DSS {@code SignatureType} URN in the request's OptionalInputs to the internal advanced
     * signature format. An absent or unrecognised type defaults to CAdES — the format used for the
     * qualified e-prescription signature this connector primarily serves.
     */
    private static SignatureService.SignatureFormat resolveFormat(SignRequest req) {
        String type = req.getOptionalInputs() != null ? req.getOptionalInputs().getSignatureType() : null;
        if (type == null || type.isBlank()) {
            return SignatureService.SignatureFormat.CADES;
        }
        return switch (type.trim()) {
            case SIGNATURE_TYPE_XADES -> SignatureService.SignatureFormat.XADES;
            case SIGNATURE_TYPE_PADES -> SignatureService.SignatureFormat.PADES;
            case SIGNATURE_TYPE_CADES -> SignatureService.SignatureFormat.CADES;
            default -> SignatureService.SignatureFormat.CADES;
        };
    }

    /**
     * Whether the signed document is embedded in the signature (enveloping CAdES / XAdES). The
     * qualified e-prescription signature embeds the FHIR bundle, so an absent flag defaults to
     * {@code true}.
     */
    private static boolean resolveIncludeEContent(SignRequest req) {
        if (req.getOptionalInputs() == null || req.getOptionalInputs().isIncludeEContent() == null) {
            return true;
        }
        return req.getOptionalInputs().isIncludeEContent();
    }

    private static byte[] extractDocumentBytes(DocumentType doc) {
        if (doc == null)
            return new byte[0];
        if (doc.getBase64XML() != null)
            return doc.getBase64XML();
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
        } catch (Exception ignored) {
        }
        return vr;
    }

    @Override
    public GetSignatureModeResponse getSignatureMode(GetSignatureMode parameter) throws FaultMessage {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSignatureMode'");
    }

    @Override
    public ActivateComfortSignatureResponse activateComfortSignature(ActivateComfortSignature parameter)
            throws FaultMessage {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'activateComfortSignature'");
    }

    @Override
    public DeactivateComfortSignatureResponse deactivateComfortSignature(DeactivateComfortSignature parameter)
            throws FaultMessage {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deactivateComfortSignature'");
    }
}
