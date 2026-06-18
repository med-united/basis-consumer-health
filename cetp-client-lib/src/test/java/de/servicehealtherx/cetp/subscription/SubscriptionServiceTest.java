package de.servicehealtherx.cetp.subscription;

import de.servicehealtherx.cetp.TimeSource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Subscribe persistence + dedup (gemSpec_Kon TIP1-A_4608, FR-012). */
@QuarkusTest
class SubscriptionServiceTest {

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
    void test_TIP1_A_4608_subscribe_persists_with_terminationTime_now_plus_25h() {
        SubscribeResult result = service.subscribe("m1", "c1", "w1", "cetp://host:9200", "CARD", null);

        assertEquals(T0.plus(25, ChronoUnit.HOURS), result.terminationTime());
        assertEquals(1, Subscription.count());
        assertEquals(1, ClientEndpoint.count());
    }

    @Test
    void test_FR_012_duplicate_subscribe_returns_existing_id() {
        SubscribeResult first = service.subscribe("m1", "c1", "w1", "cetp://host:9200", "CARD", null);
        SubscribeResult second = service.subscribe("m1", "c1", "w1", "cetp://host:9200", "CARD", "//EVT:Event");

        assertEquals(first.subscriptionId(), second.subscriptionId(), "same key must reuse the subscription");
        assertEquals(1, Subscription.count());
    }

    @Test
    void test_FR_012_different_context_creates_distinct_subscription() {
        SubscribeResult a = service.subscribe("m1", "c1", "w1", "cetp://host:9200", "CARD", null);
        SubscribeResult b = service.subscribe("m2", "c1", "w1", "cetp://host:9200", "CARD", null);

        assertNotEquals(a.subscriptionId(), b.subscriptionId());
        assertEquals(2, Subscription.count());
        assertEquals(1, ClientEndpoint.count(), "same sink shared by both subscriptions");
    }
}
