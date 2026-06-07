package de.servicehealtherx.crypto.jmx;

import de.servicehealtherx.crypto.TslDownloader;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.time.Instant;

@Startup
@ApplicationScoped
public class TslManagement implements TslManagementMBean {

    private static final Logger LOG = Logger.getLogger(TslManagement.class);
    private static final String OBJECT_NAME = "de.servicehealtherx:module=crypto-lib,name=TslManagement";
    @Inject
    TslDownloader tslDownloader;

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
                LOG.infof("[JMX] deregistered %s", OBJECT_NAME);
            }
        } catch (Exception e) {
            LOG.warnf(e, "[JMX] failed to deregister %s", OBJECT_NAME);
        }
    }

    @Override
    public String getTslUrl() {
        return tslDownloader.getTslUrl();
    }

    @Override
    public String reloadTsl() {
        try {
            tslDownloader.refreshTspServiceList();

            return "{\"status\":\"OK\"}";
        } catch (Exception e) {
            return "{\"status\":\"FAILED\",\"sequenceNumber\":0,\"expiry\":null,\"downloadedAt\":\"" +
                    Instant.now() + "\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Override
    public String tslStatus() {
        TslDownloader.TslState state = tslDownloader.getCurrentState();
        return "{\"sequenceNumber\":" + state.sequenceNumber() +
                ",\"expiry\":\"" + state.expiry() + "\",\"downloadedAt\":\"" + state.downloadedAt() +
                "\",\"status\":\"" + state.status() + "\"}";
    }
}
