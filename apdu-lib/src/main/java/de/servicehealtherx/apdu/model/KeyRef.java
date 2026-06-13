package de.servicehealtherx.apdu.model;

public enum KeyRef {
    C_AUT((byte) 0x04, "C.AUT"),
    C_ENC((byte) 0x02, "C.ENC"),
    C_QES((byte) 0x06, "C.QES");

    private final byte reference;
    private final String cosName;

    KeyRef(byte reference, String cosName) {
        this.reference = reference;
        this.cosName = cosName;
    }

    public byte reference() {
        return reference;
    }

    public String cosName() {
        return cosName;
    }
}
