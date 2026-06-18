package de.servicehealtherx.apdu.model;

public final class GematikISO7816 {

    private GematikISO7816() {}

    // Class bytes (use int to avoid sign-extension in CommandAPDU constructors)
    public static final int CLA_ISO = 0x00;
    public static final int CLA_CHAIN = 0x10;
    public static final int CLA_SM = 0x0C;

    // Instruction bytes — ISO 7816-4
    public static final int INS_SELECT = 0xA4;
    public static final int INS_READ_BINARY = 0xB0;
    public static final int INS_UPDATE_BINARY = 0xD6;
    public static final int INS_ERASE_BINARY = 0x0E;
    public static final int INS_READ_RECORD = 0xB2;
    public static final int INS_UPDATE_RECORD = 0xDC;
    public static final int INS_APPEND_RECORD = 0xE2;
    public static final int INS_SEARCH_RECORD = 0xA2;
    public static final int INS_ERASE_RECORD = 0x0C;
    public static final int INS_VERIFY = 0x20;
    public static final int INS_CHANGE_REFERENCE_DATA = 0x24;
    public static final int INS_RESET_RETRY_COUNTER = 0x2C;
    public static final int INS_DISABLE_VERIFICATION_REQUIREMENT = 0x26;
    public static final int INS_ENABLE_VERIFICATION_REQUIREMENT = 0x28;
    public static final int INS_GET_DATA = 0xCA;
    public static final int INS_PUT_DATA = 0xDA;
    public static final int INS_MANAGE_SECURITY_ENV = 0x22;
    public static final int INS_INTERNAL_AUTHENTICATE = 0x88;
    public static final int INS_EXTERNAL_AUTHENTICATE = 0x82;
    public static final int INS_GET_CHALLENGE = 0x84;
    public static final int INS_PERFORM_SECURITY_OPERATION = 0x2A;
    public static final int INS_ACTIVATE_FILE = 0x44;
    public static final int INS_DEACTIVATE_FILE = 0x04;
    public static final int INS_GENERAL_AUTHENTICATE = 0x86;

    // Status words — success
    public static final int SW_SUCCESS = 0x9000;

    // Status words — PIN management
    public static final int SW_PIN_WRONG_TRIES_REMAINING_BASE = 0x63C0;
    public static final int SW_AUTH_METHOD_BLOCKED = 0x6983;
    public static final int SW_PIN_TRANSPORT = 0x6984;
    public static final int SW_SECURITY_NOT_SATISFIED = 0x6982;

    // Status words — file operations
    public static final int SW_OBJECT_NOT_FOUND = 0x6A82;
    public static final int SW_RECORD_NOT_FOUND = 0x6A83;
    public static final int SW_WRONG_PARAMETERS = 0x6A86;
    public static final int SW_WRONG_LENGTH = 0x6700;
    public static final int SW_COMMAND_NOT_ALLOWED = 0x6986;

    // Status words — access control
    public static final int SW_CONDITION_NOT_SATISFIED = 0x6985;
    public static final int SW_REFERENCED_DATA_NOT_FOUND = 0x6A88;

    // Select parameters
    public static final int SELECT_BY_FILE_ID = 0x02;
    public static final int SELECT_BY_PATH = 0x08;
    public static final int SELECT_BY_DF_NAME = 0x04;
    public static final int SELECT_FIRST_OCCURRENCE = 0x00;

    // MSE tags
    public static final int MSE_SET_COMPUTE = 0x41;
    public static final int MSE_SET_VERIFY = 0xA1;
    public static final int TAG_ALGORITHM_ID = 0x80;
    public static final int TAG_KEY_REF = 0x84;

    // Well-known file identifiers (gematik)
    public static final short FID_MF = (short) 0x3F00;
    public static final short FID_EF_GDO = (short) 0x2F02;
    public static final short FID_EF_ATR = (short) 0x2F01;
    public static final short FID_EF_DIR = (short) 0x2F00;

    // Application identifiers (MF-level), as listed in each card's EF.DIR (DO '61' → '4F' AID).
    // Used to discriminate the card type once the universally-present MF files have been read.
    public static final byte[] AID_EGK = new byte[]{            // gemSpec_eGK_ObjSys_G2_1 EF.DIR
            (byte) 0xD2, 0x76, 0x00, 0x01, 0x44, (byte) 0x80, 0x00
    };
    public static final byte[] AID_HBA = new byte[]{           // gemSpec_HBA_ObjSys_G2_1 EF.DIR
            (byte) 0xD2, 0x76, 0x00, 0x01, 0x46, 0x01
    };
    public static final byte[] AID_SMC_B = new byte[]{         // gemSpec_SMC-B_ObjSys_G2_1 EF.DIR
            (byte) 0xD2, 0x76, 0x00, 0x01, 0x46, 0x06
    };

    // AID — ESIGN crypto application (present on eGK, HBA and SMC-B). gemSpec_eGK_ObjSys_G2_1 §5.5,
    // Tab_eGK_ObjSys_059: applicationIdentifier 'A000000167 455349474E'.
    public static final byte[] AID_DF_ESIGN = new byte[]{
            (byte) 0xA0, 0x00, 0x00, 0x01, 0x67, 0x45, 0x53, 0x49, 0x47, 0x4E
    };

    // AID — DF.QES qualified-signature application on the HBA (gemSpec_HBA_ObjSys_G2_1, EF.DIR DO
    // '4F' = 'D27600006601'); holds PrK.HP.QES and is protected by PIN.QES.
    public static final byte[] AID_DF_QES = new byte[]{
            (byte) 0xD2, 0x76, 0x00, 0x00, 0x66, 0x01
    };

    // eGK C.CH.AUT certificate files inside DF.ESIGN (gemSpec_eGK_ObjSys_G2_1 §5.5.1 / §5.5.9).
    // The ECC certificate is preferred over the RSA certificate when present (TUC_KON_001 §2c).
    public static final short FID_EF_C_CH_AUT_E256 = (short) 0xC504;  // Tab_eGK_ObjSys_200
    public static final short FID_EF_C_CH_AUT_R2048 = (short) 0xC500; // Tab_eGK_ObjSys_060

    // HBA C.HP.AUT certificate files inside DF.ESIGN (gemSpec_HBA_ObjSys_G2_1 §5.6.2.3 / §5.6.2.7).
    public static final short FID_EF_C_HP_AUT_E256 = (short) 0xC506;  // Tab_HBA_ObjSys_174
    public static final short FID_EF_C_HP_AUT_R2048 = (short) 0xC500; // Tab_HBA_ObjSys_055

    // SMC-B C.HCI.AUT certificate files inside DF.ESIGN (gemSpec_SMC-B_ObjSys_G2_1 §5.4.2.2 / §5.4.2.8).
    public static final short FID_EF_C_HCI_AUT_E256 = (short) 0xC506;  // Tab_SMC-B_ObjSys_122
    public static final short FID_EF_C_HCI_AUT_R2048 = (short) 0xC500; // Tab_SMC-B_ObjSys_042

    // SMC-B C.HCI.OSIG (organisation signature) certificate files inside DF.ESIGN
    // (gemSpec_SMC-B_ObjSys_G2_1 §5.4.2.1 / §5.4.2.7).
    public static final short FID_EF_C_HCI_OSIG_E256 = (short) 0xC007;  // Tab_SMC-B_ObjSys_120
    public static final short FID_EF_C_HCI_OSIG_R2048 = (short) 0xC000; // Tab_SMC-B_ObjSys_041

    public static int pinTriesRemaining(int sw) {
        return sw & 0x0F;
    }

    public static boolean isPinWrongTriesRemaining(int sw) {
        return (sw & 0xFFF0) == SW_PIN_WRONG_TRIES_REMAINING_BASE;
    }
}
