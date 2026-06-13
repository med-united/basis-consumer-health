package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon208SendSecuredApduTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_A_26069_01_generate_secured_scenario_wraps_signed_scenario_bytes() {
        var signedBytes = new byte[]{0x00, (byte) 0x86, 0x00, 0x00, 0x01, 0x02};
        var tuc = new TucKon208SendSecuredApdu();

        var result = tuc.generateSendSecuredApdu(newSession(CardType.EGK), signedBytes, 1);

        assertEquals(1, result.steps().size());
        assertEquals(0x86, result.steps().get(0).command().getINS());
    }

    @Test
    void test_A_26069_01_generate_throws_on_replayed_sequence_number() {
        var signedBytes = new byte[]{0x00, (byte) 0x86, 0x00, 0x00, 0x01, 0x02};
        var tuc = new TucKon208SendSecuredApdu();

        assertThrows(TucException.class,
                () -> tuc.generateSendSecuredApdu(newSession(CardType.EGK), signedBytes, 0));
    }

    @Test
    void test_A_26069_01_next_sequence_number_in_hints() {
        var signedBytes = new byte[]{0x00, (byte) 0x86, 0x00, 0x00, 0x01, 0x02};
        var tuc = new TucKon208SendSecuredApdu();

        var result = tuc.generateSendSecuredApdu(newSession(CardType.EGK), signedBytes, 1);

        assertEquals(2, result.semanticOutputHints().get("nextSequenceNumber"));
    }
}
