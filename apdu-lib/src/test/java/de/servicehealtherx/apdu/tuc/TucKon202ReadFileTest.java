package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon202ReadFileTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4573_generate_read_file_returns_two_steps_select_then_read_binary() {
        var tuc = new TucKon202ReadFile();

        var result = tuc.generateReadFile(newSession(CardType.EGK), (short) 0x2F02, 0, 32);

        assertEquals(2, result.steps().size());
    }

    @Test
    void test_TIP1_A_4573_first_step_is_select_ins_0xA4() {
        var tuc = new TucKon202ReadFile();

        var result = tuc.generateReadFile(newSession(CardType.EGK), (short) 0x2F02, 0, 32);

        assertEquals(0xA4, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_4573_second_step_is_read_binary_ins_0xB0_with_offset_in_p1_p2() {
        int offset = 0x0100;
        var tuc = new TucKon202ReadFile();

        var result = tuc.generateReadFile(newSession(CardType.EGK), (short) 0x2F02, offset, 32);
        var cmd = result.steps().get(1).command();

        assertEquals(0xB0, cmd.getINS());
        assertEquals(offset >> 8, cmd.getP1());
        assertEquals(offset & 0xFF, cmd.getP2());
    }
}
