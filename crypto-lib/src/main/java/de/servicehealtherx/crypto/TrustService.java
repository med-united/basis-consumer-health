package de.servicehealtherx.crypto;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.cert.X509Certificate;

@ApplicationScoped
public class TrustService {

    private static final Logger LOG = Logger.getLogger(TrustService.class);

    @Inject
    TslDownloader tslDownloader;

    public record VerificationResult(boolean valid, String detail) {}

    public VerificationResult verify(X509Certificate certificate, boolean tolerateOcspFailure) {
        try {
            TslDownloader.TslState tslState = tslDownloader.getCurrentState();
            if ("UNAVAILABLE".equals(tslState.status())) {
                if (tolerateOcspFailure) {
                    LOG.warnf("[TrustService] TSL unavailable; TOLERATE_OCSP_FAILURE=true, proceeding");
                    return new VerificationResult(true, "TSL unavailable; tolerated");
                }
                return new VerificationResult(false, "TSL unavailable");
            }

            // gemLibPki PL_TUC_PKI_VERIFY_CERTIFICATE integration goes here
            LOG.debugf("[TrustService] verifying certificate subject=%s",
                certificate.getSubjectX500Principal().getName());

            return new VerificationResult(true, "VALID");
        } catch (Exception e) {
            LOG.errorf(e, "[TrustService] certificate verification failed");
            return new VerificationResult(false, "Verification error: " + e.getMessage());
        }
    }

    public VerificationResult verify(X509Certificate certificate) {
        return verify(certificate, false);
    }
}
