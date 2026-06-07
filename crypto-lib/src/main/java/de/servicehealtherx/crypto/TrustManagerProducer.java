package de.servicehealtherx.crypto;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import de.gematik.pki.gemlibpki.commons.certificate.CertificateProfile;
import de.gematik.pki.gemlibpki.commons.certificate.CertificateType;
import de.gematik.pki.gemlibpki.commons.certificate.TucPki018Verifier;
import de.gematik.pki.gemlibpki.commons.tsl.TspInformationProvider;
import de.gematik.pki.gemlibpki.commons.tsl.TspService;
import de.gematik.pki.gemlibpki.commons.tsl.TspServiceSubset;
import de.gematik.pki.gemlibpki.commons.utils.CertReader;
import eu.europa.esig.trustedlist.jaxb.tsl.DigitalIdentityType;

import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

@ApplicationScoped
public class TrustManagerProducer {

    private static final Logger LOG = Logger.getLogger(TrustManagerProducer.class);

    @Inject
    TslDownloader tslDownloader;

    public record VerificationResult(boolean valid, String detail) {
    }

    public void setTslDownloader(TslDownloader tslDownloader) {
        this.tslDownloader = tslDownloader;
    }

    @Produces
    @GSMCKtTrustManager
    public TrustManager produceGSMCKtTrustManager() {
        String productType = "Basis-Consumer";
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
        final List<TspService> tspServiceList = tslDownloader.getTspServiceList();

        List<X509Certificate> allowedIssuers = tspServiceList.stream()
                .flatMap(tspService -> tspService.getTspServiceType().getServiceInformation()
                        .getServiceDigitalIdentity().getDigitalId().stream())
                .filter(dit -> dit.getX509Certificate() != null)
                .map(dit -> {
                    try {
                        return CertReader.readX509(productType, dit.getX509Certificate());
                    } catch (Exception e) {
                        LOG.error("Error reading X509 certificate from TSP service digital identity", e);
                        return null;
                    }
                })
                .filter(cert -> cert != null)
                .toList();

        return new TucPki18VerifierTrustManager(tucPki018Verifier, true,
                allowedIssuers.toArray(new X509Certificate[0]));
    }
}
