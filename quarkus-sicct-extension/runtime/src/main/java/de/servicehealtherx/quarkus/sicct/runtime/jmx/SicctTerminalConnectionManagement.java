package de.servicehealtherx.quarkus.sicct.runtime.jmx;

import de.servicehealtherx.quarkus.sicct.runtime.Role;
import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalConnection;
import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalManager;
import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    @Inject
    SicctTerminalManager manager;

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
    public String connect(String terminalId) {
        CardTerminal terminal = CardTerminal.resolve(terminalId);
        if (terminal == null)
            return "TERMINAL_NOT_FOUND";
        // Begin a card-terminal session in the default User role (TUC_KON_050).
        LOG.infof("[SICCT] JMX connect requested for terminal=%s (hostname=%s)", terminalId, terminal.hostname);
        return manager.TUC_KON_050_startCardTerminalSession(terminal.ctid, Role.USER);
    }

    @Override
    @Transactional
    public String disconnect(String terminalId) {
        CardTerminal terminal = CardTerminal.resolve(terminalId);
        if (terminal == null)
            return "TERMINAL_NOT_FOUND";
        LOG.infof("[SICCT] JMX disconnect requested for terminal=%s (hostname=%s)", terminalId, terminal.hostname);
        return manager.disconnectTerminal(terminal);
    }

    @Override
    @Transactional
    public String getTerminalStatus(String terminalId) {
        CardTerminal terminal = CardTerminal.resolve(terminalId);
        if (terminal == null) {
            return "{\"error\":\"terminal not found: " + terminalId + "\"}";
        }

        // Prefer the live in-memory connection: its CardTerminal instance carries the
        // authoritative, up-to-the-moment correlation state. The freshly resolved DB
        // row only reflects the last persisted transition. When no connection is
        // active (terminal offline), the DB row is the best available answer.
        SicctTerminalConnection conn = manager.getConnections().get(terminal.macAddress);
        CardTerminal live = conn != null ? conn.getTerminal() : terminal;
        String connectionState = conn != null ? conn.getConnectionState().name() : "DISCONNECTED";
        boolean connected = conn != null
                && conn.getConnectionState() == SicctTerminalConnection.ConnectionState.CONNECTED;

        return "{\"ctid\":\"" + live.ctid +
                "\",\"hostname\":\"" + live.hostname +
                "\",\"connected\":" + connected +
                ",\"connectionState\":\"" + connectionState +
                "\",\"ipAddress\":\"" + live.ipAddress +
                "\",\"tcpPort\":" + live.tcpPort +
                ",\"correlation\":\"" + live.correlation +
                "\",\"activeSlots\":0}";
    }

    @Override
    @Transactional
    public String pair(String ctid) {
        CardTerminal terminal = CardTerminal.resolve(ctid);
        if (terminal == null)
            return "{\"error\":\"terminal not found: " + ctid + "\"}";

        SicctTerminalConnection conn = manager.getConnections().get(terminal.macAddress);
        if (conn == null)
            return "{\"error\":\"no active connection for terminal: " + terminal.hostname + "\"}";
        if (conn.getSicctChannelHandler() == null)
            return "{\"error\":\"terminal not ready (no SICCT channel): " + terminal.hostname + "\"}";

        LOG.infof("[SICCT] JMX pair (EHEALTH TERMINAL AUTHENTICATE) requested for terminal=%s (hostname=%s)",
                ctid, terminal.hostname);
        conn.pairTerminal();
        return "{\"ctid\":\"" + terminal.ctid +
                "\",\"hostname\":\"" + terminal.hostname +
                "\",\"pairing\":\"EHEALTH_AUTHENTICATE_TRIGGERED\"}";
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
