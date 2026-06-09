package de.servicehealtherx.crypto.jmx;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.jboss.logging.Logger;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class KeyStoreReloadManagement implements KeyStoreReloadManagementMBean {

    private static final Logger LOG = Logger.getLogger(KeyStoreReloadManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-lib,name=KeyStoreReloadManagement";

    @Inject
    Instance<CryptoProvider> cryptoProviders;

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
    public String reloadKeyStore(String storeType, String alias) {
        for (CryptoProvider provider : cryptoProviders) {
            for (KeyStoreDescriptor desc : provider.listKeyStores()) {
                if (desc.alias.value().equals(alias)) {
                    return reloadDescriptor(desc);
                }
            }
        }
        return "{\"alias\":\"" + alias + "\",\"availability\":\"KEY_NOT_FOUND\",\"error\":\"alias not registered\"}";
    }

    @Override
    public String reloadAllKeyStores() {
        List<KeyStoreDescriptor> stores = new ArrayList<>();
        for (CryptoProvider provider : cryptoProviders) {
            stores.addAll(provider.listKeyStores());
        }

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (KeyStoreDescriptor desc : stores) {
            if (!first)
                sb.append(",");
            sb.append("\"").append(desc.alias.value()).append("\":").append(reloadDescriptor(desc));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String reloadDescriptor(KeyStoreDescriptor desc) {
        try {
            if (desc.sourceType.name().equals("SICCT")) {
                return "{\"alias\":\"" + desc.alias.value() + "\",\"availability\":\"" +
                        desc.getAvailability() + "\",\"error\":null}";
            }
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
