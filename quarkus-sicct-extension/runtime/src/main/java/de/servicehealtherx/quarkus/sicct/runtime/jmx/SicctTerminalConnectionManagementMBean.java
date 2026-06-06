package de.servicehealtherx.quarkus.sicct.runtime.jmx;

public interface SicctTerminalConnectionManagementMBean {

    String connect(String terminalId);

    String disconnect(String terminalId);

    String getTerminalStatus(String terminalId);

    String listAllTerminals();
}
