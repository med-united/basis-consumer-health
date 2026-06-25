package de.servicehealtherx.quarkus.sicct.runtime.jmx;

public interface SicctTerminalConnectionManagementMBean {

    String connect(String terminalId);

    String disconnect(String terminalId);

    String getTerminalStatus(String terminalId);

    String listAllTerminals();

    /**
     * Triggers an EHEALTH TERMINAL AUTHENTICATE (CREATE) run — i.e. a pairing — for
     * the terminal identified by the given CTID (UUID), hostname or MAC address.
     */
    String pair(String ctid);
}
