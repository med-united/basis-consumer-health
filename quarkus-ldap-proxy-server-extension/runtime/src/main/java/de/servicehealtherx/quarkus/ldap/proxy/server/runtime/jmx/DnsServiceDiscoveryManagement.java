package de.servicehealtherx.quarkus.ldap.proxy.server.runtime.jmx;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DnsServiceDiscoveryManagement implements DnsServiceDiscoveryManagementMBean {

    private static final Logger LOG = Logger.getLogger(DnsServiceDiscoveryManagement.class);
    private static final String OBJECT_NAME =
        "de.servicehealtherx:module=quarkus-ldap-proxy-server-extension,name=DnsServiceDiscoveryManagement";

    private final Map<String, List<ServiceRecord>> discoveredServices = new ConcurrentHashMap<>();

    record ServiceRecord(String service, String host, int port, int priority, int weight) {}

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
    public String triggerServiceDiscovery(String domain) {
        if (domain == null || domain.isBlank()) {
            LOG.infof("[DNS-SD] re-discovering all configured zones");
        } else {
            LOG.infof("[DNS-SD] triggerServiceDiscovery for domain=%s", domain);
            discoverDomain(domain);
        }

        List<ServiceRecord> records = discoveredServices.getOrDefault(domain != null ? domain : "", List.of());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < records.size(); i++) {
            ServiceRecord r = records.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"service\":\"").append(r.service())
              .append("\",\"host\":\"").append(r.host())
              .append("\",\"port\":").append(r.port())
              .append(",\"priority\":").append(r.priority())
              .append(",\"weight\":").append(r.weight()).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public String getDiscoveredServices() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<ServiceRecord>> entry : discoveredServices.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":[");
            List<ServiceRecord> records = entry.getValue();
            for (int i = 0; i < records.size(); i++) {
                ServiceRecord r = records.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"host\":\"").append(r.host())
                  .append("\",\"port\":").append(r.port()).append("}");
            }
            sb.append("]");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private void discoverDomain(String domain) {
        try {
            String ptrQuery = "_ldap._tcp." + domain;
            // DNS SRV lookup via JNDI
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(ptrQuery, new String[]{"SRV"});
            // Parse SRV records — placeholder for full JNDI SRV parsing
            List<ServiceRecord> records = new ArrayList<>();
            discoveredServices.put(domain, records);
            LOG.infof("[DNS-SD] discovered %d service(s) for domain=%s", records.size(), domain);
        } catch (Exception e) {
            LOG.warnf("[DNS-SD] DNS-SD query failed for domain=%s: %s", domain, e.getMessage());
        }
    }
}
