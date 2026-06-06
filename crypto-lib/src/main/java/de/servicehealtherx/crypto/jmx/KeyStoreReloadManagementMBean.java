package de.servicehealtherx.crypto.jmx;

public interface KeyStoreReloadManagementMBean {

    String reloadKeyStore(String storeType, String alias);

    String reloadAllKeyStores();
}
