package de.servicehealtherx.apdu.card;

/**
 * Certificate validation status for a CardObject (FR-012). Defaults to {@link #NOT_AVAILABLE}
 * at handle creation and is updated asynchronously by TUC_KON_037 validation.
 */
public enum CertStatus {
    NOT_AVAILABLE,
    VALID,
    INVALID,
    INCONCLUSIVE
}
