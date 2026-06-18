package de.servicehealtherx.cetp.subscription;

import de.servicehealtherx.cetp.TimeSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bootup clears subscriptions but retains endpoint URLs for BOOTUP_COMPLETE (TIP1-A_4613, FR-008/FR-017). */
@QuarkusTest
class BootupClearTest {

    @Inject
    SubscriptionService service;
    @Inject
    TimeSource timeSource;

    private static final Instant T0 = Instant.parse("2026-06-17T08:00:00Z");

    @BeforeEach
    @Transactional
    void clean() {
        Subscription.deleteAll();
        ClientEndpoint.deleteAll();
        timeSource.setClock(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    void reset() {
        timeSource.setClock(Clock.systemUTC());
    }

    @Test
    void test_TIP1_A_4613_bootup_starts_with_empty_subscription_list() {
        service.subscribe("m1", "c1", "w1", "cetp://host:9200", "CARD", null);

        List<String> retained = service.clearOnBootup();

        assertEquals(0, Subscription.count(), "subscription list must be empty after bootup");
        // terminationTime (now+25h) is still in the future → endpoint URL is retained for BOOTUP_COMPLETE.
        assertTrue(retained.contains("cetp://host:9200"));
    }
}
