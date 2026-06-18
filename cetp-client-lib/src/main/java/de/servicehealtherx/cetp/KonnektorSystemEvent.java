package de.servicehealtherx.cetp;

import java.util.Map;

/**
 * Transport-neutral konnektor system event, the input of TUC_KON_256
 * ("Systemereignis absetzen", gemSpec_Kon §4.1.6.4.1). Basis-services fire it
 * asynchronously ({@code Event<KonnektorSystemEvent>.fireAsync(...)}); {@link CetpClient}
 * observes it with {@code @ObservesAsync} and delivers it to subscribed client systems.
 *
 * @param topic      event topic, a "/"-separated tree path (e.g. {@code CARD/INSERTED})
 * @param eventType  Operation / Security / Infrastructure
 * @param severity   Info / Warning / Error / Fatal
 * @param parameters additional key/value parameters (case-sensitive keys)
 */
public record KonnektorSystemEvent(
        String topic,
        EventType eventType,
        EventSeverity severity,
        Map<String, String> parameters) {

    public KonnektorSystemEvent {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
