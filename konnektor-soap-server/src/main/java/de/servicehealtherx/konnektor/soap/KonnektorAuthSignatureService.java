package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.authsignatureservice.wsdl.v7_4.AuthSignatureServicePortType;
import de.gematik.ws.conn.authsignatureservice.wsdl.v7_4.FaultMessage;
import de.gematik.ws.conn.signatureservice.v7.BinaryDocumentType;
import de.gematik.ws.conn.signatureservice.v7.ExternalAuthenticate;
import de.gematik.ws.conn.signatureservice.v7.ExternalAuthenticateResponse;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.SignatureService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import oasis.names.tc.dss._1_0.core.schema.Base64Signature;
import oasis.names.tc.dss._1_0.core.schema.SignatureObject;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.*;

@ApplicationScoped
@WebService(
    portName = "AuthSignatureServicePort",
    serviceName = "AuthSignatureService",
    targetNamespace = "http://ws.gematik.de/conn/AuthSignatureService/WSDL/v7.4",
    endpointInterface = "de.gematik.ws.conn.authsignatureservice.wsdl.v7_4.AuthSignatureServicePortType"
)
@SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
public class KonnektorAuthSignatureService implements AuthSignatureServicePortType {

    @Inject
    SignatureService signatureService;

    @Override
    public ExternalAuthenticateResponse externalAuthenticate(ExternalAuthenticate parameter) throws FaultMessage {
        try {
            KeyAlias alias = toKeyAlias(parameter.getCardHandle());
            byte[] hashBytes = extractBinaryBytes(parameter.getBinaryString());
            String signatureType = parameter.getOptionalInputs() != null
                ? parameter.getOptionalInputs().getSignatureType() : null;

            SignatureService.SignHashRequest req = new SignatureService.SignHashRequest(
                alias, signatureType, hashBytes, "konnektor-soap");

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

    private static byte[] extractBinaryBytes(BinaryDocumentType doc) {
        if (doc == null || doc.getBase64Data() == null) return new byte[0];
        return doc.getBase64Data().getValue() != null ? doc.getBase64Data().getValue() : new byte[0];
    }
}
