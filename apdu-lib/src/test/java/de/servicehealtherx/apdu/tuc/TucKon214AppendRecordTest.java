package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon214AppendRecordTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4577_2_generate_append_record_returns_append_record_step_ins_0xE2() {
        var tuc = new TucKon214AppendRecord();

        var result = tuc.generateAppendRecord(newSession(CardType.EGK), (byte) 0x01, new byte[]{0x01, 0x02});

        assertFalse(result.steps().isEmpty());
        assertEquals(0xE2, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_4577_2_generate_throws_for_kvk_card() {
        var tuc = new TucKon214AppendRecord();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateAppendRecord(newSession(CardType.KVK), (byte) 0x01, new byte[]{0x01}));

        assertEquals(4001, ex.getErrorCode());
    }
}
