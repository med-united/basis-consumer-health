package de.servicehealtherx.cetp;

import de.servicehealtherx.cetp.delivery.CetpEventSender;
import de.servicehealtherx.cetp.delivery.EventXmlWriter;
import de.servicehealtherx.cetp.filter.AccessAuthorizationFilter;
import de.servicehealtherx.cetp.filter.EventDeliveryFilter;
import de.servicehealtherx.cetp.filter.TopicPrefixFilter;
import de.servicehealtherx.cetp.filter.XPathExpressionFilter;
import de.servicehealtherx.cetp.subscription.ClientEndpoint;
import de.servicehealtherx.cetp.subscription.Subscription;
import de.servicehealtherx.cetp.subscription.SubscriptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Singleton CETP client: the PUSH side of the Systeminformationsdienst (gemSpec_Kon §4.1.6,
 * TUC_KON_256). Observes {@link KonnektorSystemEvent}s asynchronously, runs the topic → access →
 * XPath filter pipeline over the currently valid subscriptions, and delivers the framed event XML
 * to each matching client-system event sink. Failed deliveries feed the per-sink Auto-Unsubscribe
 * counter (TIP1-A_4611).
 */
@ApplicationScoped
public class CetpClient {

    private static final Logger LOG = Logger.getLogger(CetpClient.class);
    private static final String BOOTUP_COMPLETE = "BOOTUP/BOOTUP_COMPLETE";

    @Inject
    TopicPrefixFilter topicFilter;
    @Inject
    AccessAuthorizationFilter accessFilter;
    @Inject
    XPathExpressionFilter xpathFilter;
    @Inject
    EventXmlWriter eventXmlWriter;
    @Inject
    CetpEventSender eventSender;
    @Inject
    SubscriptionService subscriptionService;
    @Inject
    TimeSource timeSource;

    /**
     * Asynchronous observer (FR-001) — runs off the producer thread. Iterates the subscriptions and
     * delivers the event to the listed hosts (TUC_KON_256).
     */
    public void onKonnektorEvent(@ObservesAsync KonnektorSystemEvent event) {
        deliver(event);
    }

    void deliver(KonnektorSystemEvent event) {
        List<DeliveryTarget> targets = selectTargets(event);
        for (DeliveryTarget target : targets) {
            eventSender.send(target.eventTo(), target.xml()).thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    subscriptionService.recordSuccess(target.endpointId());
                } else if (subscriptionService.recordFailure(target.endpointId())) {
                    LOG.infof("Auto-Unsubscribe: sink %s removed after %d consecutive failures",
                            target.eventTo(), subscriptionService.evtMaxTry());
                }
            });
        }
    }

    /**
     * Read + filter the subscriptions within one transaction, returning detached delivery targets so
     * the async sends happen outside the persistence context.
     */
    @ActivateRequestContext
    @Transactional
    List<DeliveryTarget> selectTargets(KonnektorSystemEvent event) {
        if (BOOTUP_COMPLETE.equalsIgnoreCase(event.topic())) {
            return bootupCompleteTargets(event);
        }
        List<EventDeliveryFilter> pipeline = List.of(topicFilter, accessFilter, xpathFilter);
        List<DeliveryTarget> targets = new ArrayList<>();
        for (Subscription subscription : Subscription.findValid(timeSource.now())) {
            if (passesAll(pipeline, event, subscription)) {
                byte[] xml = eventXmlWriter.toEventXml(event, subscription.subscriptionId.toString());
                targets.add(new DeliveryTarget(subscription.endpoint.eventTo, subscription.endpoint.id, xml));
            }
        }
        return targets;
    }

    /** FR-008: BOOTUP_COMPLETE goes to the retained endpoint URLs with an empty SubscriptionID. */
    private List<DeliveryTarget> bootupCompleteTargets(KonnektorSystemEvent event) {
        byte[] xml = eventXmlWriter.toEventXml(event, "");
        List<DeliveryTarget> targets = new ArrayList<>();
        for (ClientEndpoint endpoint : ClientEndpoint.<ClientEndpoint>listAll()) {
            targets.add(new DeliveryTarget(endpoint.eventTo, endpoint.id, xml));
        }
        return targets;
    }

    private static boolean passesAll(List<EventDeliveryFilter> pipeline, KonnektorSystemEvent event,
            Subscription subscription) {
        for (EventDeliveryFilter filter : pipeline) {
            if (!filter.keep(event, subscription)) {
                return false;
            }
        }
        return true;
    }

    record DeliveryTarget(String eventTo, UUID endpointId, byte[] xml) {
    }
}
