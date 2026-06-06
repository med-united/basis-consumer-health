package de.servicehealtherx.crypto.services.jmx;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.services.SignatureService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Base64;

@ApplicationScoped
public class SignatureServiceManagement implements SignatureServiceManagementMBean {

    private static final Logger LOG = Logger.getLogger(SignatureServiceManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-services-lib,name=SignatureServiceManagement";
    private static final String JMX_CALLER = "jmx/operator";

    @Inject
    SignatureService signatureService;

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
    public String sign(String alias, String algorithm, String dataBase64) {
        byte[] data = Base64.getDecoder().decode(dataBase64);
        SignatureService.SignRequest req = new SignatureService.SignRequest(
            new KeyAlias(alias),
            SignatureService.SignatureFormat.CADES,
            data,
            algorithm,
            JMX_CALLER,
            false,
            false
        );
        SignatureService.SignResult result = signatureService.signDocument(req);
        return Base64.getEncoder().encodeToString(result.signedDocument());
    }

    @Override
    public boolean verify(String alias, String algorithm, String dataBase64, String signatureBase64) {
        byte[] data = Base64.getDecoder().decode(dataBase64);
        byte[] signature = Base64.getDecoder().decode(signatureBase64);
        SignatureService.VerifyRequest req = new SignatureService.VerifyRequest(
            data, SignatureService.SignatureFormat.CADES, JMX_CALLER);
        SignatureService.VerifyResult result = signatureService.verifyDocument(req);
        return result.result() == SignatureService.VerificationResult.VALID;
    }

    @Override
    public String externalAuthenticate(String alias, String signatureType, String hashBase64) {
        byte[] hash = Base64.getDecoder().decode(hashBase64);
        SignatureService.SignHashRequest req = new SignatureService.SignHashRequest(
            new KeyAlias(alias), signatureType, hash, JMX_CALLER);
        byte[] result = signatureService.externalAuthenticate(req);
        return Base64.getEncoder().encodeToString(result);
    }

    @Override
    public String getSignatureMode() {
        return "NOT_IMPLEMENTED";
    }
}
