package de.servicehealtherx.sicct.event;

/**
 * CDI event fired when certificate validation for an SMC-B / HBAx card yields a non-good result
 * (gemSpec_Kon topic {@code CERT/CARD/STATUS}, TUC_KON_256, severity Warning; FR-048).
 * {@code certStatus} values per TAB_KON_285 (Invalid / Inconclusive / Unknown / Revoked).
 */
public record CertCardStatusEvent(
        String cardHandle,
        String cardType,
        String iccsn,
        String cardHolderName,
        String certName,
        String certStatus) {
}
