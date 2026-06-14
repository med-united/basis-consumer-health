package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;


/**
 * Unit tests for {@link ComfortSignatureService} (Phase 7 task T044; FR-029–FR-032).
 */
class ComfortSignatureServiceTest {

    private static HbaxCardSession verifiedHbax() {
        HbaxCardSession s = new HbaxCardSession("M1", "CS1", "user1");
        s.authState().add(AuthEntry.chv(ComfortSignatureService.PIN_QES));
        return s;
    }

    @Test
    void test_FR_029_activate_sets_comfort_mode_and_count() {
        ComfortSignatureService svc = new ComfortSignatureService(5);
        HbaxCardSession session = verifiedHbax();

        svc.activate(session, true, 100);

        assertEquals(HbaxCardSession.SIGN_MODE_COMFORT, session.signMode());
        assertEquals(100, session.countRemaining().get());
        assertEquals(1, svc.activeSessionCount());
    }

    @Test
    void test_FR_032_count_is_capped_at_hardware_limit_250() {
        ComfortSignatureService svc = new ComfortSignatureService(5);
        HbaxCardSession session = verifiedHbax();

        svc.activate(session, true, 1000);

        assertEquals(ComfortSignatureService.HBA_COMFORT_HARD_CAP, session.countRemaining().get());
    }

    @Test
    void test_FR_029_activate_fails_when_feature_disabled_or_pin_not_verified() {
        ComfortSignatureService svc = new ComfortSignatureService(5);

        HbaxCardSession verified = verifiedHbax();
        assertThrows(IllegalStateException.class, () -> svc.activate(verified, false, 100));

        HbaxCardSession unverified = new HbaxCardSession("M1", "CS1", "user1");
        assertThrows(IllegalStateException.class, () -> svc.activate(unverified, true, 100));
    }

    @Test
    void test_FR_029_4278_when_max_concurrent_sessions_reached() {
        ComfortSignatureService svc = new ComfortSignatureService(1);
        svc.activate(verifiedHbax(), true, 10); // fills the single slot

        CardServiceException ex = assertThrows(CardServiceException.class,
                () -> svc.activate(verifiedHbax(), true, 10));
        assertEquals(4278, ex.getErrorCode());
    }

    @Test
    void test_FR_030_deactivate_resets_mode_and_removes_pin_qes() {
        ComfortSignatureService svc = new ComfortSignatureService(5);
        HbaxCardSession session = verifiedHbax();
        svc.activate(session, true, 10);

        svc.deactivate(session);

        assertEquals(HbaxCardSession.SIGN_MODE_PIN, session.signMode());
        assertTrue(session.authState().isEmpty(), "PIN.QES removed from authState");
        assertEquals(0, svc.activeSessionCount());
    }

    @Test
    void test_FR_031_count_decrements_and_auto_deactivates_at_zero() {
        ComfortSignatureService svc = new ComfortSignatureService(5);
        HbaxCardSession session = verifiedHbax();
        svc.activate(session, true, 2);

        assertEquals(1, svc.recordSignature(session));
        assertEquals(0, svc.recordSignature(session)); // hits zero → auto-deactivate
        assertEquals(HbaxCardSession.SIGN_MODE_PIN, session.signMode());
        assertFalse(HbaxCardSession.SIGN_MODE_COMFORT.equals(session.signMode()));
    }
}
