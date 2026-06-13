package de.servicehealtherx.apdu.model;

public enum PinRef {
    PIN_CH("PIN.CH", (byte) 0x01),
    PIN_QES("PIN.QES", (byte) 0x02),
    PIN_AMTS_REP("PIN.AMTS_REP", (byte) 0x03),
    MRPIN_AMTS("MRPIN.AMTS", (byte) 0x06),
    PIN_OSD("PIN.OSD", (byte) 0x05);

    private final String cosName;
    private final byte reference;

    PinRef(String cosName, byte reference) {
        this.cosName = cosName;
        this.reference = reference;
    }

    public String cosName() {
        return cosName;
    }

    public byte reference() {
        return reference;
    }
}
