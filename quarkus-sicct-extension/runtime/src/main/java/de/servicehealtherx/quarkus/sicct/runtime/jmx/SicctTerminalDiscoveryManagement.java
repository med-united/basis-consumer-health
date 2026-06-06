package de.servicehealtherx.quarkus.sicct.runtime.jmx;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SicctTerminalDiscoveryManagement implements SicctTerminalDiscoveryManagementMBean {

    private static final Logger LOG = Logger.getLogger(SicctTerminalDiscoveryManagement.class);
    private static final String OBJECT_NAME =
        "de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalDiscoveryManagement";
    private static final int SICCT_UDP_PORT = 4876;

    private final List<DiscoveredTerminal> lastDiscovery = new ArrayList<>();

    record DiscoveredTerminal(String terminalId, String host, int port, Instant discoveredAt) {}

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
    public void triggerDiscovery() {
        LOG.infof("[SICCT] JMX triggerDiscovery: sending UDP broadcast on port %d", SICCT_UDP_PORT);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] discover = "SICCT-DISCOVER".getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(discover, discover.length,
                InetAddress.getByName("255.255.255.255"), SICCT_UDP_PORT);
            socket.send(packet);
            lastDiscovery.clear();
            LOG.infof("[SICCT] discovery broadcast sent");
        } catch (Exception e) {
            LOG.warnf("[SICCT] discovery broadcast failed: %s", e.getMessage());
        }
    }

    @Override
    public String getLastDiscoveryResult() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lastDiscovery.size(); i++) {
            DiscoveredTerminal t = lastDiscovery.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"terminalId\":\"").append(t.terminalId())
              .append("\",\"host\":\"").append(t.host())
              .append("\",\"port\":").append(t.port())
              .append(",\"discoveredAt\":\"").append(t.discoveredAt()).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }
}
