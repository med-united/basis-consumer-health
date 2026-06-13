package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon210WriteRecordTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4576_2_generate_write_record_returns_update_record_step() {
        var tuc = new TucKon210WriteRecord();

        var result = tuc.generateWriteRecord(newSession(CardType.EGK), (byte) 0x01, 1, new byte[]{0x01});

        assertFalse(result.steps().isEmpty());
        assertEquals(0xDC, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_4576_2_generate_throws_for_kvk_card() {
        var tuc = new TucKon210WriteRecord();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateWriteRecord(newSession(CardType.KVK), (byte) 0x01, 1, new byte[]{0x01}));

        assertEquals(4001, ex.getErrorCode());
    }
}
