package de.servicehealtherx.crypto.services.jmx;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.CertificateService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

@ApplicationScoped
public class CertificateServiceManagement implements CertificateServiceManagementMBean {

    private static final Logger LOG = Logger.getLogger(CertificateServiceManagement.class);

    @Inject
    CertificateService certificateService;
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-services-lib,name=CertificateServiceManagement";

    @PostConstruct
    void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (!server.isRegistered(name)) {
                server.registerMBean(this, name);
                LOG.infof("[JMX] registered %s", OBJECT_NAME);
            }
        } catch (Exception e) {
            LOG.errorf(e, "[JMX] failed to register %s", OBJECT_NAME);
        }
    }

    @PreDestroy
    void deregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(name))
                server.unregisterMBean(name);
        } catch (Exception e) {
            LOG.warnf(e, "[JMX] failed to deregister %s", OBJECT_NAME);
        }
    }

    @Override
    public String readCertificate(String alias, String certRef, String crypt) {
        // certRef ∈ { "C.AUT", "C.OSIG" }, crypt ∈ { "ECC", "RSA" }
        try {
            CertificateService.CertRef ref = "C.OSIG".equalsIgnoreCase(certRef) || "C.SIG".equalsIgnoreCase(certRef)
                    ? CertificateService.CertRef.C_OSIG
                    : CertificateService.CertRef.C_AUT;
            CertificateService.CryptAlgorithm alg = "RSA".equalsIgnoreCase(crypt)
                    ? CertificateService.CryptAlgorithm.RSA
                    : CertificateService.CryptAlgorithm.ECC;
            byte[] der = certificateService.readCertificate(
                    new CertificateService.ReadCertRequest(new KeyAlias(alias), ref, alg, "jmx"));
            return Base64.getEncoder().encodeToString(der);
        } catch (Exception e) {
            throw new RuntimeException("readCertificate failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String verifyCertificate(String certificateBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(certificateBase64);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(der));
            CertificateService.VerifyCertResult result = certificateService.verifyCertificate(cert, "jmx");
            return "{\"result\":\"" + result.result() + "\",\"detail\":\""
                    + sanitize(result.detail()) + "\",\"roles\":\"" + String.join(";", result.roles()) + "\"}";
        } catch (Exception e) {
            return "{\"result\":\"INCONCLUSIVE\",\"detail\":\"" + sanitize(e.getMessage()) + "\"}";
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace("\"", "'");
    }
}
