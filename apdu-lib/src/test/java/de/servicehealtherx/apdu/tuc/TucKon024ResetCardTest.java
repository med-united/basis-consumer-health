package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon024ResetCardTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4584_02_generate_reset_returns_reset_apdu_step() {
        var tuc = new TucKon024ResetCard();

        var result = tuc.generateResetCard(newSession(CardType.EGK));

        assertFalse(result.steps().isEmpty());
    }

    @Test
    void test_TIP1_A_4584_02_hints_signal_auth_state_clear() {
        var tuc = new TucKon024ResetCard();

        var result = tuc.generateResetCard(newSession(CardType.EGK));

        assertEquals(Boolean.TRUE, result.semanticOutputHints().get("clearAuthState"));
        assertEquals(Boolean.TRUE, result.semanticOutputHints().get("releaseLock"));
    }
}
