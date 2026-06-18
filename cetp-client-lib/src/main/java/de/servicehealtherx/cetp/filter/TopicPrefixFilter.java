package de.servicehealtherx.cetp.filter;

import de.servicehealtherx.cetp.KonnektorSystemEvent;
import de.servicehealtherx.cetp.subscription.Subscription;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;

/**
 * Topic filter (TUC_KON_256 step 5a): keep the subscription iff the event topic equals or begins
 * with the subscribed topic, compared case-insensitively. Matching is on whole "/"-separated
 * segments so that {@code CARD} matches {@code CARD/INSERTED} but not {@code CARDX/...}.
 */
@ApplicationScoped
public class TopicPrefixFilter implements EventDeliveryFilter {

    @Override
    public boolean keep(KonnektorSystemEvent event, Subscription subscription) {
        String eventTopic = event.topic().toLowerCase(Locale.ROOT);
        String subTopic = subscription.topic.toLowerCase(Locale.ROOT);
        if (eventTopic.equals(subTopic)) {
            return true;
        }
        return eventTopic.startsWith(subTopic + "/");
    }
}
