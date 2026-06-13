package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TucKon223StartCardSessionTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_A_26067_generate_start_session_result_contains_session_uuid_in_hints() {
        var tuc = new TucKon223StartCardSession();

        var result = tuc.generateStartCardSession(newSession(CardType.EGK));

        assertTrue(result.semanticOutputHints().containsKey("sessionId"));
        assertInstanceOf(UUID.class, result.semanticOutputHints().get("sessionId"));
    }

    @Test
    void test_A_26067_generate_start_session_has_manage_security_env_step() {
        var tuc = new TucKon223StartCardSession();

        var result = tuc.generateStartCardSession(newSession(CardType.EGK));

        assertFalse(result.steps().isEmpty());
        assertEquals(0x22, result.steps().get(0).command().getINS());
    }
}
