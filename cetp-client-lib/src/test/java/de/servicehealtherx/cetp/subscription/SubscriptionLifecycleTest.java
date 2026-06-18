package de.servicehealtherx.cetp.subscription;

import de.servicehealtherx.cetp.TimeSource;
import de.servicehealtherx.cetp.subscription.SubscriptionService.Renewal;
import de.servicehealtherx.cetp.subscription.SubscriptionService.SubscribeResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unsubscribe (TIP1-A_4609) and RenewSubscriptions (TIP1-A_5112). */
@QuarkusTest
class SubscriptionLifecycleTest {

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
    void test_TIP1_A_4609_unsubscribe_removes_subscription() {
        service.subscribe("m1", "c1", "w1", "cetp://host:9200", "CARD", null);

        service.unsubscribe(null, "cetp://host:9200");

        assertEquals(0, Subscription.count());
        assertEquals(0, ClientEndpoint.count());
        assertTrue(service.getSubscriptions().isEmpty());
    }

    @Test
    void test_TIP1_A_5112_renew_extends_only_valid_subscriptions() {
        SubscribeResult valid = service.subscribe("m1", "c1", "w1", "cetp://host:9200", "CARD", null);
        // Advance the clock 1 h (still valid, < 25 h) and renew.
        timeSource.setClock(Clock.fixed(T0.plus(1, ChronoUnit.HOURS), ZoneOffset.UTC));

        List<Renewal> renewals = service.renew();

        assertEquals(1, renewals.size());
        assertEquals(valid.subscriptionId(), renewals.get(0).subscriptionId());
        assertEquals(T0.plus(26, ChronoUnit.HOURS), renewals.get(0).terminationTime());
    }
}
