package de.servicehealtherx.crypto.jmx;

import de.servicehealtherx.crypto.TslDownloader;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class TslManagementTest {

    @Inject
    TslManagement tslManagement;

    @InjectMock
    TslDownloader tslDownloader;

    @BeforeEach
    void setup() {
        TslDownloader.TslState state = new TslDownloader.TslState(
            123, Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-06-06T12:00:00Z"), "VALID");
        when(tslDownloader.download()).thenReturn(state);
        when(tslDownloader.getCurrentState()).thenReturn(state);
        when(tslDownloader.getTslUrl()).thenReturn("https://download.tsl.ti-dienste.de/ECC/EK/ECC-RSA_TSL-ref.xml");
    }

    @Test
    void test_FR220_TslManagement_reloadTsl_invokes_tsl_downloader() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("de.servicehealtherx:module=crypto-lib,name=TslManagement");

        assertTrue(server.isRegistered(name), "TslManagement MBean must be registered on platform MBeanServer");

        String result = (String) server.invoke(name, "reloadTsl", new Object[0], new String[0]);

        verify(tslDownloader).download();
        assertNotNull(result, "reloadTsl must return JSON");
        assertTrue(result.contains("\"status\":\"OK\""), "Result must contain status:OK");
        assertTrue(result.contains("\"sequenceNumber\":123"), "Result must include sequence number");
    }

    @Test
    void test_FR220_TslManagement_getTslStatus_returns_json() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("de.servicehealtherx:module=crypto-lib,name=TslManagement");

        String result = (String) server.invoke(name, "tslStatus", new Object[0], new String[0]);

        assertNotNull(result, "getTslStatus must return JSON");
        assertTrue(result.contains("sequenceNumber"), "Result must contain sequenceNumber field");
        assertTrue(result.contains("status"), "Result must contain status field");
    }

    @Test
    void test_FR220_TslManagement_getTslUrl_returns_configured_url() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("de.servicehealtherx:module=crypto-lib,name=TslManagement");

        String url = (String) server.getAttribute(name, "TslUrl");

        assertNotNull(url, "TslUrl attribute must not be null");
        assertFalse(url.isBlank(), "TslUrl must not be blank");
    }
}
