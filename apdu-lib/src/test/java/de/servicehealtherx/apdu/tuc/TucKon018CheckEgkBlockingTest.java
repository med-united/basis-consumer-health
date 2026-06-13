package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon018CheckEgkBlockingTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4579_02_generate_check_blocking_returns_read_binary_step() {
        var tuc = new TucKon018CheckEgkBlocking();

        var result = tuc.generateCheckEgkBlocking(newSession(CardType.EGK), false);

        assertFalse(result.steps().isEmpty());
        assertEquals(0xB0, result.steps().get(result.steps().size() - 1).command().getINS());
    }

    @Test
    void test_TIP1_A_4579_02_hca_only_flag_in_hints() {
        var tuc = new TucKon018CheckEgkBlocking();

        var result = tuc.generateCheckEgkBlocking(newSession(CardType.EGK), true);

        assertEquals(Boolean.TRUE, result.semanticOutputHints().get("checkHcaOnly"));
    }
}
