package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon209ReadRecordTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4575_generate_read_record_returns_read_record_step_ins_0xB2() {
        var tuc = new TucKon209ReadRecord();

        var result = tuc.generateReadRecord(newSession(CardType.EGK), (byte) 0x01, 1, 32);

        assertFalse(result.steps().isEmpty());
        assertEquals(0xB2, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_4575_p1_is_record_number() {
        int recordNumber = 3;
        var tuc = new TucKon209ReadRecord();

        var result = tuc.generateReadRecord(newSession(CardType.EGK), (byte) 0x01, recordNumber, 32);

        assertEquals(recordNumber, result.steps().get(0).command().getP1());
    }
}
