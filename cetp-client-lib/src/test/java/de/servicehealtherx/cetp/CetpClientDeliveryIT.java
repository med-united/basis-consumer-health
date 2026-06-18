package de.servicehealtherx.cetp;

import de.servicehealtherx.cetp.subscription.ClientEndpoint;
import de.servicehealtherx.cetp.subscription.Subscription;
import de.servicehealtherx.cetp.subscription.SubscriptionService;
import de.servicehealtherx.cetp.testsupport.FakeEventSink;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end delivery (User Story 1) over plain TCP against an in-process {@link FakeEventSink}. */
@QuarkusTest
class CetpClientDeliveryIT {

    @Inject
    Event<KonnektorSystemEvent> bus;
    @Inject
    SubscriptionService subscriptionService;
    @Inject
    TimeSource timeSource;

    @BeforeEach
    @Transactional
    void clean() {
        Subscription.deleteAll();
        ClientEndpoint.deleteAll();
        timeSource.setClock(Clock.systemUTC());
    }

    @AfterEach
    void resetClock() {
        timeSource.setClock(Clock.systemUTC());
    }

    private static KonnektorSystemEvent cardInserted() {
        return new KonnektorSystemEvent("CARD/INSERTED", EventType.Operation, EventSeverity.Info,
                Map.of("CtID", "CT1"));
    }

    @Test
    void test_SC_001_matching_event_delivered() throws Exception {
        try (FakeEventSink sink = FakeEventSink.plain()) {
            subscriptionService.subscribe("m1", "c1", "w1", sink.eventTo(), "CARD", null);

            bus.fireAsync(cardInserted());

            byte[] frame = sink.awaitFrame(5, TimeUnit.SECONDS);
            assertNotNull(frame, "expected a CETP frame to be delivered");
            String s = new String(frame, StandardCharsets.UTF_8);
            assertTrue(s.startsWith("CETP"), "frame must start with CETP magic");
            assertTrue(s.contains("CARD/INSERTED"), s);
        }
    }

    @Test
    void test_SC_002_non_matching_topic_not_delivered() throws Exception {
        try (FakeEventSink sink = FakeEventSink.plain()) {
            subscriptionService.subscribe("m1", "c1", "w1", sink.eventTo(), "CARD/INSERTED", null);

            bus.fireAsync(new KonnektorSystemEvent("CT/CONNECTED", EventType.Operation, EventSeverity.Info, Map.of()));

            assertNull(sink.awaitFrame(2, TimeUnit.SECONDS), "non-matching topic must not be delivered");
        }
    }

    @Test
    void test_FR_002_expired_subscription_excluded() throws Exception {
        try (FakeEventSink sink = FakeEventSink.plain()) {
            // Subscribe at a fixed instant, then advance the clock beyond terminationTime (now + 25h).
            Clock t0 = Clock.fixed(java.time.Instant.parse("2026-06-17T00:00:00Z"), ZoneOffset.UTC);
            timeSource.setClock(t0);
            subscriptionService.subscribe("m1", "c1", "w1", sink.eventTo(), "CARD", null);
            timeSource.setClock(Clock.fixed(t0.instant().plus(Duration.ofHours(26)), ZoneOffset.UTC));

            bus.fireAsync(cardInserted());

            assertNull(sink.awaitFrame(2, TimeUnit.SECONDS), "expired subscription must be excluded");
        }
    }

    @Test
    void test_FR_008_bootup_complete_delivered_to_retained_endpoint_with_empty_subscription_id() throws Exception {
        try (FakeEventSink sink = FakeEventSink.plain()) {
            // A retained endpoint exists (its subscription was cleared at bootup, but the URL is kept).
            subscriptionService.subscribe("m1", "c1", "w1", sink.eventTo(), "CARD", null);
            subscriptionService.clearOnBootup();

            bus.fireAsync(new KonnektorSystemEvent("BOOTUP/BOOTUP_COMPLETE", EventType.Operation,
                    EventSeverity.Info, Map.of()));

            byte[] frame = sink.awaitFrame(5, TimeUnit.SECONDS);
            assertNotNull(frame, "retained endpoint must receive BOOTUP_COMPLETE");
            String s = new String(frame, StandardCharsets.UTF_8);
            assertTrue(s.contains("BOOTUP/BOOTUP_COMPLETE"), s);
            assertTrue(s.contains("SubscriptionID/>") || s.contains("SubscriptionID></"), s);
        }
    }

    @Test
    void test_SC_008_one_unreachable_sink_does_not_block_others() throws Exception {
        try (FakeEventSink live = FakeEventSink.plain()) {
            // A sink at a port nobody listens on — its delivery will hang until the 2 s timeout.
            subscriptionService.subscribe("m1", "c1", "w1", "cetp://127.0.0.1:1", "CARD", null);
            subscriptionService.subscribe("m2", "c2", "w2", live.eventTo(), "CARD", null);

            bus.fireAsync(cardInserted());

            // The live sink must receive well within the 2 s dead-sink timeout (proves fan-out is not serial).
            byte[] frame = live.awaitFrame(1500, TimeUnit.MILLISECONDS);
            assertNotNull(frame, "live sink must receive promptly despite an unreachable peer");
        }
    }
}
