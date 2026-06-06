package de.servicehealtherx.crypto.jmx;

public interface TslManagementMBean {

    String getTslUrl();

    String reloadTsl();

    String getTslStatus();
}
