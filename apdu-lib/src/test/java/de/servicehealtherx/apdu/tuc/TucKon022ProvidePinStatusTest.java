package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon022ProvidePinStatusTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4570_generate_pin_status_query_has_get_data_apdu() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon022ProvidePinStatus();

        var result = tuc.generatePinStatus(session, PinRef.PIN_CH);

        assertFalse(result.steps().isEmpty());
        assertEquals(0xCA, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_4570_result_semantic_label_contains_pin_ref() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon022ProvidePinStatus();

        var result = tuc.generatePinStatus(session, PinRef.PIN_CH);

        assertTrue(result.steps().get(0).semanticLabel().contains("PIN.CH"));
        assertEquals(PinRef.PIN_CH, result.semanticOutputHints().get("pinRef"));
    }
}
