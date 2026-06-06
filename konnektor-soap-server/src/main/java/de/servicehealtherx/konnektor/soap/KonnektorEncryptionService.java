package de.servicehealtherx.konnektor.soap;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jws.WebService;

import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Konnektor compatibility EncryptionService SOAP endpoint.
 * MTOM mandatory from PTV5 per FR-151, TIP1-A_5694-03.
 */
@ApplicationScoped
@WebService(serviceName = "EncryptionService", portName = "EncryptionServicePort",
    targetNamespace = "http://ws.gematik.de/conn/EncryptionService/v6.1")
public class KonnektorEncryptionService {

    @Inject
    EncryptionService encryptionService;

    public byte[] encryptDocument(String mandantId, byte[] document, List<X509Certificate> recipientCerts) {
        EncryptionService.EncryptRequest req = new EncryptionService.EncryptRequest(
            new KeyAlias("p12/default"), recipientCerts, document, "BINARY", mandantId, false);
        return encryptionService.encryptDocument(req);
    }

    public byte[] decryptDocument(String mandantId, String alias, byte[] ciphertext) {
        EncryptionService.DecryptRequest req = new EncryptionService.DecryptRequest(
            new KeyAlias(alias), ciphertext, mandantId);
        return encryptionService.decryptDocument(req);
    }
}
