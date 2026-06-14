package de.servicehealtherx.apdu.card;

/**
 * OCSP response status for a CardObject (FR-013). Defaults to {@link #NOT_AVAILABLE} at handle
 * creation and is updated asynchronously by TUC_KON_037; remains NOT_AVAILABLE if OCSP is
 * unreachable.
 */
public enum OcspResult {
    NOT_AVAILABLE,
    GOOD,
    REVOKED,
    UNKNOWN
}
