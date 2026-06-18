package de.servicehealtherx.cetp.filter;

import de.servicehealtherx.cetp.EventSeverity;
import de.servicehealtherx.cetp.EventType;
import de.servicehealtherx.cetp.KonnektorSystemEvent;
import de.servicehealtherx.cetp.delivery.EventXmlWriter;
import de.servicehealtherx.cetp.subscription.Subscription;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** XPath filter per TUC_KON_256 step 5c (error 4095 isolation). */
class XPathExpressionFilterTest {

    private final XPathExpressionFilter filter = newFilter();

    private static XPathExpressionFilter newFilter() {
        XPathExpressionFilter f = new XPathExpressionFilter();
        f.eventXmlWriter = new EventXmlWriter();
        return f;
    }

    private static KonnektorSystemEvent event() {
        return new KonnektorSystemEvent("CARD/INSERTED", EventType.Operation, EventSeverity.Info, Map.of());
    }

    private static Subscription withFilter(String xpath) {
        Subscription s = new Subscription();
        s.topic = "CARD";
        s.subscriptionId = UUID.randomUUID();
        s.filter = xpath;
        return s;
    }

    @Test
    void test_TIP1_A_4598_02_no_filter_keeps_subscription() {
        assertTrue(filter.keep(event(), withFilter(null)));
        assertTrue(filter.keep(event(), withFilter("   ")));
    }

    @Test
    void test_TIP1_A_4598_02_xpath_filter_matches_and_excludes() {
        assertTrue(filter.keep(event(), withFilter("//*[local-name()='Topic' and text()='CARD/INSERTED']")));
        assertFalse(filter.keep(event(), withFilter("//*[local-name()='Topic' and text()='CT/CONNECTED']")));
    }

    @Test
    void test_TIP1_A_4598_02_malformed_filter_excludes_only_this_subscription() {
        // Error 4095: a broken XPath drops only this subscription, never throws.
        assertFalse(filter.keep(event(), withFilter("///[[bad")));
    }
}
