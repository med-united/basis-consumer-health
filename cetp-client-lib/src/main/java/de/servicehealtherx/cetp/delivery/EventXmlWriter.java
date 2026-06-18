package de.servicehealtherx.cetp.delivery;

import de.gematik.ws.conn.eventservice.v7.Event;
import de.gematik.ws.conn.eventservice.v7.EventSeverityType;
import de.gematik.ws.conn.eventservice.v7.EventType;
import de.servicehealtherx.cetp.EventSeverity;
import de.servicehealtherx.cetp.KonnektorSystemEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Serialises a {@link KonnektorSystemEvent} to the UTF-8 XML {@code Event} document of
 * EventService.xsd v7.2 (gemSpec_Kon TAB_KON_030), using the generated JAXB types from
 * {@code api-telematik}.
 */
@ApplicationScoped
public class EventXmlWriter {

    private final JAXBContext jaxbContext;

    public EventXmlWriter() {
        try {
            this.jaxbContext = JAXBContext.newInstance(Event.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot create JAXBContext for Event", e);
        }
    }

    /**
     * @param event          the konnektor system event
     * @param subscriptionId the matched subscription's ID, or {@code null}/empty for BOOTUP_COMPLETE
     *                       (FR-008 — SubscriptionID is then an empty element)
     * @return UTF-8 XML {@code Event} document
     */
    public byte[] toEventXml(KonnektorSystemEvent event, String subscriptionId) {
        Event xml = new Event();
        xml.setTopic(event.topic());
        xml.setType(toType(event.eventType()));
        xml.setSeverity(toSeverity(event.severity()));
        xml.setSubscriptionID(subscriptionId == null ? "" : subscriptionId);

        Event.Message message = new Event.Message();
        for (Map.Entry<String, String> e : event.parameters().entrySet()) {
            Event.Message.Parameter p = new Event.Message.Parameter();
            p.setKey(e.getKey());
            p.setValue(e.getValue());
            message.getParameter().add(p);
        }
        xml.setMessage(message);

        try {
            Marshaller marshaller = jaxbContext.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            marshaller.marshal(xml, out);
            return out.toByteArray();
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot marshal Event for topic " + event.topic(), e);
        }
    }

    private static EventType toType(de.servicehealtherx.cetp.EventType type) {
        return switch (type) {
            case Operation -> EventType.OPERATION;
            case Security -> EventType.SECURITY;
            case Infrastructure -> EventType.INFRASTRUCTURE;
        };
    }

    private static EventSeverityType toSeverity(EventSeverity severity) {
        return switch (severity) {
            case Info -> EventSeverityType.INFO;
            case Warning -> EventSeverityType.WARNING;
            case Error -> EventSeverityType.ERROR;
            case Fatal -> EventSeverityType.FATAL;
        };
    }
}
