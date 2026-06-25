package de.servicehealtherx.quarkus.sicct.runtime.jmx;

public interface SicctTerminalConnectionManagementMBean {

    String connect(String terminalId);

    String disconnect(String terminalId);

    String getTerminalStatus(String terminalId);

    String listAllTerminals();

    /**
     * Starts the TUC_KON_053 pairing for the terminal identified by the given CTID
     * (UUID), hostname or MAC address. Establishes the pairing TLS connection and
     * returns the KT-certificate fingerprint for the administrator to verify. The flow
     * then pauses until {@link #confirmFingerprint(String)} or
     * {@link #rejectFingerprint(String)} is called with that fingerprint; the whole
     * process times out after 30 seconds.
     */
    String requestPairTerminal(String ctid);

    /**
     * Confirms the fingerprint returned by {@link #requestPairTerminal(String)} and lets
     * the paused pairing flow continue through to AKTIV. Pass the fingerprint verbatim.
     */
    String confirmFingerprint(String fingerprint);

    /**
     * Rejects the fingerprint returned by {@link #requestPairTerminal(String)} and
     * cancels the paused pairing flow. Pass the fingerprint verbatim.
     */
    String rejectFingerprint(String fingerprint);
}
