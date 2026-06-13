package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon220DecryptTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4582_generate_decrypt_returns_mse_set_then_pso_decipher() {
        var tuc = new TucKon220Decrypt();

        var result = tuc.generateDecrypt(newSession(CardType.EGK), KeyRef.C_ENC,
                AlgorithmId.RSA_OAEP_SHA256, new byte[]{0x01, 0x02, 0x03});

        assertEquals(2, result.steps().size());
    }

    @Test
    void test_TIP1_A_4582_pso_decipher_ins_is_0x2A_p1_0x80_p2_0x86() {
        var tuc = new TucKon220Decrypt();

        var result = tuc.generateDecrypt(newSession(CardType.EGK), KeyRef.C_ENC,
                AlgorithmId.RSA_OAEP_SHA256, new byte[]{0x01, 0x02, 0x03});
        var psoCmd = result.steps().get(1).command();

        assertEquals(0x2A, psoCmd.getINS());
        assertEquals(0x80, psoCmd.getP1());
        assertEquals(0x86, psoCmd.getP2());
    }

    @Test
    void test_TIP1_A_4582_throws_for_kvk_card() {
        var tuc = new TucKon220Decrypt();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateDecrypt(newSession(CardType.KVK), KeyRef.C_ENC,
                        AlgorithmId.RSA_OAEP_SHA256, new byte[]{0x01}));

        assertEquals(4001, ex.getErrorCode());
    }
}
