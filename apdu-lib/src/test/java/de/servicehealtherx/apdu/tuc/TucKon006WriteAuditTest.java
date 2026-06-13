package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon006WriteAuditTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4580_generate_write_audit_delegates_to_append_record_steps() {
        var tuc = new TucKon006WriteAudit();

        var result = tuc.generateWriteAudit(newSession(CardType.EGK), new byte[]{0x01, 0x02, 0x03});

        assertFalse(result.steps().isEmpty());
        assertEquals(0xE2, result.steps().get(0).command().getINS());
    }

    @Test
    void test_TIP1_A_4580_generate_throws_for_non_egk_card() {
        var tuc = new TucKon006WriteAudit();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateWriteAudit(newSession(CardType.HBA), new byte[]{0x01}));

        assertEquals(4001, ex.getErrorCode());
    }
}
