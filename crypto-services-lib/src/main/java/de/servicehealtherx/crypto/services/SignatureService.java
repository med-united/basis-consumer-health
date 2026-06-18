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

@ApplicationScoped
public class SignatureService {

    private static final Logger LOG = Logger.getLogger(SignatureService.class);

    @Inject
    Instance<CryptoProvider> cryptoProviders;

    @Inject
    AuditLogger auditLogger;

    public enum SignatureFormat {
        CADES, PADES, XADES
    }

    public enum VerificationResult {
        VALID, INVALID, INCONCLUSIVE
    }

    public record SignRequest(
            KeyAlias signerAlias,
            SignatureFormat format,
            byte[] document,
            String algorithm,
            String callerIdentity,
            boolean qes,
            boolean eccPreferred) {
    }

    public record SignHashRequest(
            KeyAlias signerAlias,
            String signatureType,
            byte[] hash,
            String callerIdentity) {
    }

    public record SignResult(
            byte[] signedDocument,
            String signatureFormat,
            String algorithm) {
    }

    public record VerifyRequest(
            byte[] signedDocument,
            SignatureFormat format,
            String callerIdentity) {
    }

    public record VerifyResult(
            VerificationResult result,
            String detail,
            String assumedTimestampType) {
    }

    public SignResult signDocument(SignRequest request) {
        long start = System.currentTimeMillis();
        String algorithm = resolveAlgorithm(request);
        try {
            CryptoOperationRequest cryptoReq = new CryptoOperationRequest(
                    request.signerAlias(), algorithm, request.document(), request.callerIdentity());

            CryptoProvider cryptoProvider = getCryptoProviderForAlias(request.signerAlias());
            CryptoOperationResult cryptoResult = cryptoProvider.sign(cryptoReq);

            byte[] signedDoc = embedSignature(request.document(), cryptoResult.result, request.format());
            auditLogger.logSuccess(request.signerAlias().value(), "SIGN",
                    algorithm, request.callerIdentity(), System.currentTimeMillis() - start);
            return new SignResult(signedDoc, request.format().name(), algorithm);
        } catch (Exception e) {
            auditLogger.logFailure(request.signerAlias().value(), "SIGN",
                    algorithm, request.callerIdentity(), System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("Sign failed: " + e.getMessage(), e);
        }
    }

    public byte[] signPlain(KeyAlias signerAlias, byte[] data, String callerIdentity) {
        long start = System.currentTimeMillis();
        String algorithm = "SHA256withECDSA";
        try {
            CryptoOperationRequest req = new CryptoOperationRequest(signerAlias, algorithm, data, callerIdentity);
            CryptoProvider cryptoProvider = getCryptoProviderForAlias(signerAlias);
            CryptoOperationResult result = cryptoProvider.sign(req);
            auditLogger.logSuccess(signerAlias.value(), "SIGN_PLAIN", algorithm, callerIdentity,
                    System.currentTimeMillis() - start);
            return result.result;
        } catch (Exception e) {
            auditLogger.logFailure(signerAlias.value(), "SIGN_PLAIN", algorithm, callerIdentity,
                    System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("SignPlain failed: " + e.getMessage(), e);
        }
    }

    public VerifyResult verifyDocument(VerifyRequest request) {
        // CoreValidation + cert chain via TrustService
        // Full implementation requires JDK XML/CMS signature APIs or Bouncy Castle
        return new VerifyResult(VerificationResult.INCONCLUSIVE, "Verification implementation pending", "LOCAL");
    }

    public byte[] externalAuthenticate(SignHashRequest request) {
        long start = System.currentTimeMillis();
        String algorithm = resolveHashAlgorithm(request.hash().length);
        try {
            CryptoOperationRequest req = new CryptoOperationRequest(
                    request.signerAlias(), algorithm, request.hash(), request.callerIdentity());
            CryptoProvider cryptoProvider = getCryptoProviderForAlias(request.signerAlias());
            CryptoOperationResult result = cryptoProvider.sign(req);
            auditLogger.logSuccess(request.signerAlias().value(), "EXTERNAL_AUTHENTICATE",
                    algorithm, request.callerIdentity(), System.currentTimeMillis() - start);
            return result.result;
        } catch (Exception e) {
            auditLogger.logFailure(request.signerAlias().value(), "EXTERNAL_AUTHENTICATE",
                    algorithm, request.callerIdentity(), System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("ExternalAuthenticate failed: " + e.getMessage(), e);
        }
    }

    /**
     * ExternalAuthenticate against an inserted card addressed by CardHandle: sign {@code hash} with
     * the card's C.AUT key via whichever {@link CryptoProvider} holds it. Returns the raw card
     * signature (ECDSA R||S).
     */
    public byte[] externalAuthenticate(String cardHandle, byte[] hash, String callerIdentity) {
        long start = System.currentTimeMillis();
        try {
            byte[] signature = providerForCard(cardHandle).externalAuthenticate(cardHandle, hash);
            auditLogger.logSuccess(cardHandle, "EXTERNAL_AUTHENTICATE", "C.AUT", callerIdentity,
                    System.currentTimeMillis() - start);
            return signature;
        } catch (Exception e) {
            auditLogger.logFailure(cardHandle, "EXTERNAL_AUTHENTICATE", "C.AUT", callerIdentity,
                    System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("ExternalAuthenticate failed: " + e.getMessage(), e);
        }
    }

    /**
     * SignDocument against an inserted card addressed by CardHandle: create a qualified signature
     * over {@code document} with the card's C.QES key via whichever {@link CryptoProvider} holds it.
     * Returns the raw card signature (ECDSA R||S).
     */
    public byte[] signDocumentWithCard(String cardHandle, byte[] document, String callerIdentity) {
        long start = System.currentTimeMillis();
        try {
            byte[] signature = providerForCard(cardHandle).signQes(cardHandle, document);
            auditLogger.logSuccess(cardHandle, "SIGN", "C.QES", callerIdentity,
                    System.currentTimeMillis() - start);
            return signature;
        } catch (Exception e) {
            auditLogger.logFailure(cardHandle, "SIGN", "C.QES", callerIdentity,
                    System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("SignDocument failed: " + e.getMessage(), e);
        }
    }

    private CryptoProvider providerForCard(String cardHandle) {
        for (CryptoProvider provider : cryptoProviders) {
            if (provider.ownsCard(cardHandle)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("No card found for handle: " + cardHandle);
    }

    private String resolveAlgorithm(SignRequest request) {
        return request.algorithm();
    }

    private String resolveHashAlgorithm(int hashLength) {
        return switch (hashLength) {
            case 32 -> "NONEwithECDSA";
            case 48 -> "NONEwithECDSA";
            case 64 -> "NONEwithECDSA";
            default -> throw new IllegalArgumentException(
                    "Unsupported hash length: " + hashLength
                            + " bytes. Expected 32 (SHA-256), 48 (SHA-384), or 64 (SHA-512).");
        };
    }

    private byte[] embedSignature(byte[] document, byte[] signature, SignatureFormat format) {
        // Format-specific embedding: CAdES (CMS), PAdES (PDF), XAdES (XML)
        // Placeholder: real implementation requires format-specific libraries
        byte[] result = new byte[document.length + signature.length + 4];
        result[0] = (byte) format.ordinal();
        result[1] = (byte) (signature.length >> 8);
        result[2] = (byte) signature.length;
        result[3] = 0;
        System.arraycopy(signature, 0, result, 4, signature.length);
        System.arraycopy(document, 0, result, 4 + signature.length, document.length);
        return result;
    }

    public CryptoProvider getCryptoProviderForAlias(KeyAlias alias) {
        for (CryptoProvider provider : cryptoProviders) {
            if (provider.getAvailability(alias) == KeyStoreAvailability.AVAILABLE) {
                return provider;
            }
        }
        throw new RuntimeException("No CryptoProvider found for alias: " + alias);
    }
}
