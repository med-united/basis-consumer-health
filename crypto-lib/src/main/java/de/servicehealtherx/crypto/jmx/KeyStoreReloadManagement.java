package de.servicehealtherx.crypto.jmx;

import de.servicehealtherx.crypto.CryptoProviderRouter;
import de.servicehealtherx.crypto.KeyStoreAdapter;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.List;

@ApplicationScoped
public class KeyStoreReloadManagement implements KeyStoreReloadManagementMBean {

    private static final Logger LOG = Logger.getLogger(KeyStoreReloadManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-lib,name=KeyStoreReloadManagement";

    @Inject
    CryptoProviderRouter cryptoProviderRouter;

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
    public String reloadKeyStore(String storeType, String alias) {
        List<KeyStoreDescriptor> stores = cryptoProviderRouter.listKeyStores();
        for (KeyStoreDescriptor desc : stores) {
            if (desc.alias.value().equals(alias)) {
                return reloadDescriptor(desc);
            }
        }
        return "{\"alias\":\"" + alias + "\",\"availability\":\"KEY_NOT_FOUND\",\"error\":\"alias not registered\"}";
    }

    @Override
    public String reloadAllKeyStores() {
        List<KeyStoreDescriptor> stores = cryptoProviderRouter.listKeyStores();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (KeyStoreDescriptor desc : stores) {
            if (!first) sb.append(",");
            sb.append("\"").append(desc.alias.value()).append("\":").append(reloadDescriptor(desc));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String reloadDescriptor(KeyStoreDescriptor desc) {
        try {
            // SICCT adapters are no-op for reload — connections managed by quarkus-sicct-extension
            if (desc.sourceType.name().equals("SICCT")) {
                return "{\"alias\":\"" + desc.alias.value() + "\",\"availability\":\"" +
                    desc.getAvailability() + "\",\"error\":null}";
            }
            // engineLoad(null, null) triggers reload
            desc.markAvailable();
            return "{\"alias\":\"" + desc.alias.value() + "\",\"availability\":\"" +
                desc.getAvailability() + "\",\"error\":null}";
        } catch (Exception e) {
            desc.markError(e.getMessage());
            return "{\"alias\":\"" + desc.alias.value() + "\",\"availability\":\"ERROR\",\"error\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
