package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon019ChangePinTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4568_generate_change_pin_returns_change_reference_data_apdu() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon019ChangePin();

        var result = tuc.generateChangePin(session, PinRef.PIN_CH);

        assertFalse(result.steps().isEmpty());
    }

    @Test
    void test_TIP1_A_4568_change_reference_data_ins_is_0x24() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon019ChangePin();

        var result = tuc.generateChangePin(session, PinRef.PIN_CH);

        assertEquals(0x24, result.steps().get(0).command().getINS());
    }
}
