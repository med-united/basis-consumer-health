package de.servicehealtherx.quarkus.sicct.runtime.jmx;

import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.quarkus.runtime.Startup;
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
@Startup
public class SicctTerminalConnectionManagement implements SicctTerminalConnectionManagementMBean {

    private static final Logger LOG = Logger.getLogger(SicctTerminalConnectionManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalConnectionManagement";

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
    @Transactional
    public String connect(String hostname) {
        CardTerminal terminal = CardTerminal.findByHostname(hostname);
        if (terminal == null)
            return "TERMINAL_NOT_FOUND";
        if (terminal.connected)
            return "CONNECTED";
        LOG.infof("[SICCT] JMX connect requested for hostname=%s", hostname);
        return "CONNECTING";
    }

    @Override
    @Transactional
    public String disconnect(String hostname) {
        CardTerminal terminal = CardTerminal.findByHostname(hostname);
        if (terminal == null)
            return "TERMINAL_NOT_FOUND";
        LOG.infof("[SICCT] JMX disconnect requested for hostname=%s", hostname);
        return "DISCONNECTED";
    }

    @Override
    @Transactional
    public String getTerminalStatus(String hostname) {
        CardTerminal terminal = CardTerminal.findByHostname(hostname);
        if (terminal == null) {
            return "{\"error\":\"terminal not found: " + hostname + "\"}";
        }
        return "{\"hostname\":\"" + terminal.hostname +
                "\",\"connected\":" + terminal.connected +
                ",\"ipAddress\":\"" + terminal.ipAddress +
                "\",\"tcpPort\":" + terminal.tcpPort +
                ",\"correlation\":\"" + terminal.correlation +
                "\",\"activeSlots\":0}";
    }

    @Override
    @Transactional
    public String listAllTerminals() {
        List<CardTerminal> terminals = CardTerminal.listAll();
        StringBuilder sb = new StringBuilder("");
        for (int i = 0; i < terminals.size(); i++) {
            CardTerminal t = terminals.get(i);
            if (i > 0)
                sb.append(",\n");
            sb.append(t.toString());
        }
        return sb.toString();
    }
}
