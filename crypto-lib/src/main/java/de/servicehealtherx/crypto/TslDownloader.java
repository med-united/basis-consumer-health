package de.servicehealtherx.crypto;

import eu.europa.esig.trustedlist.jaxb.tsl.TrustStatusListType;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import de.gematik.pki.gemlibpki.commons.tsl.TslInformationProvider;
import de.gematik.pki.gemlibpki.commons.tsl.TslReader;
import de.gematik.pki.gemlibpki.commons.tsl.TslValidator;
import de.gematik.pki.gemlibpki.commons.tsl.TspService;
import de.gematik.pki.gemlibpki.commons.utils.CertReader;

/**
 * Validate EGK Certificates using:
 * TUC_PKI_018
 * <a href=
 * "https://gemspec.gematik.de/docs/gemSpec/gemSpec_PKI/latest/#8.3.1.1">...</a>
 */
@ApplicationScoped
@Startup
public class TslDownloader {

    private List<TspService> tspServiceList;

    @Inject
    @ConfigProperty(name = "tsl.downloader.production", defaultValue = "false")
    boolean pu;

    String tslUrl;

    TslState currentState = new TslState(0, null, null, "UNAVAILABLE");

    public TslDownloader() {
    }

    @PostConstruct
    public void init() {
        refreshTspServiceList();
    }

    @Scheduled(every = "${tsl.downloader.every:1d}")
    public void refreshTspServiceList() {
        try {
            if (pu) {
                tslUrl = "http://download.crl.ti-dienste.de/TSL-ECC/ECC-RSA_TSL.xml";
            } else {
                tslUrl = "http://download-testref.crl.ti-dienste.de/TSL-ECC-ref/ECC-RSA_TSL-ref.xml";
            }

            String fileName = tslUrl.substring(tslUrl.lastIndexOf("/") + 1);

            File tsl = new File(fileName);

            // download file from url if file does not exist or is older than 2 weeks
            if (!tsl.exists() || (System.currentTimeMillis() - tsl.lastModified()) > 1209600000) {
                URLConnection connection = new URI(tslUrl).toURL().openConnection();
                connection.connect();
                InputStream inputStream = connection.getInputStream();
                byte[] tslFromUrl = inputStream.readAllBytes();

                if (!validateTSL(tslFromUrl)) {
                    throw new CertificateException("TSL validation failed");
                }

                connection.getInputStream().close();
                FileOutputStream fileOut = new FileOutputStream(tsl);
                fileOut.write(tslFromUrl);
                fileOut.close();
            }

            TrustStatusListType tslUnsigned = TslReader.getTslUnsigned(tsl.toPath());

            tspServiceList = new TslInformationProvider(tslUnsigned).getTspServices();
            currentState = new TslState(tslUnsigned.getSchemeInformation().getTSLSequenceNumber().longValue(),
                    tslUnsigned.getSchemeInformation().getNextUpdate().getDateTime().toGregorianCalendar().toInstant(),
                    tsl.lastModified() == 0 ? null : Instant.ofEpochMilli(tsl.lastModified()), "OK");
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    boolean validateTSL(byte[] tslBytes)
            throws CertificateException, NoSuchProviderException, FileNotFoundException, IOException {

        String certPath = pu ? "/tsl-signer-certs/C.GEM.TSL-CA3.der"
                : "/tsl-signer-certs/C.GEM.TSL-CA28.der";
        boolean valid;
        X509Certificate certificate = (X509Certificate) CertReader
                .readX509(TslDownloader.class.getResourceAsStream(certPath).readAllBytes());
        valid = TslValidator.checkSignature(tslBytes, certificate);
        return valid;
    }

    public List<TspService> getTspServiceList() {
        return tspServiceList;
    }

    public String getTslUrl() {
        return tslUrl;
    }

    public TslState getCurrentState() {
        return currentState;
    }

    public record TslState(long sequenceNumber, Instant expiry, Instant downloadedAt, String status) {

    }
}