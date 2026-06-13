package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TucKon005CardToCardAuthTest {

    private static CardSession newSession(CardType type) {
        return new CardSession("handle-001", type, CardVersion.GENERATION_2_1, new AuthState());
    }

    @Test
    void test_TIP1_A_4572_generate_one_sided_auth_returns_get_challenge_internal_auth_external_auth_steps() {
        var source = newSession(CardType.HBA);
        var target = newSession(CardType.EGK);
        var tuc = new TucKon005CardToCardAuth();

        var result = tuc.generateCardToCardAuth(source, target, KeyRef.C_AUT, AuthMode.ONE_SIDED);

        assertTrue(result.steps().size() >= 3);
    }

    @Test
    void test_TIP1_A_4572_generate_throws_when_source_card_foreign_locked() {
        var source = newSession(CardType.HBA);
        source.setLockOwner("other-caller");
        var target = newSession(CardType.EGK);
        var tuc = new TucKon005CardToCardAuth();

        var ex = assertThrows(TucException.class,
                () -> tuc.generateCardToCardAuth(source, target, KeyRef.C_AUT, AuthMode.ONE_SIDED));

        assertEquals(4093, ex.getErrorCode());
    }

    @Test
    void test_TIP1_A_4572_get_challenge_ins_is_0x84() {
        var source = newSession(CardType.HBA);
        var target = newSession(CardType.EGK);
        var tuc = new TucKon005CardToCardAuth();

        var result = tuc.generateCardToCardAuth(source, target, KeyRef.C_AUT, AuthMode.ONE_SIDED);

        var getChallengeStep = result.steps().stream()
                .filter(s -> s.semanticLabel().contains("GET CHALLENGE"))
                .findFirst();
        assertTrue(getChallengeStep.isPresent());
        assertEquals(0x84, getChallengeStep.get().command().getINS());
    }

    @Test
    void test_TIP1_A_4572_internal_auth_ins_is_0x88() {
        var source = newSession(CardType.HBA);
        var target = newSession(CardType.EGK);
        var tuc = new TucKon005CardToCardAuth();

        var result = tuc.generateCardToCardAuth(source, target, KeyRef.C_AUT, AuthMode.ONE_SIDED);

        var step = result.steps().stream()
                .filter(s -> s.semanticLabel().contains("INTERNAL AUTHENTICATE"))
                .findFirst();
        assertTrue(step.isPresent());
        assertEquals(0x88, step.get().command().getINS());
    }

    @Test
    void test_TIP1_A_4572_external_auth_ins_is_0x82() {
        var source = newSession(CardType.HBA);
        var target = newSession(CardType.EGK);
        var tuc = new TucKon005CardToCardAuth();

        var result = tuc.generateCardToCardAuth(source, target, KeyRef.C_AUT, AuthMode.ONE_SIDED);

        var step = result.steps().stream()
                .filter(s -> s.semanticLabel().contains("EXTERNAL AUTHENTICATE"))
                .findFirst();
        assertTrue(step.isPresent());
        assertEquals(0x82, step.get().command().getINS());
    }
}
