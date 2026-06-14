package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EgkSessionLock} (US1 task T019; FR-022/C1, FR-027, FR-028).
 */
class EgkSessionLockTest {

    @Test
    void test_FR_022_second_acquire_on_same_egk_is_rejected() {
        EgkSessionLock lock = new EgkSessionLock();
        Optional<String> first = lock.tryAcquire("HANDLE-1", "holderA");
        Optional<String> second = lock.tryAcquire("HANDLE-1", "holderB");

        assertTrue(first.isPresent(), "first acquire returns a sessionID");
        assertFalse(second.isPresent(), "second acquire on the same eGK is rejected (→ 4093)");
        assertTrue(lock.isLocked("HANDLE-1"));
        assertEquals("holderA", lock.lockHolder("HANDLE-1").orElseThrow());
    }

    @Test
    void test_FR_028_release_by_known_session_id_succeeds() {
        EgkSessionLock lock = new EgkSessionLock();
        String sessionId = lock.tryAcquire("HANDLE-2", "holder").orElseThrow();

        assertTrue(lock.release(sessionId));
        assertFalse(lock.isLocked("HANDLE-2"), "lock released; card available again");
    }

    @Test
    void test_FR_028_release_with_unknown_session_id_returns_false() {
        EgkSessionLock lock = new EgkSessionLock();
        lock.tryAcquire("HANDLE-3", "holder");

        assertFalse(lock.release("not-a-real-session-id"), "unknown sessionID → 4288");
        assertTrue(lock.isLocked("HANDLE-3"), "active session unaffected");
    }

    @Test
    void test_force_release_drops_lock_on_ejection() {
        EgkSessionLock lock = new EgkSessionLock();
        lock.tryAcquire("HANDLE-4", "holder");

        lock.forceRelease("HANDLE-4");

        assertFalse(lock.isLocked("HANDLE-4"));
    }
}
