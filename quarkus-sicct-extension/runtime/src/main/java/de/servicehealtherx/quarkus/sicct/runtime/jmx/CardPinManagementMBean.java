package de.servicehealtherx.quarkus.sicct.runtime.jmx;

public interface CardPinManagementMBean {

    String verifyPin(String terminalId, int slotId, String pinType);

    String getPinStatus(String terminalId, int slotId, String pinType);
}
