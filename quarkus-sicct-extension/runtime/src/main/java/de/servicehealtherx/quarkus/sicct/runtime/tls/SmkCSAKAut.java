package de.servicehealtherx.quarkus.sicct.runtime.tls;

import javax.net.ssl.KeyManagerFactory;

public class SmkCSAKAut {

    private KeyManagerFactory keyManagerFactory;

    public KeyManagerFactory getKeyManagerFactory() {
        return keyManagerFactory;
    }

    public void setKeyManagerFactory(KeyManagerFactory keyManagerFactory) {
        this.keyManagerFactory = keyManagerFactory;
    }

}
