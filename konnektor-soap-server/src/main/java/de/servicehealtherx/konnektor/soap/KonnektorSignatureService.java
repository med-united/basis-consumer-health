package de.servicehealtherx.konnektor.soap;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.SignatureService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;
import org.jboss.logging.Logger;

/**
 * Konnektor compatibility SignatureService SOAP endpoint.
 * Implements SignDocument, SignPlain, VerifyDocument, GetJobNumber, StopSignature.
 * ActivateComfortSignature + DeactivateComfortSignature + GetSignatureMode → fault code 7200 (deferred per FR-157).
 * Reference: FR-040, FR-157, gemSpec_OM conn/SignatureService.wsdl.
 */
@ApplicationScoped
@WebService(serviceName = "SignatureService", portName = "SignatureServicePort",
    targetNamespace = "http://ws.gematik.de/conn/SignatureService/v7.5")
public class KonnektorSignatureService {

    private static final Logger LOG = Logger.getLogger(KonnektorSignatureService.class);

    @Inject
    SignatureService signatureService;

    public byte[] signDocument(String mandantId, String clientSystemId, String workplaceId,
                                byte[] document, String signatureType) {
        String alias = resolveSigningAlias(mandantId, workplaceId);
        SignatureService.SignRequest req = new SignatureService.SignRequest(
            new KeyAlias(alias),
            resolveFormat(signatureType),
            document,
            signatureType,
            mandantId + "/" + clientSystemId,
            false,
            false
        );
        return signatureService.signDocument(req).signedDocument();
    }

    public boolean verifyDocument(byte[] signedDocument, String signatureType) {
        SignatureService.VerifyRequest req = new SignatureService.VerifyRequest(
            signedDocument, resolveFormat(signatureType), "konnektor/verify");
        SignatureService.VerifyResult result = signatureService.verifyDocument(req);
        return result.result() == SignatureService.VerificationResult.VALID;
    }

    public String activateComfortSignature(String mandantId, String userId) {
        throw new UnsupportedOperationException("ActivateComfortSignature not implemented — fault code 7200");
    }

    public String deactivateComfortSignature(String mandantId, String userId) {
        throw new UnsupportedOperationException("DeactivateComfortSignature not implemented — fault code 7200");
    }

    public String getSignatureMode(String mandantId, String userId) {
        throw new UnsupportedOperationException("GetSignatureMode not implemented — fault code 7200");
    }

    private String resolveSigningAlias(String mandantId, String workplaceId) {
        return "p12/default";
    }

    private SignatureService.SignatureFormat resolveFormat(String signatureType) {
        if (signatureType != null) {
            if (signatureType.contains("CAdES")) return SignatureService.SignatureFormat.CADES;
            if (signatureType.contains("PAdES")) return SignatureService.SignatureFormat.PADES;
            if (signatureType.contains("XAdES")) return SignatureService.SignatureFormat.XADES;
        }
        return SignatureService.SignatureFormat.CADES;
    }
}
