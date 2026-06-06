package de.servicehealtherx.quarkus.sicct.runtime.jmx;

public interface SicctTerminalDiscoveryManagementMBean {

    void triggerDiscovery();

    String getLastDiscoveryResult();
}
