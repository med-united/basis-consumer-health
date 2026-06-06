package de.servicehealtherx.crypto.services.jmx;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.EncryptionService;
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
import java.util.List;

@ApplicationScoped
public class EncryptionServiceManagement implements EncryptionServiceManagementMBean {

    private static final Logger LOG = Logger.getLogger(EncryptionServiceManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-services-lib,name=EncryptionServiceManagement";
    private static final String JMX_CALLER = "jmx/operator";

    @Inject
    EncryptionService encryptionService;

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
    public String encryptDocument(String alias, String algorithm, String documentBase64, String recipientCertBase64) {
        try {
            byte[] document = Base64.getDecoder().decode(documentBase64);
            X509Certificate cert = parseCert(Base64.getDecoder().decode(recipientCertBase64));
            EncryptionService.EncryptRequest req = new EncryptionService.EncryptRequest(
                new KeyAlias(alias), List.of(cert), document, "BINARY", JMX_CALLER, false);
            byte[] result = encryptionService.encryptDocument(req);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException("JMX encryptDocument failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String decryptDocument(String alias, String ciphertextBase64) {
        try {
            byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);
            EncryptionService.DecryptRequest req = new EncryptionService.DecryptRequest(
                new KeyAlias(alias), ciphertext, JMX_CALLER);
            byte[] result = encryptionService.decryptDocument(req);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException("JMX decryptDocument failed: " + e.getMessage(), e);
        }
    }

    private X509Certificate parseCert(byte[] der) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
    }
}
