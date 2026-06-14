package de.servicehealtherx.apdu.card;

/**
 * The eight CARDVERSION sub-fields read from a card's EF.Version and related structures
 * (gemSpec_Kon §4.1.5, data-model §CardVersion). Any sub-field that cannot be read is
 * stored as {@code null} rather than failing CardObject creation (FR-007).
 *
 * <p>This is a distinct concept from {@code de.servicehealtherx.apdu.model.CardVersion}
 * (the feature-007 generation enum); see specs/002-card-handle reconciliation note.
 */
public record CardVersionInfo(
        String cosVersion,
        String objectSystemVersion,
        String cardPersonalizationVersion,
        String dataStructureVersion,
        String loggingVersion,
        String atrVersion,
        String gdoVersion,
        String keyInfoVersion) {

    /** A CardVersionInfo with all sub-fields unread/unknown. */
    public static CardVersionInfo empty() {
        return new CardVersionInfo(null, null, null, null, null, null, null, null);
    }
}
