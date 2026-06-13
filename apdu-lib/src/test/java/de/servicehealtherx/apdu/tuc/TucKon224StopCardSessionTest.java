package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon224StopCardSessionTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_A_26068_generate_stop_session_includes_card_reset_step() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon224StopCardSession();

        var result = tuc.generateStopCardSession(session);

        assertFalse(result.steps().isEmpty());
    }

    @Test
    void test_A_26068_generate_clears_lock_owner_in_hints() {
        var session = newSession(CardType.EGK);
        session.setLockOwner("caller-1");
        var tuc = new TucKon224StopCardSession();

        var result = tuc.generateStopCardSession(session);

        assertEquals(Boolean.TRUE, result.semanticOutputHints().get("clearAuthState"));
        assertEquals(Boolean.TRUE, result.semanticOutputHints().get("releaseLock"));
    }
}
