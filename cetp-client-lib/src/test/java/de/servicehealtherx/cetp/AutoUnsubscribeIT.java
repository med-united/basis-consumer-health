package de.servicehealtherx.cetp;

import de.servicehealtherx.cetp.subscription.ClientEndpoint;
import de.servicehealtherx.cetp.subscription.Subscription;
import de.servicehealtherx.cetp.subscription.SubscriptionService;
import de.servicehealtherx.cetp.testsupport.FakeEventSink;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auto-Unsubscribe on EVT_MAX_TRY consecutive failures (User Story 3, TIP1-A_4611). */
@QuarkusTest
class AutoUnsubscribeIT {

    /** An address that actively refuses connections, so each delivery attempt fails fast. */
    private static final String DEAD_SINK = "cetp://127.0.0.1:1";

    @Inject
    Event<KonnektorSystemEvent> bus;
    @Inject
    SubscriptionService service;

    @BeforeEach
    @Transactional
    void clean() {
        Subscription.deleteAll();
        ClientEndpoint.deleteAll();
    }

    private static KonnektorSystemEvent event() {
        return new KonnektorSystemEvent("CARD/INSERTED", EventType.Operation, EventSeverity.Info, Map.of());
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("condition not met within timeout");
    }

    @Test
    void test_TIP1_A_4611_auto_unsubscribe_after_evt_max_try_failures() {
        assertEquals(3, service.evtMaxTry(), "test config expects cetp.evt-max-try=3");
        service.subscribe("m1", "c1", "w1", DEAD_SINK, "CARD", null);

        bus.fireAsync(event());
        await(() -> service.failureCount(DEAD_SINK) >= 1);
        bus.fireAsync(event());
        await(() -> service.failureCount(DEAD_SINK) >= 2);
        bus.fireAsync(event());
        // After the 3rd consecutive failure the sink (and its subscription) is removed.
        await(() -> service.failureCount(DEAD_SINK) == -1);

        assertEquals(0, Subscription.count());
    }

    @Test
    void test_FR_021_success_resets_failure_counter() throws Exception {
        try (FakeEventSink sink = FakeEventSink.plain()) {
            service.subscribe("m1", "c1", "w1", sink.eventTo(), "CARD", null);
            // Pre-load 2 failures, then a successful delivery must reset the counter to 0.
            QuarkusTransaction.requiringNew().run(() -> {
                ClientEndpoint e = ClientEndpoint.findByEventTo(sink.eventTo());
                e.failureCount = 2;
            });

            bus.fireAsync(event());

            await(() -> service.failureCount(sink.eventTo()) == 0);
        }
    }

    @Test
    void test_FR_022_only_connection_failures_counted_when_sink_silent() throws Exception {
        // A sink that accepts connections but never answers at the application level must NOT accrue
        // failures: only connection-establishment failures count toward the threshold.
        try (FakeEventSink silentButReachable = FakeEventSink.plain()) {
            service.subscribe("m1", "c1", "w1", silentButReachable.eventTo(), "CARD", null);

            bus.fireAsync(event());
            await(() -> silentButReachable.connectionCount() >= 1);
            // Give the failure path a chance to (wrongly) fire, then assert it did not.
            Thread.sleep(300);

            assertTrue(service.failureCount(silentButReachable.eventTo()) <= 0,
                    "a reachable sink must not accrue failures");
        }
    }
}
