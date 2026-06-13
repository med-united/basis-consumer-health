package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon211ClearRecordTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4577_1_generate_clear_record_returns_erase_record_step_ins_0x0C() {
        var tuc = new TucKon211ClearRecord();

        var result = tuc.generateClearRecord(newSession(CardType.EGK), (byte) 0x01, 1);

        assertFalse(result.steps().isEmpty());
        assertEquals(0x0C, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_4577_1_generate_throws_for_kvk_card() {
        var tuc = new TucKon211ClearRecord();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateClearRecord(newSession(CardType.KVK), (byte) 0x01, 1));

        assertEquals(4001, ex.getErrorCode());
    }
}
