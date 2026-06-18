package de.servicehealtherx.cetp.delivery;

import de.servicehealtherx.cetp.EventSeverity;
import de.servicehealtherx.cetp.EventType;
import de.servicehealtherx.cetp.KonnektorSystemEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Event XML body per gemSpec_Kon TAB_KON_030 / TIP1-A_4596. */
class EventXmlWriterTest {

    private final EventXmlWriter writer = new EventXmlWriter();

    @Test
    void test_TIP1_A_4596_event_body_utf8_topic_type_severity_subscriptionId() {
        KonnektorSystemEvent event = new KonnektorSystemEvent(
                "CARD/INSERTED", EventType.Operation, EventSeverity.Info,
                Map.of("CtID", "CT1", "SlotID", "1"));

        String xml = new String(writer.toEventXml(event, "sub-123"), StandardCharsets.UTF_8);

        assertTrue(xml.contains("CARD/INSERTED"), xml);
        assertTrue(xml.contains("Operation"), xml);
        assertTrue(xml.contains("Info"), xml);
        assertTrue(xml.contains("sub-123"), xml);
        assertTrue(xml.contains("CtID"), xml);
        assertTrue(xml.contains("CT1"), xml);
        assertTrue(xml.toLowerCase().contains("utf-8"), xml);
    }

    @Test
    void test_FR_008_bootup_complete_uses_empty_subscription_id() {
        KonnektorSystemEvent event = new KonnektorSystemEvent(
                "BOOTUP/BOOTUP_COMPLETE", EventType.Operation, EventSeverity.Info, Map.of());

        String xml = new String(writer.toEventXml(event, ""), StandardCharsets.UTF_8);

        // Empty SubscriptionID element, either <SubscriptionID/> or <SubscriptionID></SubscriptionID>
        assertTrue(xml.contains("SubscriptionID/>") || xml.contains("SubscriptionID></"), xml);
    }
}
