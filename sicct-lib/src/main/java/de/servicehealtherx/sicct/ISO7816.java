package de.servicehealtherx.sicct;

public final class ISO7816 {

    private ISO7816() {
    }

    // Class Bytes (CLA)
    public static final byte CLA_ISO = (byte) 0x00;
    public static final byte CLA_PROPRIETARY = (byte) 0x80;
    public static final byte CLA_SECURE_MSG = (byte) 0x0C;

    // Instructions (INS)
    public static final byte INS_SELECT = (byte) 0xA4;
    public static final byte INS_READ_BINARY = (byte) 0xB0;
    public static final byte INS_UPDATE_BINARY = (byte) 0xD6;
    public static final byte INS_GET_DATA = (byte) 0xCA;
    public static final byte INS_PUT_DATA = (byte) 0xDA;
    public static final byte INS_VERIFY = (byte) 0x20;
    public static final byte INS_CHANGE_REF_DATA = (byte) 0x24;
    public static final byte INS_MSE = (byte) 0x22; // Manage Security Environment
    public static final byte INS_PSO = (byte) 0x2A; // Perform Security Operation
    public static final byte INS_GENERAL_AUTH = (byte) 0x87;
    public static final byte INS_GET_RESPONSE = (byte) 0xC0;
    public static final byte INS_ENVELOPE = (byte) 0xC2;
    public static final byte INS_GET_CHALLENGE = (byte) 0x84;
    public static final byte INS_INTERNAL_AUTH = (byte) 0x88;
    public static final byte INS_EXTERNAL_AUTH = (byte) 0x82;

    // Status Words (SW)
    public static final short SW_NO_ERROR = (short) 0x9000;
    public static final short SW_BYTES_REMAINING_00 = (short) 0x6100;
    public static final short SW_WRONG_LENGTH = (short) 0x6700;
    public static final short SW_SECURITY_STATUS_NOT_SATISFIED = (short) 0x6982;
    public static final short SW_FILE_NOT_FOUND = (short) 0x6A82;
    public static final short SW_INCORRECT_P1P2 = (short) 0x6A86;
    public static final short SW_INS_NOT_SUPPORTED = (short) 0x6D00;
    public static final short SW_CLA_NOT_SUPPORTED = (short) 0x6E00;
    public static final short SW_UNKNOWN = (short) 0x6F00;
}