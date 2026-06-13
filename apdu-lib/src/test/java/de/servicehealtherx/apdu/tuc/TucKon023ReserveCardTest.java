package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon023ReserveCardTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4571_03_generate_lock_returns_empty_steps_on_unlocked_card() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon023ReserveCard();

        var result = tuc.generateLock(session, "caller-1");

        assertTrue(result.steps().isEmpty());
    }

    @Test
    void test_TIP1_A_4571_03_generate_lock_throws_4093_when_already_locked() {
        var session = newSession(CardType.EGK);
        session.setLockOwner("other-caller");
        var tuc = new TucKon023ReserveCard();

        var ex = assertThrows(TucException.class, () -> tuc.generateLock(session, "caller-1"));

        assertEquals(4093, ex.getErrorCode());
    }

    @Test
    void test_TIP1_A_4571_03_generate_unlock_clears_lock_owner() {
        var session = newSession(CardType.EGK);
        session.setLockOwner("caller-1");
        var tuc = new TucKon023ReserveCard();

        tuc.generateUnlock(session, "caller-1");

        assertNull(session.lockOwner());
    }
}
