package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon021UnblockPinTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4569_02_generate_unblock_pin_returns_reset_retry_counter_apdu() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon021UnblockPin();

        var result = tuc.generateUnblockPin(session, PinRef.PIN_CH);

        assertFalse(result.steps().isEmpty());
    }

    @Test
    void test_TIP1_A_4569_02_reset_retry_counter_ins_is_0x2C() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon021UnblockPin();

        var result = tuc.generateUnblockPin(session, PinRef.PIN_CH);

        assertEquals(0x2C, result.steps().get(0).command().getINS());
    }
}
