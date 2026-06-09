package de.servicehealtherx.crypto.jmx;

import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class CryptoProviderManagement implements CryptoProviderManagementMBean {

    private static final Logger LOG = Logger.getLogger(CryptoProviderManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-lib,name=CryptoProviderManagement";

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
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (Exception e) {
            LOG.warnf(e, "[JMX] failed to deregister %s", OBJECT_NAME);
        }
    }

    @Override
    public String listKeyStores() {
        List<KeyStoreDescriptor> stores = cryptoProviders.stream()
                .flatMap(p -> p.listKeyStores().stream())
                .collect(Collectors.toList());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < stores.size(); i++) {
            KeyStoreDescriptor d = stores.get(i);
            if (i > 0)
                sb.append(",");
            sb.append("{\"storeType\":\"").append(d.sourceType).append("\"")
                    .append(",\"alias\":\"").append(d.alias.value()).append("\"")
                    .append(",\"availability\":\"").append(d.getAvailability()).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public String listKeyReferences() {
        return listKeyStores();
    }

    @Override
    public String getAvailability(String alias) {
        try {
            for (CryptoProvider provider : cryptoProviders) {
                KeyStoreAvailability avail = provider.getAvailability(new KeyAlias(alias));
                if (avail != KeyStoreAvailability.UNAVAILABLE) {
                    return avail.name();
                }
            }
            return KeyStoreAvailability.UNAVAILABLE.name();
        } catch (IllegalArgumentException e) {
            return "KEY_NOT_FOUND";
        }
    }

    @Override
    public String getAvailabilities() {
        Map<String, KeyStoreAvailability> map = cryptoProviders.stream()
                .flatMap(p -> p.getAvailabilities().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, KeyStoreAvailability> entry : map.entrySet()) {
            if (!first)
                sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
