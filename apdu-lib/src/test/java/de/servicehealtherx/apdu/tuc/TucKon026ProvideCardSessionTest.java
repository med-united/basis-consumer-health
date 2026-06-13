package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TucKon026ProvideCardSessionTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4567_provide_card_session_result_has_select_apdu_step() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon026ProvideCardSession();

        var result = tuc.generateProvideCardSession(session);

        assertFalse(result.steps().isEmpty());
        assertEquals(0xA4, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_4567_provide_card_session_session_id_in_hints() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon026ProvideCardSession();

        var result = tuc.generateProvideCardSession(session);

        assertTrue(result.semanticOutputHints().containsKey("sessionId"));
        assertInstanceOf(UUID.class, result.semanticOutputHints().get("sessionId"));
    }
}
