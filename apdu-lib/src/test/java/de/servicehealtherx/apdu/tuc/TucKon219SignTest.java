package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon219SignTest {

    private static CardSession newSession(CardType type) {
        var session = new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
        session.authState().markPinVerified(PinRef.PIN_QES);
        return session;
    }

    @Test
    void test_TIP1_A_4581_generate_sign_returns_three_steps_mse_key_mse_alg_pso_cds() {
        var tuc = new TucKon219Sign();

        var result = tuc.generateSign(newSession(CardType.HBA), KeyRef.C_QES,
                AlgorithmId.ECDSA_SHA256, new byte[]{0x01, 0x02, 0x03});

        assertEquals(3, result.steps().size());
    }

    @Test
    void test_TIP1_A_4581_pso_cds_ins_is_0x2A_p1_0x9E_p2_0x9A() {
        var tuc = new TucKon219Sign();

        var result = tuc.generateSign(newSession(CardType.HBA), KeyRef.C_QES,
                AlgorithmId.ECDSA_SHA256, new byte[]{0x01, 0x02, 0x03});
        var psoCmd = result.steps().get(2).command();

        assertEquals(0x2A, psoCmd.getINS());
        assertEquals(0x9E, psoCmd.getP1());
        assertEquals(0x9A, psoCmd.getP2());
    }

    @Test
    void test_TIP1_A_4581_throws_when_pin_qes_not_in_verified_auth_state() {
        var session = new CardSession("handle-001", CardType.HBA, CardVersion.GENERATION_2_1, new AuthState());
        var tuc = new TucKon219Sign();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateSign(session, KeyRef.C_QES, AlgorithmId.ECDSA_SHA256, new byte[]{0x01}));

        assertEquals(4063, ex.getErrorCode());
    }
}
