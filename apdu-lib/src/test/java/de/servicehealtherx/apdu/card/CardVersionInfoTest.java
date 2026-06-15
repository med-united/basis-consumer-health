package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.model.CardType;

/**
 * Unit tests for {@link CardVersionInfo#toEventParameter(CardType)} — the TUC_KON_001 step 3 rule
 * for the {@code CardVersion} parameter of the {@code CARD/INSERTED} event: COSVERSION and
 * OBJECTSYSTEMVERSION always; DATASTRUCTUREVERSION additionally for eGK; unreadable fields omitted.
 */
class CardVersionInfoTest {

    private static CardVersionInfo version(String cos, String objSys, String dataStruct) {
        return new CardVersionInfo(cos, objSys, null, dataStruct, null, null, null, null);
    }

    @Test
    void egk_includes_data_structure_version() {
        CardVersionInfo v = version("4.4.0", "3.0.0", "1.2.0");
        assertEquals("COSVERSION=4.4.0, OBJECTSYSTEMVERSION=3.0.0, DATASTRUCTUREVERSION=1.2.0",
                v.toEventParameter(CardType.EGK));
    }

    @Test
    void non_egk_omits_data_structure_version() {
        CardVersionInfo v = version("4.4.0", "3.0.0", "1.2.0");
        assertEquals("COSVERSION=4.4.0, OBJECTSYSTEMVERSION=3.0.0",
                v.toEventParameter(CardType.SMC_B));
    }

    @Test
    void unreadable_subfields_are_omitted() {
        CardVersionInfo v = version(null, "3.0.0", null);
        assertEquals("OBJECTSYSTEMVERSION=3.0.0", v.toEventParameter(CardType.EGK));
    }

    @Test
    void all_unreadable_yields_empty_string() {
        assertEquals("", CardVersionInfo.empty().toEventParameter(CardType.EGK));
    }
}
