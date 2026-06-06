package de.servicehealtherx.quarkus.ldap.proxy.server.runtime.jmx;

public interface DnsServiceDiscoveryManagementMBean {

    String triggerServiceDiscovery(String domain);

    String getDiscoveredServices();
}
