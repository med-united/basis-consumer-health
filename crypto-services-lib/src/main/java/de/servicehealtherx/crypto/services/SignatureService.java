package de.servicehealtherx.crypto.services;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.TrustService;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import de.servicehealtherx.crypto.signer.CadesSignature;
import de.servicehealtherx.crypto.signer.PadesSignature;
import de.servicehealtherx.crypto.signer.XadesSignature;
import de.servicehealtherx.crypto.signer.card.CardSigner;
import de.servicehealtherx.crypto.signer.card.CardSigningPrivateKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

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

    /**
     * VerifyPin against an inserted card addressed by CardHandle: VERIFY the {@code pinType} PIN
     * (e.g. {@code PIN.SMC} on an SMC-B) via whichever {@link CryptoProvider} holds it
     * (gemSpec_Kon VerifyPin / TUC_KON_012). Returns the card's verification outcome.
     */
    public de.servicehealtherx.crypto.model.PinVerificationResult verifyPin(
            String cardHandle, String pinType, String callerIdentity) {
        long start = System.currentTimeMillis();
        try {
            de.servicehealtherx.crypto.model.PinVerificationResult result =
                    providerForCard(cardHandle).verifyPin(cardHandle, pinType);
            auditLogger.logSuccess(cardHandle, "VERIFY_PIN", pinType, callerIdentity,
                    System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            auditLogger.logFailure(cardHandle, "VERIFY_PIN", pinType, callerIdentity,
                    System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("VerifyPin failed: " + e.getMessage(), e);
        }
    }

    /**
     * GetPinStatus against an inserted card addressed by CardHandle: read the state of the
     * {@code pinType} PIN (e.g. {@code PIN.SMC} on an SMC-B) via whichever {@link CryptoProvider}
     * holds it (gemSpec_Kon GetPinStatus / TUC_KON_011) without consuming a retry.
     */
    public de.servicehealtherx.crypto.model.PinStatusResult getPinStatus(
            String cardHandle, String pinType, String callerIdentity) {
        long start = System.currentTimeMillis();
        try {
            de.servicehealtherx.crypto.model.PinStatusResult result =
                    providerForCard(cardHandle).getPinStatus(cardHandle, pinType);
            auditLogger.logSuccess(cardHandle, "GET_PIN_STATUS", pinType, callerIdentity,
                    System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            auditLogger.logFailure(cardHandle, "GET_PIN_STATUS", pinType, callerIdentity,
                    System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("GetPinStatus failed: " + e.getMessage(), e);
        }
    }

    /**
     * SignDocument against an inserted card addressed by CardHandle, producing a complete advanced
     * electronic signature in the requested {@code format} (CAdES / PAdES / XAdES) rather than the
     * bare card signature value.
     *
     * <p>Unlike {@link #signDocumentWithCard(String, byte[], String)}, which returns the raw card
     * {@code R||S} bytes, this wraps the signature into the proper container: a CMS {@code SignedData}
     * for CAdES, a signed PDF for PAdES, a {@code ds:Signature} XML document for XAdES. The card never
     * signs the document directly — it signs the format's to-be-signed structure (e.g. the CMS signed
     * attributes) via the {@code "EHBA"} JCE provider, so the result verifies as a real
     * {@code SHA256withECDSA} signature.
     *
     * @param includeEContent for CAdES/XAdES, whether the signed document is embedded
     *                        (enveloping) or detached/enveloped; ignored for PAdES.
     */
    public byte[] signDocumentWithCard(String cardHandle, byte[] document, SignatureFormat format,
            boolean includeEContent, String callerIdentity) {
        long start = System.currentTimeMillis();
        try {
            CryptoProvider provider = providerForCard(cardHandle);
            X509Certificate signerCert = readSignerCertificate(provider, cardHandle);
            CardSigner cardSigner = toBeSigned -> provider.signQes(cardHandle, toBeSigned);
            PrivateKey cardKey = new CardSigningPrivateKey(cardSigner);
            List<X509Certificate> chain = List.of(signerCert);

            byte[] signed = switch (format) {
                case CADES -> new CadesSignature().signCades(
                        document, includeEContent, cardKey, signerCert, chain, null);
                case PADES -> new PadesSignature().signPades(
                        document, cardKey, signerCert, chain, null);
                case XADES -> new XadesSignature().signXades(
                        document, includeEContent, cardKey, signerCert, chain, null);
            };

            auditLogger.logSuccess(cardHandle, "SIGN", format.name(), callerIdentity,
                    System.currentTimeMillis() - start);
            return signed;
        } catch (Exception e) {
            auditLogger.logFailure(cardHandle, "SIGN", format.name(), callerIdentity,
                    System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("SignDocument failed: " + e.getMessage(), e);
        }
    }

    /**
     * Read the certificate matching the key {@link #signDocumentWithCard} signs with. The HBA carries
     * a qualified {@code C.QES} key in DF.QES; an SMC-B does not, in which case {@code signQes} falls
     * back to the {@code C.AUT} key, so the matching certificate is read here too.
     */
    private X509Certificate readSignerCertificate(CryptoProvider provider, String cardHandle)
            throws Exception {
        byte[] der;
        try {
            der = provider.readCardCertificate(cardHandle, "C.QES", "ECC");
        } catch (RuntimeException e) {
            LOG.warnf("[SignatureService] C.QES unavailable on %s (%s); using C.AUT certificate",
                    cardHandle, e.getMessage());
            der = provider.readCardCertificate(cardHandle, "C.AUT", "ECC");
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509", "BC");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
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
