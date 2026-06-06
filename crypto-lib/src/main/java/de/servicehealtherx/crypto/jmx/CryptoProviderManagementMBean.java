package de.servicehealtherx.crypto.jmx;

public interface CryptoProviderManagementMBean {

    String listKeyStores();

    String listKeyReferences();

    String getAvailability(String alias);

    String getAvailabilities();
}
