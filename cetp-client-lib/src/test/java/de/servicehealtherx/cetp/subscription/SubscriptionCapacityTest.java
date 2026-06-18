package de.servicehealtherx.cetp.subscription;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Capacity: at least 999 concurrent subscriptions (TIP1-A_4612, FR-018). */
@QuarkusTest
class SubscriptionCapacityTest {

    @Inject
    SubscriptionService service;

    @BeforeEach
    @Transactional
    void clean() {
        Subscription.deleteAll();
        ClientEndpoint.deleteAll();
    }

    @Test
    void test_TIP1_A_4612_supports_999_subscriptions() {
        for (int i = 0; i < 999; i++) {
            service.subscribe("m" + i, "c1", "w1", "cetp://host:" + (10000 + i), "CARD", null);
        }
        assertEquals(999, Subscription.count());
    }
}
