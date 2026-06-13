package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon203WriteFileTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4574_generate_write_file_returns_update_binary_step() {
        var tuc = new TucKon203WriteFile();

        var result = tuc.generateWriteFile(newSession(CardType.EGK), (short) 0x2F02, 0, new byte[]{0x01, 0x02});

        assertFalse(result.steps().isEmpty());
        assertEquals(0xD6, result.steps().get(result.steps().size() - 1).command().getINS());
    }

    @Test
    void test_TIP1_A_4574_generate_throws_for_kvk_card() {
        var tuc = new TucKon203WriteFile();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateWriteFile(newSession(CardType.KVK), (short) 0x2F02, 0, new byte[]{0x01}));

        assertEquals(4001, ex.getErrorCode());
    }
}
