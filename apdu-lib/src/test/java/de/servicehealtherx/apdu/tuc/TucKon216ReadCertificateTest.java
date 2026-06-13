package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon216ReadCertificateTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4585_generate_read_certificate_returns_select_then_read_binary_steps() {
        var tuc = new TucKon216ReadCertificate();

        var result = tuc.generateReadCertificate(newSession(CardType.EGK), (short) 0x2F02);

        assertEquals(2, result.steps().size());
    }

    @Test
    void test_TIP1_A_4585_first_step_select_ins_0xA4() {
        var tuc = new TucKon216ReadCertificate();

        var result = tuc.generateReadCertificate(newSession(CardType.EGK), (short) 0x2F02);

        assertEquals(0xA4, result.steps().get(0).command().getINS());
    }
}
