package de.servicehealtherx.quarkus.sicct.runtime.jmx;

import de.servicehealtherx.sicct.jpa.CardTerminal;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.List;

@ApplicationScoped
public class SicctTerminalConnectionManagement implements SicctTerminalConnectionManagementMBean {

    private static final Logger LOG = Logger.getLogger(SicctTerminalConnectionManagement.class);
    private static final String OBJECT_NAME =
        "de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalConnectionManagement";

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
    @Transactional
    public String connect(String terminalId) {
        CardTerminal terminal = CardTerminal.findByTerminalId(terminalId);
        if (terminal == null) return "TERMINAL_NOT_FOUND";
        if ("CONNECTED".equals(terminal.pairingStatus)) return "CONNECTED";
        // SicctTerminalManager would initiate TCP connection here (US8 implementation)
        LOG.infof("[SICCT] JMX connect requested for terminalId=%s", terminalId);
        return "CONNECTING";
    }

    @Override
    @Transactional
    public String disconnect(String terminalId) {
        CardTerminal terminal = CardTerminal.findByTerminalId(terminalId);
        if (terminal == null) return "TERMINAL_NOT_FOUND";
        LOG.infof("[SICCT] JMX disconnect requested for terminalId=%s", terminalId);
        return "DISCONNECTED";
    }

    @Override
    @Transactional
    public String getTerminalStatus(String terminalId) {
        CardTerminal terminal = CardTerminal.findByTerminalId(terminalId);
        if (terminal == null) {
            return "{\"error\":\"terminal not found: " + terminalId + "\"}";
        }
        return "{\"terminalId\":\"" + terminal.terminalId +
            "\",\"connectionState\":\"" + terminal.pairingStatus +
            "\",\"host\":\"" + terminal.host +
            "\",\"port\":" + terminal.port +
            ",\"pairingStatus\":\"" + terminal.pairingStatus +
            "\",\"activeSlots\":0}";
    }

    @Override
    @Transactional
    public String listAllTerminals() {
        List<CardTerminal> terminals = CardTerminal.listAll();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < terminals.size(); i++) {
            CardTerminal t = terminals.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"terminalId\":\"").append(t.terminalId)
              .append("\",\"host\":\"").append(t.host)
              .append("\",\"port\":").append(t.port)
              .append(",\"pairingStatus\":\"").append(t.pairingStatus).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }
}
