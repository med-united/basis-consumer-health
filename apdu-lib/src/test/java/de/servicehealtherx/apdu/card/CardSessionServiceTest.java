package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.model.CardType;

/**
 * Unit tests for {@link CardSessionService} (Phase 7 tasks T042/T043; FR-021–FR-028).
 */
class CardSessionServiceTest {

    private CardSessionService service() {
        return new CardSessionService(new EgkSessionLock());
    }

    private static CardObject card(CardType type) {
        return CardObject.builder().ctid(UUID.randomUUID()).slotNo(1).type(type)
                .insertTime(Instant.now()).build();
    }

    @Test
    void test_FR_022_egk_has_at_most_one_session_per_card() {
        CardSessionService svc = service();
        CardObject egk = card(CardType.EGK);

        CardSessionContext a = svc.getOrCreateSession(egk, CardType.EGK, "M1", "C1", null);
        CardSessionContext b = svc.getOrCreateSession(egk, CardType.EGK, "M1", "C1", null);

        assertSame(a, b, "one eGK session per card (C1)");
        assertEquals(1, egk.cardSessionList().size());
    }

    @Test
    void test_FR_024_smb_session_keyed_by_card_plus_mandant() {
        CardSessionService svc = service();
        CardObject smb = card(CardType.SMC_B);

        CardSessionContext m1 = svc.getOrCreateSession(smb, CardType.SMC_B, "M1", "C1", null);
        CardSessionContext m1again = svc.getOrCreateSession(smb, CardType.SMC_B, "M1", "C9", "u");
        CardSessionContext m2 = svc.getOrCreateSession(smb, CardType.SMC_B, "M2", "C1", null);

        assertSame(m1, m1again, "same mandant → same SM-B session (C3)");
        assertNotSame(m1, m2, "different mandant → distinct session");
        assertEquals(2, smb.cardSessionList().size());
    }

    @Test
    void test_FR_023_hbax_session_keyed_by_card_csid_user() {
        CardSessionService svc = service();
        CardObject hba = card(CardType.HBA);

        CardSessionContext s1 = svc.getOrCreateSession(hba, CardType.HBA, "M1", "CS1", "user1");
        CardSessionContext s1again = svc.getOrCreateSession(hba, CardType.HBA, "M1", "CS1", "user1");
        CardSessionContext s2 = svc.getOrCreateSession(hba, CardType.HBA, "M1", "CS1", "user2");

        assertSame(s1, s1again, "same csid+user → same HBAx session (C2)");
        assertNotSame(s1, s2, "different user → distinct session");
    }

    @Test
    void test_FR_022_second_start_returns_empty_for_4093() {
        CardSessionService svc = service();
        CardObject egk = card(CardType.EGK);

        Optional<String> first = svc.startEgkSession(egk, "holderA");
        Optional<String> second = svc.startEgkSession(egk, "holderB");

        assertTrue(first.isPresent());
        assertFalse(second.isPresent(), "second concurrent start → 4093");
        EgkCardSession session = (EgkCardSession) egk.cardSessionList().get(0);
        assertEquals(first.get(), session.sessionId());
    }

    @Test
    void test_FR_028_stop_with_known_then_unknown_session_id() {
        CardSessionService svc = service();
        CardObject egk = card(CardType.EGK);
        String sessionId = svc.startEgkSession(egk, "holder").orElseThrow();

        assertTrue(svc.stopEgkSession(sessionId), "known sessionID stops");
        assertFalse(svc.stopEgkSession("unknown"), "unknown sessionID → 4288");
        // After stop, the lock is free again → a new start succeeds.
        assertTrue(svc.startEgkSession(egk, "holder").isPresent());
    }
}
