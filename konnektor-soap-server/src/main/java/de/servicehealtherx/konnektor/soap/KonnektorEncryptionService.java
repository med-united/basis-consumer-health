package de.servicehealtherx.konnektor.soap;

import de.gematik.ws.conn.connectorcommon.v5.DocumentType;
import de.gematik.ws.conn.encryptionservice.v6.DecryptDocument;
import de.gematik.ws.conn.encryptionservice.v6.DecryptDocumentResponse;
import de.gematik.ws.conn.encryptionservice.v6.EncryptDocument;
import de.gematik.ws.conn.encryptionservice.v6.EncryptDocumentResponse;
import de.gematik.ws.conn.encryptionservice.v6.KeyOnCardType;
import de.gematik.ws.conn.encryptionservice.wsdl.v6_1.EncryptionServicePortType;
import de.gematik.ws.conn.encryptionservice.wsdl.v6_1.FaultMessage;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.EncryptionService;
import io.quarkiverse.cxf.annotation.CXFEndpoint;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import oasis.names.tc.dss._1_0.core.schema.Base64Data;

import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.buildError;
import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.okStatus;
import static de.servicehealtherx.konnektor.soap.KonnektorServiceHelper.toKeyAlias;

/**
 * Konnektor SOAP endpoint for the gematik {@code EncryptionService} (api-telematik/conn,
 * {@code WSDL/v6.1}). It implements the generated
 * {@link de.gematik.ws.conn.encryptionservice.wsdl.v6.EncryptionServicePortType} and routes both
 * operations to {@link EncryptionService} in {@code crypto-services-lib}, which performs the
 * gematik TI-ECIES transport encryption (gemSpec_Krypt §4.7 / A_17220).
 *
 * <ul>
 *   <li>{@code EncryptDocument} needs only the recipient certificates (no card): each EC recipient
 *       certificate in {@code RecipientKeys/Certificate} becomes one CMS {@code RecipientInfo}.</li>
 *   <li>{@code DecryptDocument} unwraps the transport key with the private key referenced by
 *       {@code PrivateKeyOnCard/CardHandle}; whether that key is software- (P12) or card-resident
 *       is hidden behind the crypto layer's JCE {@code "ELC"} seam.</li>
 * </ul>
 */
@CXFEndpoint(value = "/conn/EncryptionService")
@WebService(portName = "EncryptionServicePort", serviceName = "EncryptionService",
        targetNamespace = "http://ws.gematik.de/conn/EncryptionService/WSDL/v6.1",
        endpointInterface = "de.gematik.ws.conn.encryptionservice.wsdl.v6_1.EncryptionServicePortType")
public class KonnektorEncryptionService implements EncryptionServicePortType {

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
                    : new KeyAlias("default");

            byte[] docBytes = extractDocumentBytes(parameter.getDocument());
            EncryptionService.EncryptRequest req = new EncryptionService.EncryptRequest(
                    recipientAlias, certs, docBytes, "Base64Data", "konnektor-soap", true);

            byte[] encrypted = encryptionService.encryptDocument(req);

            EncryptDocumentResponse response = new EncryptDocumentResponse();
            response.setStatus(okStatus());
            response.setDocument(wrapBytes(encrypted));
            return response;
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
                    alias, ciphertext, "konnektor-soap");

            byte[] plaintext = encryptionService.decryptDocument(req);

            DecryptDocumentResponse response = new DecryptDocumentResponse();
            response.setStatus(okStatus());
            response.setDocument(wrapBytes(plaintext));
            return response;
        } catch (Exception e) {
            throw new FaultMessage("DecryptDocument failed: " + e.getMessage(), buildError(e.getMessage()));
        }
    }

    private static byte[] extractDocumentBytes(DocumentType doc) {
        if (doc == null) {
            return new byte[0];
        }
        if (doc.getBase64XML() != null) {
            return doc.getBase64XML();
        }
        if (doc.getBase64Data() != null && doc.getBase64Data().getValue() != null) {
            return doc.getBase64Data().getValue();
        }
        return new byte[0];
    }

    private static DocumentType wrapBytes(byte[] data) {
        DocumentType doc = new DocumentType();
        Base64Data base64Data = new Base64Data();
        base64Data.setValue(data);
        doc.setBase64Data(base64Data);
        return doc;
    }

    private static X509Certificate parseCertificate(byte[] der) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }
}
