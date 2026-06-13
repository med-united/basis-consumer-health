package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TucKon036ProvideProfessionalRoleTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_TIP1_A_5478_generate_returns_versicherter_hint_for_kvk() {
        var tuc = new TucKon036ProvideProfessionalRole();

        var result = tuc.generateProvideProfessionalRole(newSession(CardType.KVK));

        var role = (List<String>) result.semanticOutputHints().get("professionalRole");
        assertNotNull(role);
        assertTrue(role.contains("Versicherter"));
    }

    @Test
    void test_TIP1_A_5478_generate_for_smcb_returns_read_c_aut_steps() {
        var tuc = new TucKon036ProvideProfessionalRole();

        var result = tuc.generateProvideProfessionalRole(newSession(CardType.SMC_B));

        assertFalse(result.steps().isEmpty());
    }

    @Test
    void test_TIP1_A_5478_hints_contain_profession_oid_parse_flag() {
        var tuc = new TucKon036ProvideProfessionalRole();

        var result = tuc.generateProvideProfessionalRole(newSession(CardType.SMC_B));

        assertEquals(Boolean.TRUE, result.semanticOutputHints().get("parseProfessionOids"));
    }
}
