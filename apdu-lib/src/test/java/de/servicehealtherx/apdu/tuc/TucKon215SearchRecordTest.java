package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon215SearchRecordTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4578_generate_search_record_returns_search_record_step_ins_0xA2() {
        var tuc = new TucKon215SearchRecord();

        var result = tuc.generateSearchRecord(newSession(CardType.EGK), (byte) 0x01, new byte[]{0x42, 0x00});

        assertFalse(result.steps().isEmpty());
        assertEquals(0xA2, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_4578_search_pattern_in_command_data() {
        var pattern = new byte[]{0x42, 0x00, 0xFF};
        var tuc = new TucKon215SearchRecord();

        var result = tuc.generateSearchRecord(newSession(CardType.EGK), (byte) 0x01, pattern);

        assertArrayEquals(pattern, result.steps().get(0).command().getData());
    }
}
