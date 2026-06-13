package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon027EnableDisablePinTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_5486_generate_enable_returns_enable_verification_apdu_0x28() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon027EnableDisablePin();

        var result = tuc.generateEnableDisablePin(session, PinRef.PIN_CH, true);

        assertEquals(0x28, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_5486_generate_disable_returns_disable_verification_apdu_0x26() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon027EnableDisablePin();

        var result = tuc.generateEnableDisablePin(session, PinRef.PIN_CH, false);

        assertEquals(0x26, result.steps().get(0).command().getINS());
    }
}
