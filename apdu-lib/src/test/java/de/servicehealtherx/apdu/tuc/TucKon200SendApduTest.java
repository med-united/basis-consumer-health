package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon200SendApduTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4583_02_generate_wraps_raw_bytes_as_command_apdu_step() {
        var rawBytes = new byte[]{0x00, (byte) 0xA4, 0x04, 0x00};
        var tuc = new TucKon200SendApdu();

        var result = tuc.generateSendApdu(newSession(CardType.EGK), rawBytes);

        assertEquals(1, result.steps().size());
        assertEquals(0xA4, result.steps().get(0).command().getINS());
    }
}
