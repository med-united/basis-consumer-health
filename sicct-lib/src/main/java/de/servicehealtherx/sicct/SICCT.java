package de.servicehealtherx.sicct;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.beanit.asn1bean.ber.types.BerInteger;

import sicct.protocol._1._3._0.SicctFuAddress;
import sicct.protocol._1._3._0.SicctMessageType;
import sicct.protocol._1._3._0.SicctPayload;

public class SICCT {
    public static SicctMessageType C_COMMAND = new SicctMessageType((byte) 0x6B);
    public static SicctMessageType R_COMMAND = new SicctMessageType((byte) 0x83);
    public static SicctMessageType EVENT = new SicctMessageType((byte) 0x50);

    public static SicctFuAddress TERMINAL_ADDRESS = new SicctFuAddress((byte) 0x00);

    // SICCT Instructions (ISO 7816 INS bytes)
    public static final byte INS_RESET_CT_ICC = (byte) 0x11; // 17
    public static final byte INS_REQUEST_ICC = (byte) 0x12; // 18
    public static final byte INS_GET_STATUS = (byte) 0x13; // 19
    public static final byte INS_EJECT_ICC = (byte) 0x15; // 21
    public static final byte INS_INPUT = (byte) 0x16; // 22
    public static final byte INS_OUTPUT = (byte) 0x17; // 23
    public static final byte INS_PERFORM_VERIFICATION = (byte) 0x18; // 24
    public static final byte INS_MODIFY_VERIFICATION_DATA = (byte) 0x19; // 25
    public static final byte INS_SELECT_CT_MODE = (byte) 0x20; // 32
    public static final byte INS_COMFORT_AUTHENTICATION = (byte) 0x21; // 33
    public static final byte INS_COMFORT_ENROLL = (byte) 0x22; // 34
    public static final byte INS_SET_STATUS = (byte) 0x23; // 35
    public static final byte INS_DOWNLOAD_INIT = (byte) 0x24; // 36
    public static final byte INS_DOWNLOAD_DATA = (byte) 0x25; // 37
    public static final byte INS_DOWNLOAD_FINISH = (byte) 0x26; // 38
    public static final byte INS_CONTROL_COMMAND = (byte) 0x27; // 39
    public static final byte INS_INIT_CT_SESSION = (byte) 0x28; // 40
    public static final byte INS_CLOSE_CT_SESSION = (byte) 0x29; // 41

    public static final byte INS_EHEALTH_TERMINAL_AUTHENTICATE = (byte) 0xAA; // 170

    public static final byte P1_CARD_TERMINAL = (byte) 0x00;

    // Data Objects served by all Functional Units
    public static final byte P2_GET_STATUS_FUNCTIONAL_UNIT_NAME = (byte) 0xA1;

    // In case P1 addresses the Cardterminal (CT):
    public static final byte P2_GET_STATUS_CARD_TERMINAL_MANUFACTURER = (byte) 0x46;
    public static final byte P2_GET_STATUS_CARD_TERMINAL_STATUS = (byte) 0x63;
    public static final byte P2_GET_STATUS_ALL_ICC = (byte) 0x80;
    public static final byte P2_GET_STATUS_FUNCTIONAL_UNIT_DATA = (byte) 0x81;

    public static BerInteger length(SicctPayload payload) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            payload.encode(baos);
            return new BerInteger(baos.size());
        } catch (IOException e) {
            throw new RuntimeException("Failed to calculate payload length", e);
        }
    }

}