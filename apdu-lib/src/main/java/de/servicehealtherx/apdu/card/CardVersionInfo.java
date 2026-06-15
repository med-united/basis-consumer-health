package de.servicehealtherx.apdu.card;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import de.servicehealtherx.apdu.model.CardType;

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

    /**
     * Render the {@code CardVersion} value for the TUC_KON_256 {@code CARD/INSERTED} event
     * parameter (TUC_KON_001 step 3): always {@code COSVERSION} and {@code OBJECTSYSTEMVERSION};
     * for an eGK additionally {@code DATASTRUCTUREVERSION} (required for eGK G1+). Sub-fields that
     * could not be read are omitted. Returns an empty string if none are available.
     */
    public String toEventParameter(CardType type) {
        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, "COSVERSION", cosVersion);
        putIfPresent(fields, "OBJECTSYSTEMVERSION", objectSystemVersion);
        if (type == CardType.EGK) {
            putIfPresent(fields, "DATASTRUCTUREVERSION", dataStructureVersion);
        }
        return fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
