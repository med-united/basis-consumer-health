package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon012VerifyPinTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4566_generate_verify_pin_returns_verify_apdu_step() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon012VerifyPin();

        var result = tuc.generateVerifyPin(session, PinRef.PIN_CH, "caller-1");

        assertFalse(result.steps().isEmpty());
    }

    @Test
    void test_TIP1_A_4566_verify_apdu_ins_is_0x20_and_p2_is_pin_ref_byte() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon012VerifyPin();

        var result = tuc.generateVerifyPin(session, PinRef.PIN_CH, "caller-1");
        var cmd = result.steps().get(0).command();

        assertEquals(0x20, cmd.getINS());
        assertEquals(PinRef.PIN_CH.reference(), (byte) cmd.getP2());
    }

    @Test
    void test_TIP1_A_4566_generate_throws_4063_when_pin_is_blocked() {
        var session = newSession(CardType.EGK);
        session.authState().markPinStatus(PinRef.PIN_CH, PinStatus.BLOCKED);
        var tuc = new TucKon012VerifyPin();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateVerifyPin(session, PinRef.PIN_CH, "caller-1"));

        assertEquals(4063, ex.getErrorCode());
    }

    @Test
    void test_TIP1_A_4566_generate_throws_4093_when_card_is_reserved() {
        var session = newSession(CardType.EGK);
        session.setLockOwner("other-caller");
        var tuc = new TucKon012VerifyPin();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateVerifyPin(session, PinRef.PIN_CH, "caller-1"));

        assertEquals(4093, ex.getErrorCode());
    }

    @Test
    void test_TIP1_A_4566_hints_contain_pin_ref_for_auth_state_update() {
        var session = newSession(CardType.EGK);
        var tuc = new TucKon012VerifyPin();

        var result = tuc.generateVerifyPin(session, PinRef.PIN_CH, "caller-1");

        assertEquals(PinRef.PIN_CH, result.semanticOutputHints().get("pinRef"));
    }
}
