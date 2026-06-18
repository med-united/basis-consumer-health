package de.servicehealtherx.cetp.filter;

import de.servicehealtherx.cetp.EventSeverity;
import de.servicehealtherx.cetp.EventType;
import de.servicehealtherx.cetp.KonnektorSystemEvent;
import de.servicehealtherx.cetp.subscription.Subscription;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Access-authorisation filter per TUC_KON_256 step 5b (TUC_KON_000 seam). */
class AccessAuthorizationFilterTest {

    private static KonnektorSystemEvent event() {
        return new KonnektorSystemEvent("CARD/INSERTED", EventType.Operation, EventSeverity.Info, Map.of());
    }

    private static Subscription subscription() {
        Subscription s = new Subscription();
        s.topic = "CARD";
        s.mandantId = "m1";
        s.clientSystemId = "c1";
        s.workplaceId = "w1";
        return s;
    }

    @Test
    void test_TIP1_A_4598_02_access_authorization_filter_keeps_when_authorized() {
        AccessAuthorizationFilter filter = new AccessAuthorizationFilter();
        filter.accessAuthorization = new AccessAuthorization(); // permissive default
        assertTrue(filter.keep(event(), subscription()));
    }

    @Test
    void test_TIP1_A_4598_02_access_authorization_filter_drops_when_denied() {
        AccessAuthorizationFilter filter = new AccessAuthorizationFilter();
        filter.accessAuthorization = new AccessAuthorization() {
            @Override
            public boolean isAuthorized(String m, String c, String w, Map<String, String> p) {
                return false;
            }
        };
        assertFalse(filter.keep(event(), subscription()));
    }
}
