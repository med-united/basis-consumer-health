package de.servicehealtherx.crypto.services;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.TrustService;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.cert.X509Certificate;
import java.util.List;

@ApplicationScoped
public class EncryptionService {

    private static final Logger LOG = Logger.getLogger(EncryptionService.class);

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
                if (request.eccPreferred() && isRsaCertificate(cert)) {
                    throw new IllegalArgumentException(
                        "ECC-preferred mode active: RSA recipient certificate rejected for subject=" +
                        cert.getSubjectX500Principal().getName());
                }
            }

            // PL_TUC_HYBRID_ENCIPHER: symmetric key generation + asymmetric key wrap
            byte[] aesKey = generateAesKey();
            byte[] encryptedDoc = encryptDocumentSymmetric(aesKey, request.document(), request.documentType());
            byte[] encryptedKey = wrapKeyForRecipients(aesKey, request.recipientCerts());
            byte[] result = assembleHybridCiphertext(encryptedDoc, encryptedKey);

            auditLogger.logSuccess(request.recipientAlias().value(), "ENCRYPT",
                "HYBRID_AES256", request.callerIdentity(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            auditLogger.logFailure(request.recipientAlias().value(), "ENCRYPT",
                "HYBRID_AES256", request.callerIdentity(), System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    public byte[] decryptDocument(DecryptRequest request) {
        long start = System.currentTimeMillis();
        try {
            byte[] aesKey = unwrapKeyViaProvider(request.ciphertext(), request.decryptAlias(), request.callerIdentity());
            byte[] plaintext = decryptDocumentSymmetric(aesKey, request.ciphertext());

            auditLogger.logSuccess(request.decryptAlias().value(), "DECRYPT",
                "HYBRID_AES256", request.callerIdentity(), System.currentTimeMillis() - start);
            return plaintext;
        } catch (Exception e) {
            auditLogger.logFailure(request.decryptAlias().value(), "DECRYPT",
                "HYBRID_AES256", request.callerIdentity(), System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }

    private boolean isRsaCertificate(X509Certificate cert) {
        return cert.getPublicKey().getAlgorithm().startsWith("RSA");
    }

    private byte[] generateAesKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return key;
    }

    private byte[] encryptDocumentSymmetric(byte[] key, byte[] document, String documentType) {
        try {
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(document);
            byte[] result = new byte[12 + encrypted.length];
            System.arraycopy(iv, 0, result, 0, 12);
            System.arraycopy(encrypted, 0, result, 12, encrypted.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Symmetric encryption failed", e);
        }
    }

    private byte[] wrapKeyForRecipients(byte[] aesKey, List<X509Certificate> certs) {
        // RFC 5652 CMS key wrapping per recipient cert public key
        // Full implementation requires Bouncy Castle CMS
        return aesKey; // placeholder
    }

    private byte[] assembleHybridCiphertext(byte[] encryptedDoc, byte[] encryptedKey) {
        byte[] result = new byte[4 + encryptedKey.length + encryptedDoc.length];
        result[0] = (byte) (encryptedKey.length >> 8);
        result[1] = (byte) encryptedKey.length;
        result[2] = (byte) (encryptedDoc.length >> 8);
        result[3] = (byte) encryptedDoc.length;
        System.arraycopy(encryptedKey, 0, result, 4, encryptedKey.length);
        System.arraycopy(encryptedDoc, 0, result, 4 + encryptedKey.length, encryptedDoc.length);
        return result;
    }

    private CryptoProvider getCryptoProviderForAlias(KeyAlias alias) {
        for (CryptoProvider provider : cryptoProviders) {
            if (provider.getAvailability(alias) == KeyStoreAvailability.AVAILABLE) {
                return provider;
            }
        }
        throw new RuntimeException("No CryptoProvider found for alias: " + alias);
    }

    private byte[] unwrapKeyViaProvider(byte[] ciphertext, KeyAlias alias, String callerIdentity) {
        int keyLen = ((ciphertext[0] & 0xFF) << 8) | (ciphertext[1] & 0xFF);
        byte[] wrappedKey = new byte[keyLen];
        System.arraycopy(ciphertext, 4, wrappedKey, 0, keyLen);

        CryptoOperationRequest req = new CryptoOperationRequest(alias, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
            wrappedKey, callerIdentity);
        CryptoOperationResult result = getCryptoProviderForAlias(alias).decrypt(req);
        return result.result;
    }

    private byte[] decryptDocumentSymmetric(byte[] key, byte[] ciphertext) {
        try {
            int keyLen = ((ciphertext[0] & 0xFF) << 8) | (ciphertext[1] & 0xFF);
            int docOffset = 4 + keyLen;
            byte[] encryptedDoc = new byte[ciphertext.length - docOffset];
            System.arraycopy(ciphertext, docOffset, encryptedDoc, 0, encryptedDoc.length);

            byte[] iv = new byte[12];
            System.arraycopy(encryptedDoc, 0, iv, 0, 12);
            byte[] encrypted = new byte[encryptedDoc.length - 12];
            System.arraycopy(encryptedDoc, 12, encrypted, 0, encrypted.length);

            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Symmetric decryption failed", e);
        }
    }
}
