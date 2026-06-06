package de.servicehealtherx.crypto.services;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.TrustService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.cert.X509Certificate;

@ApplicationScoped
public class CertificateService {

    private static final Logger LOG = Logger.getLogger(CertificateService.class);

    @Inject
    CryptoProvider cryptoProvider;

    @Inject
    TrustService trustService;

    @Inject
    AuditLogger auditLogger;

    public enum CertRef { C_AUT, C_OSIG }

    public enum CryptAlgorithm { ECC, RSA }

    public record ReadCertRequest(
        KeyAlias alias,
        CertRef certRef,
        CryptAlgorithm crypt,
        String callerIdentity
    ) {}

    public record VerifyCertResult(
        String result,
        String detail
    ) {}

    public byte[] readCertificate(ReadCertRequest request) {
        long start = System.currentTimeMillis();
        try {
            // Cert extraction delegates to key source via CryptoProvider
            // The actual implementation depends on card/HSM type and certRef
            LOG.infof("[CertificateService] readCertificate alias=%s certRef=%s crypt=%s",
                request.alias(), request.certRef(), request.crypt());
            auditLogger.logSuccess(request.alias().value(), "READ_CERT",
                request.certRef().name(), request.callerIdentity(), System.currentTimeMillis() - start);
            // Full implementation requires card-specific APDU profile for PCSC/SICCT
            throw new UnsupportedOperationException("readCertificate: card-specific implementation pending");
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Exception e) {
            auditLogger.logFailure(request.alias().value(), "READ_CERT",
                request.certRef().name(), request.callerIdentity(), System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("readCertificate failed: " + e.getMessage(), e);
        }
    }

    public VerifyCertResult verifyCertificate(X509Certificate certificate, String callerIdentity) {
        long start = System.currentTimeMillis();
        try {
            TrustService.VerificationResult vr = trustService.verify(certificate, false);
            auditLogger.logSuccess("verify", "VERIFY_CERT", "X509",
                callerIdentity, System.currentTimeMillis() - start);
            return new VerifyCertResult(vr.valid() ? "VALID" : "INVALID", vr.detail());
        } catch (Exception e) {
            auditLogger.logFailure("verify", "VERIFY_CERT", "X509",
                callerIdentity, System.currentTimeMillis() - start, e.getMessage());
            return new VerifyCertResult("INCONCLUSIVE", e.getMessage());
        }
    }
}
