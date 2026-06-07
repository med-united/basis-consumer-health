package de.servicehealtherx.quarkus.sicct.runtime.tls;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.net.ssl.KeyManagerFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalConnection;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

public class SmkCSAKAutProvider {
    private static final Logger LOG = Logger.getLogger(SmkCSAKAutProvider.class);

    KeyStore keyStore;

    @Inject
    @ConfigProperty(name = "sicct.tls.client.key.password", defaultValue = "00")
    String keyPassword;

    KeyManagerFactory kmf;

    public SmkCSAKAutProvider() {
        try {
            // try to load the KeyStore from the HSM using SunPKCS11 provider
            Provider provider = Security.getProvider("SunPKCS11");
            provider = provider.configure("hsm.cfg");

            // KeyStore vom HSM laden
            this.keyStore = KeyStore.getInstance("PKCS11", provider);
            this.keyStore.load(null, keyPassword.toCharArray());
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | InvalidParameterException
                | IOException e) {
            LOG.errorf(e, "Failed to initialize HSM KeyStore. Loading software certificate from: %s");

            try (InputStream is = getClass().getResourceAsStream(
                    "/smc-k/80276883110000020000-P_C_SAK_AUT_E256_X509/80276883110000020000-P_C_SAK_AUT_E256_X509.p12")) {
                keyPassword = "00"; // default password for test certificate; in production, this MUST be securely
                                    // injected and MUST NOT be hardcoded
                keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(is, "00".toCharArray());
            } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException ex) {
                LOG.errorf(ex, "Failed to load fallback PKCS12 KeyStore");
                throw new RuntimeException("Failed to initialize SmkCSAKAuthProvider", ex);
            }

        }
        try {
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyPassword.toCharArray());
        } catch (NoSuchAlgorithmException e) {
            LOG.errorf(e, "Failed to initialize KeyManagerFactory: algorithm not found");
            throw new RuntimeException("Failed to initialize SmkCSAKAuthProvider", e);
        } catch (UnrecoverableKeyException | KeyStoreException e) {
            LOG.errorf(e, "Failed to initialize KeyManagerFactory: unrecoverable key or keystore error");
            throw new RuntimeException("Failed to initialize SmkCSAKAuthProvider", e);
        }
    }

    @Produces
    public SmkCSAKAut createSmkCSAKAut() {
        SmkCSAKAut smkCSAKAut = new SmkCSAKAut();
        smkCSAKAut.setKeyManagerFactory(kmf);
        return smkCSAKAut;
    }

}
