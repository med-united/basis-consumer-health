package de.servicehealtherx.crypto;

import java.util.List;

import java.security.cert.X509Certificate;

import de.gematik.pki.gemlibpki.commons.certificate.CertificateProfile;
import de.gematik.pki.gemlibpki.commons.certificate.TucPki018Verifier;
import de.gematik.pki.gemlibpki.commons.exception.GemPkiException;
import de.gematik.pki.gemlibpki.commons.utils.CertReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TrustService {

    @Inject
    TslDownloader tslDownloader;

    public VerificationResult verify(byte[] certBytes) {
        return verify(certBytes, true);
    }

    public VerificationResult verify(byte[] certBytes, boolean callerIdentity) {
        X509Certificate certFromReader = CertReader.readX509(certBytes);
        return verify(certFromReader, callerIdentity);
    }

    public VerificationResult verify(X509Certificate certFromReader, boolean callerIdentity) {

        TucPki018Verifier tucPki018Verifier = TucPki018Verifier.builder()
                .productType("Basis-Consumer")
                .tspServiceList(tslDownloader.getTspServiceList())
                .ocspTimeoutSeconds(14) // on windows machine the DNS lookup takes longer than the default of 10
                // seconds, which leads to a TimeoutException and a test fail
                // TODO: Profile for cert type CertificateType.CERT_TYPE_SMKT_AUT is not
                // defined, so we use CERT_PROFILE_ANY which allows all cert types. The TUC PKI
                // 018 checks are performed in the TucPki18VerifierTrustManager
                .certificateProfiles(List.of(CertificateProfile.CERT_PROFILE_ANY))
                .tolerateOcspFailure(true)
                .build();

        try {
            tucPki018Verifier.performTucPki018Checks(certFromReader);
            return new VerificationResult(true, "Certificate is valid (placeholder)");
        } catch (GemPkiException e) {
            return new VerificationResult(false, "Certificate validation failed: " + e.getMessage());
        }
    }

    public record VerificationResult(
            boolean valid,
            String detail) {
    }
}
