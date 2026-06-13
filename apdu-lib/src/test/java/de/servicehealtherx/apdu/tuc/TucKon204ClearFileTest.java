package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon204ClearFileTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4576_1_generate_clear_file_returns_erase_binary_step_ins_0x0E() {
        var tuc = new TucKon204ClearFile();

        var result = tuc.generateClearFile(newSession(CardType.EGK), (short) 0x2F02);

        assertFalse(result.steps().isEmpty());
        assertEquals(0x0E, result.steps().get(result.steps().size() - 1).command().getINS());
    }

    @Test
    void test_TIP1_A_4576_1_generate_throws_for_kvk_card() {
        var tuc = new TucKon204ClearFile();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateClearFile(newSession(CardType.KVK), (short) 0x2F02));

        assertEquals(4001, ex.getErrorCode());
    }
}
