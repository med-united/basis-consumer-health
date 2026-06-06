package de.servicehealtherx.crypto.services.jmx;

import de.servicehealtherx.crypto.TrustService;
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
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-services-lib,name=CertificateServiceManagement";

    @Inject
    TrustService trustService;

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
            if (server.isRegistered(name)) server.unregisterMBean(name);
        } catch (Exception e) {
            LOG.warnf(e, "[JMX] failed to deregister %s", OBJECT_NAME);
        }
    }

    @Override
    public String readCertificate(String alias, String certRef, String crypt) {
        // Routes to CryptoProvider to read certificate for the given alias
        // certRef ∈ { "C.AUT", "C.OSIG" }, crypt ∈ { "ECC", "RSA" }
        throw new UnsupportedOperationException(
            "readCertificate requires full CryptoProvider integration — implementation pending");
    }

    @Override
    public String verifyCertificate(String certificateBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(certificateBase64);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(der));
            TrustService.VerificationResult result = trustService.verify(cert, false);
            return "{\"result\":\"" + (result.valid() ? "VALID" : "INVALID") +
                "\",\"detail\":\"" + result.detail() + "\"}";
        } catch (Exception e) {
            return "{\"result\":\"INCONCLUSIVE\",\"detail\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
