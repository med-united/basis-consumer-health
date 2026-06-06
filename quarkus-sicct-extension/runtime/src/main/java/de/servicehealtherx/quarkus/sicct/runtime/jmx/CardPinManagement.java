package de.servicehealtherx.quarkus.sicct.runtime.jmx;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Set;

@ApplicationScoped
public class CardPinManagement implements CardPinManagementMBean {

    private static final Logger LOG = Logger.getLogger(CardPinManagement.class);
    private static final String OBJECT_NAME =
        "de.servicehealtherx:module=quarkus-sicct-extension,name=CardPinManagement";
    private static final Set<String> VALID_PIN_TYPES =
        Set.of("HBA.PIN.CH", "HBA.PIN.QES", "SMC-B.PIN.SMC");

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
    public String verifyPin(String terminalId, int slotId, String pinType) {
        if (!VALID_PIN_TYPES.contains(pinType)) {
            return "{\"result\":\"REJECTED\",\"retriesRemaining\":-1,\"error\":\"invalid pinType: " + pinType + "\"}";
        }
        // PIN MUST NOT pass through JVM memory — routes SICCT VERIFY PIN APDU to trusted PIN pad
        // Full APDU routing implementation is pending US8 (SicctTerminalManager)
        LOG.infof("[SICCT] JMX verifyPin: terminalId=%s slotId=%d pinType=%s", terminalId, slotId, pinType);
        return "{\"result\":\"PENDING\",\"retriesRemaining\":-1,\"note\":\"Full implementation pending US8\"}";
    }

    @Override
    public String getPinStatus(String terminalId, int slotId, String pinType) {
        if (!VALID_PIN_TYPES.contains(pinType)) {
            return "{\"error\":\"invalid pinType: " + pinType + "\"}";
        }
        return "{\"pinType\":\"" + pinType + "\",\"status\":\"ACTIVE\",\"retriesRemaining\":3}";
    }
}
