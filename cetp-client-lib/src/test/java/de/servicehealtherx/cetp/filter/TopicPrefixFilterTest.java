package de.servicehealtherx.cetp.filter;

import de.servicehealtherx.cetp.EventSeverity;
import de.servicehealtherx.cetp.EventType;
import de.servicehealtherx.cetp.KonnektorSystemEvent;
import de.servicehealtherx.cetp.subscription.Subscription;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Topic prefix matching per TUC_KON_256 step 5a. */
class TopicPrefixFilterTest {

    private final TopicPrefixFilter filter = new TopicPrefixFilter();

    private static KonnektorSystemEvent event(String topic) {
        return new KonnektorSystemEvent(topic, EventType.Operation, EventSeverity.Info, Map.of());
    }

    private static Subscription subscribedTo(String topic) {
        Subscription s = new Subscription();
        s.topic = topic;
        return s;
    }

    @Test
    void test_TIP1_A_4598_02_topic_prefix_match_case_insensitive() {
        assertTrue(filter.keep(event("CARD/INSERTED"), subscribedTo("CARD")));
        assertTrue(filter.keep(event("card/inserted"), subscribedTo("CARD")));
        assertTrue(filter.keep(event("CARD/INSERTED"), subscribedTo("CARD/INSERTED")));
    }

    @Test
    void test_TIP1_A_4598_02_non_matching_topic_dropped() {
        assertFalse(filter.keep(event("CT/CONNECTED"), subscribedTo("CARD")));
        // Prefix must align on a segment boundary: "CARD" must not match "CARDX/..."
        assertFalse(filter.keep(event("CARDX/INSERTED"), subscribedTo("CARD")));
    }
}
