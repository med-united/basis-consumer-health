package de.servicehealtherx.crypto.services;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.TrustService;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.ecies.EciesTransportEncryption;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Document encryption/decryption using the gematik TI-ECIES transport-encryption scheme
 * (gemSpec_Krypt §4.7 / A_17220). Orchestration only: recipient-certificate trust verification,
 * delegation to {@link EciesTransportEncryption} for the CMS {@code AuthEnvelopedData} build/parse,
 * and auditing. The smart-card / software key distinction is hidden behind the {@code CryptoProvider}
 * decrypt seam (which the P12 provider serves via the JCE {@code "ELC"} cipher), so this service is
 * unaware whether a key is card-resident (FR-015).
 */
@ApplicationScoped
public class EncryptionService {

    private static final Logger LOG = Logger.getLogger(EncryptionService.class);

    private static final String ALGORITHM = "ECIES-AES256-GCM";
    private static final String ELC_UNWRAP_ALGORITHM = "ELC";

    private final EciesTransportEncryption ecies = new EciesTransportEncryption();

    @Inject
    Instance<CryptoProvider> cryptoProviders;

    @Inject
    TrustService trustService;

    @Inject
    AuditLogger auditLogger;

    public record EncryptRequest(
        KeyAlias recipientAlias,
        List<X509Certificate> recipientCerts,
        byte[] document,
        String documentType,
        String callerIdentity,
        boolean eccPreferred
    ) {}

    public record DecryptRequest(
        KeyAlias decryptAlias,
        byte[] ciphertext,
        String callerIdentity
    ) {}

    public byte[] encryptDocument(EncryptRequest request) {
        long start = System.currentTimeMillis();
        try {
            for (X509Certificate cert : request.recipientCerts()) {
                TrustService.VerificationResult vr = trustService.verify(cert, false);
                if (!vr.valid()) {
                    throw new IllegalArgumentException(
                        "Recipient certificate rejected: subject=" +
                        cert.getSubjectX500Principal().getName() + " reason=" + vr.detail());
                }
                if (isRsaCertificate(cert)) {
                    throw new IllegalArgumentException(
                        "unsuitable recipient: RSA certificate cannot be used with ECIES for subject=" +
                        cert.getSubjectX500Principal().getName());
                }
            }

            byte[] result = ecies.encrypt(request.document(), request.recipientCerts());

            auditLogger.logSuccess(request.recipientAlias().value(), "ENCRYPT",
                ALGORITHM, request.callerIdentity(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            auditLogger.logFailure(request.recipientAlias().value(), "ENCRYPT",
                ALGORITHM, request.callerIdentity(), System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    public byte[] decryptDocument(DecryptRequest request) {
        long start = System.currentTimeMillis();
        try {
            CryptoProvider provider = getCryptoProviderForAlias(request.decryptAlias());
            byte[] plaintext = ecies.decrypt(request.ciphertext(),
                elcBlob -> provider.decrypt(new CryptoOperationRequest(
                    request.decryptAlias(), ELC_UNWRAP_ALGORITHM, elcBlob, request.callerIdentity())).result);

            auditLogger.logSuccess(request.decryptAlias().value(), "DECRYPT",
                ALGORITHM, request.callerIdentity(), System.currentTimeMillis() - start);
            return plaintext;
        } catch (Exception e) {
            auditLogger.logFailure(request.decryptAlias().value(), "DECRYPT",
                ALGORITHM, request.callerIdentity(), System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }

    private boolean isRsaCertificate(X509Certificate cert) {
        return cert.getPublicKey().getAlgorithm().startsWith("RSA");
    }

    private CryptoProvider getCryptoProviderForAlias(KeyAlias alias) {
        for (CryptoProvider provider : cryptoProviders) {
            if (provider.getAvailability(alias) == KeyStoreAvailability.AVAILABLE) {
                return provider;
            }
        }
        throw new RuntimeException("No CryptoProvider found for alias: " + alias);
    }
}
