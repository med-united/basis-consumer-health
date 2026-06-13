package de.servicehealtherx.crypto.p12;

import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.adapter.P12KeyStoreAdapter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@ApplicationScoped
@Startup
public class P12CertManagement implements P12CertManagementMBean {

    private static final Logger LOG = Logger.getLogger(P12CertManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-p12-lib,name=P12CertManagement";

    @Inject
    P12CryptoConfig config;

    @Inject
    P12CryptoProvider provider;

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
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (Exception e) {
            LOG.warnf(e, "[JMX] failed to deregister %s", OBJECT_NAME);
        }
    }

    @Override
    public void uploadCertificate(String aliasHint, byte[] p12Data, String password) {
        if (aliasHint == null || aliasHint.isBlank()) {
            throw new IllegalArgumentException("aliasHint must not be blank");
        }
        if (p12Data == null || p12Data.length == 0) {
            throw new IllegalArgumentException("p12Data must not be empty");
        }
        if (password == null) {
            throw new IllegalArgumentException("password must not be null");
        }

        // Normalise aliasHint to a valid path segment (no path traversal)
        String safeName = aliasHint.replaceAll("[^a-zA-Z0-9\\-_.]", "-").replaceAll("-{2,}", "-");
        Path certsDir = Path.of(config.certsDir());
        Path targetDir = certsDir.resolve(safeName);
        Path p12File = targetDir.resolve(safeName + ".p12");
        Path passwordFile = targetDir.resolve("password.txt");

        try {
            Files.createDirectories(targetDir);

            // Write atomically: first password, then P12 (scanner reads password before
            // P12)
            Files.write(passwordFile, password.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(p12File, p12Data,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Derive alias using the same rule the scanner uses
            String alias = P12CertScanner.deriveAlias(certsDir, p12File);
            LOG.infof("[P12CertMgmt] wrote alias=%s to %s", alias, p12File);

            P12KeyStoreAdapter adapter = new P12KeyStoreAdapter(alias,
                    p12File.toAbsolutePath().toString(), password, password);
            try {
                adapter.engineLoad(null, null);
            } catch (IOException e) {
                LOG.warnf("[P12CertMgmt] uploaded P12 for alias=%s could not be loaded: %s", alias, e.getMessage());
                // still register so operator can see ERROR status via listCertificates
            }
            provider.registerAdapter(adapter);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write P12 certificate for alias: " + aliasHint, e);
        }
    }

    @Override
    public String listCertificates() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (KeyStoreDescriptor d : provider.listKeyStores()) {
            if (!first)
                sb.append(",");
            sb.append("{\"alias\":\"").append(d.alias.value()).append("\"")
                    .append(",\"availability\":\"").append(d.getAvailability()).append("\"");
            if (d.getErrorMessage() != null) {
                sb.append(",\"error\":\"").append(d.getErrorMessage().replace("\"", "'")).append("\"");
            }
            sb.append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public void reloadCertificates() {
        LOG.info("[P12CertMgmt] reloading certificates from disk");
        provider.reloadCertificates();
    }
}
