package de.servicehealtherx.konnektor.vsdm;

/**
 * gematik error codes used by the local ReadVSD flow. The 30xx codes are VSDM Fachmodul codes
 * (gemSpec_SST_PS_VSDM Tab_SST_PS_VSDM_10); the 1xx codes are gemSpec_OM codes for eGK
 * application/certificate problems. The negative sentinels are local-only-service rejections that
 * have no normative VSDM code (the konnektor returns them as generic technical faults).
 */
public final class VsdmErrorCode {

    private VsdmErrorCode() {}

    // VSDM Fachmodul (Tab_SST_PS_VSDM_10)
    public static final int VSD_INCONSISTENT = 3001;   // EF.StatusVD Status='1'
    public static final int VSD_READ_FAILED = 3011;    // reading PD/VD/GVD from the eGK failed
    public static final int SMB_NOT_ENABLED = 3041;    // SMC-B security state insufficient (C2C)
    public static final int HBA_NOT_ENABLED = 3042;    // HBA security state insufficient (C2C)

    // gemSpec_OM (eGK application / certificate)
    public static final int EGK_CERT_REVOKED = 106;    // AUT certificate online-revoked
    public static final int EGK_CERT_INVALID = 107;    // AUT certificate offline-invalid
    public static final int HCA_BLOCKED = 114;         // DF.HCA (health application) blocked

    // Local-only-service rejections (no normative VSDM code)
    public static final int ONLINE_CHECK_NOT_SUPPORTED = -1;
    public static final int RECEIPT_NOT_SUPPORTED = -2;
    public static final int CARD_BUSY = -3;
    public static final int TIMEOUT = -4;
    public static final int INVALID_REQUEST = -5;
    public static final int UNSUPPORTED_CARD_GENERATION = -6;
}
