package de.servicehealtherx.consumer.soap;

import de.gematik.ws.consumer.encryptionservice.v3.DecryptDocument;
import de.gematik.ws.consumer.encryptionservice.v3.DecryptDocumentResponse;
import de.gematik.ws.consumer.encryptionservice.v3.EncryptDocument;
import de.gematik.ws.consumer.encryptionservice.v3.EncryptDocumentResponse;
import de.gematik.ws.consumer.encryptionservice.v3.KeyOnCardType;
import de.gematik.ws.consumer.encryptionservice.wsdl.v3_0.EncryptionServicePortType;
import de.gematik.ws.consumer.encryptionservice.wsdl.v3_0.FaultMessage;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.EncryptionService;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static de.servicehealtherx.consumer.soap.ConsumerServiceHelper.*;

@CXFEndpoint(value = "/consumer/EncryptionService")
@WebService(portName = "EncryptionServicePort", serviceName = "EncryptionService", targetNamespace = "http://ws.gematik.de/consumer/EncryptionService/WSDL/v3.0", endpointInterface = "de.gematik.ws.consumer.encryptionservice.wsdl.v3_0.EncryptionServicePortType")
public class ConsumerEncryptionService implements EncryptionServicePortType {

    @Inject
    EncryptionService encryptionService;

    @Override
    public EncryptDocumentResponse encryptDocument(EncryptDocument parameter) throws FaultMessage {
        try {
            List<X509Certificate> certs = new ArrayList<>();
            KeyOnCardType certOnCard = null;

            if (parameter.getRecipientKeys() != null) {
                certOnCard = parameter.getRecipientKeys().getCertificateOnCard();
                for (byte[] certBytes : parameter.getRecipientKeys().getCertificate()) {
                    certs.add(parseCertificate(certBytes));
                }
            }

            KeyAlias recipientAlias = certOnCard != null
                    ? toKeyAlias(certOnCard.getCardHandle())
                    : new KeyAlias("sicct/default");
            boolean eccPreferred = certOnCard != null && "ECC".equals(certOnCard.getCrypt());

            byte[] docBytes = extractDocumentBytes(parameter.getDocument());
            EncryptionService.EncryptRequest req = new EncryptionService.EncryptRequest(
                    recipientAlias, certs, docBytes, "Base64Data", "consumer-soap", eccPreferred);

            byte[] encrypted = encryptionService.encryptDocument(req);

            EncryptDocumentResponse response = new EncryptDocumentResponse();
            response.setStatus(okStatus());
            response.setDocument(wrapBytes(encrypted));
            return response;
        } catch (FaultMessage e) {
            throw e;
        } catch (Exception e) {
            throw new FaultMessage("EncryptDocument failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    @Override
    public DecryptDocumentResponse decryptDocument(DecryptDocument parameter) throws FaultMessage {
        try {
            KeyAlias alias = toKeyAlias(parameter.getPrivateKeyOnCard().getCardHandle());
            byte[] ciphertext = extractDocumentBytes(parameter.getDocument());

            EncryptionService.DecryptRequest req = new EncryptionService.DecryptRequest(
                    alias, ciphertext, "consumer-soap");

            byte[] plaintext = encryptionService.decryptDocument(req);

            DecryptDocumentResponse response = new DecryptDocumentResponse();
            response.setStatus(okStatus());
            response.setDocument(wrapBytes(plaintext));
            return response;
        } catch (Exception e) {
            throw new FaultMessage("DecryptDocument failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }
}
