package de.servicehealtherx.quarkus.sicct.runtime.jmx;

import de.servicehealtherx.quarkus.sicct.runtime.discovery.CardTerminalDiscovery;
import de.servicehealtherx.quarkus.sicct.runtime.discovery.CardTerminalDiscovery.DiscoveredTerminal;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Startup
public class SicctTerminalDiscoveryManagement implements SicctTerminalDiscoveryManagementMBean {

    private static final Logger LOG = Logger.getLogger(SicctTerminalDiscoveryManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalDiscoveryManagement";

    @Inject
    CardTerminalDiscovery cardTerminalDiscovery;

    private final List<DiscoveredTerminal> lastDiscovery = new ArrayList<>();

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
    public void triggerDiscovery() {
        LOG.infof("[SICCT] JMX triggerDiscovery: sending SICCT Dienstanfrage broadcast on port %d",
                CardTerminalDiscovery.SICCT_DISCOVERY_PORT);
        try {
            List<DiscoveredTerminal> found = cardTerminalDiscovery.discover();
            synchronized (lastDiscovery) {
                lastDiscovery.clear();
                lastDiscovery.addAll(found);
            }
        } catch (Exception e) {
            LOG.warnf("[SICCT] discovery failed: %s", e.getMessage());
        }
    }

    @Override
    public String getLastDiscoveryResult() {
        List<DiscoveredTerminal> snapshot;
        synchronized (lastDiscovery) {
            snapshot = new ArrayList<>(lastDiscovery);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < snapshot.size(); i++) {
            DiscoveredTerminal t = snapshot.get(i);
            if (i > 0)
                sb.append(",");
            sb.append("{\"name\":\"").append(escapeJson(t.name()))
                    .append("\",\"ipAddress\":\"").append(t.ipAddress())
                    .append("\",\"macAddress\":\"").append(t.macAddressHex())
                    .append("\",\"commandPort\":").append(t.commandPort())
                    .append(",\"protocolVersion\":\"")
                    .append(t.protocolVersionMajor()).append('.').append(t.protocolVersionMinor())
                    .append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
