package de.servicehealtherx.crypto;

import javax.net.ssl.X509TrustManager;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import de.gematik.pki.gemlibpki.commons.certificate.TucPki018Verifier;

public class TucPki18VerifierTrustManager implements X509TrustManager {

    private final TucPki018Verifier tucPki018Verifier;
    private final boolean checkServer;
    private final X509Certificate[] acceptedIssuers;

    public TucPki18VerifierTrustManager(TucPki018Verifier tucPki018Verifier, boolean checkServer,
            X509Certificate[] acceptedIssuers) {
        // Initialize the trust manager with TSL information from the verifier
        // This is a placeholder; actual implementation would use tucPki018Verifier to
        // configure the verifier
        this.tucPki018Verifier = tucPki018Verifier;
        this.checkServer = checkServer;
        this.acceptedIssuers = acceptedIssuers;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (!checkServer) {
            throw new CertificateException("Server certificate validation is disabled");
        } else {
            try {
                this.tucPki018Verifier.performTucPki018Checks(chain[0]);
            } catch (Exception e) {
                throw new CertificateException("TUC PKI 018 validation failed", e);
            }
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return acceptedIssuers;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (checkServer) {
            throw new CertificateException("Client certificate validation is disabled");
        } else {
            try {
                this.tucPki018Verifier.performTucPki018Checks(chain[0]);
            } catch (Exception e) {
                throw new CertificateException("TUC PKI 018 validation failed", e);
            }
        }
    }
}
