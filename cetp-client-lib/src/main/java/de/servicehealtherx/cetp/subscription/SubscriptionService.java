package de.servicehealtherx.cetp.subscription;

import de.servicehealtherx.cetp.TimeSource;
import de.servicehealtherx.cetp.delivery.CetpEventSender.HostPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists and manages CETP subscriptions for {@code KonnektorEventService} (gemSpec_Kon §4.1.6.5)
 * and provides the per-sink failure handling that drives Auto-Unsubscribe (TIP1-A_4611).
 */
@ApplicationScoped
public class SubscriptionService {

    /** Maximum validity window of a subscription (gemSpec_Kon Subscribe: system time + 25 h). */
    static final Duration VALIDITY = Duration.ofHours(25);

    @Inject
    TimeSource timeSource;

    @ConfigProperty(name = "cetp.evt-max-try", defaultValue = "3")
    int evtMaxTry;

    /** TIP1-A_4608: create or refresh a subscription; returns its id + terminationTime. */
    @Transactional
    public SubscribeResult subscribe(String mandantId, String clientSystemId, String workplaceId,
            String eventTo, String topic, String filter) {
        HostPort hostPort = HostPort.parse(eventTo); // throws on invalid → caller maps to error 4000
        Instant terminationTime = timeSource.now().plus(VALIDITY);

        ClientEndpoint endpoint = ClientEndpoint.findByEventTo(eventTo);
        if (endpoint == null) {
            endpoint = new ClientEndpoint();
            endpoint.eventTo = eventTo;
            endpoint.host = hostPort.host();
            endpoint.port = hostPort.port();
            endpoint.retainedUntil = terminationTime;
            endpoint.persist();
        }

        Subscription existing = Subscription.find(
                "endpoint = ?1 and topic = ?2 and mandantId = ?3 and clientSystemId = ?4 and workplaceId = ?5",
                endpoint, topic, mandantId, clientSystemId, workplaceId).firstResult();

        Subscription subscription = existing != null ? existing : new Subscription();
        if (existing == null) {
            subscription.subscriptionId = UUID.randomUUID();
            subscription.endpoint = endpoint;
            subscription.topic = topic;
            subscription.mandantId = mandantId;
            subscription.clientSystemId = clientSystemId;
            subscription.workplaceId = workplaceId;
        }
        subscription.filter = filter; // refresh filter on repeat Subscribe (FR-012)
        subscription.terminationTime = terminationTime;
        if (existing == null) {
            subscription.persist();
        }
        if (endpoint.retainedUntil.isBefore(terminationTime)) {
            endpoint.retainedUntil = terminationTime;
        }
        return new SubscribeResult(subscription.subscriptionId, terminationTime);
    }

    /** TIP1-A_4609: remove subscription(s) by subscription id and/or event sink URL. */
    @Transactional
    public void unsubscribe(UUID subscriptionId, String eventTo) {
        if (subscriptionId != null) {
            Subscription s = Subscription.findBySubscriptionId(subscriptionId);
            if (s != null) {
                removeAndCleanup(s);
            }
        }
        if (eventTo != null) {
            ClientEndpoint endpoint = ClientEndpoint.findByEventTo(eventTo);
            if (endpoint != null) {
                endpoint.delete(); // cascade removes its subscriptions
            }
        }
    }

    /** TIP1-A_5112: renew every still-valid subscription; expired ones are not renewed. */
    @Transactional
    public List<Renewal> renew() {
        Instant now = timeSource.now();
        Instant terminationTime = now.plus(VALIDITY);
        List<Subscription> valid = Subscription.findValid(now);
        for (Subscription s : valid) {
            s.terminationTime = terminationTime;
            if (s.endpoint.retainedUntil.isBefore(terminationTime)) {
                s.endpoint.retainedUntil = terminationTime;
            }
        }
        return valid.stream().map(s -> new Renewal(s.subscriptionId, terminationTime)).toList();
    }

    public List<SubscriptionView> getSubscriptions() {
        return Subscription.<Subscription>listAll().stream()
                .map(s -> new SubscriptionView(s.subscriptionId, s.endpoint.eventTo, s.topic, s.filter))
                .toList();
    }

    /**
     * TIP1-A_4613 / FR-017: at bootup, clear all subscriptions but return the endpoint URLs still
     * within their retention window (for the BOOTUP_COMPLETE send); then drop expired endpoints.
     */
    @Transactional
    public List<String> clearOnBootup() {
        Instant now = timeSource.now();
        Subscription.deleteAll();
        List<String> retained = ClientEndpoint.<ClientEndpoint>list("retainedUntil > ?1", now)
                .stream().map(e -> e.eventTo).toList();
        ClientEndpoint.delete("retainedUntil <= ?1", now);
        return retained;
    }

    /** FR-019/FR-028: count a failed attempt; returns true if the endpoint was auto-unsubscribed. */
    @Transactional
    public boolean recordFailure(UUID endpointId) {
        ClientEndpoint endpoint = ClientEndpoint.findById(endpointId);
        if (endpoint == null) {
            return false;
        }
        endpoint.failureCount++;
        if (endpoint.failureCount >= evtMaxTry) {
            endpoint.delete(); // Auto-Unsubscribe (TIP1-A_4611) — cascade removes subscriptions
            return true;
        }
        return false;
    }

    /** FR-021: a successful delivery resets the sink's consecutive-failure counter. */
    @Transactional
    public void recordSuccess(UUID endpointId) {
        ClientEndpoint endpoint = ClientEndpoint.findById(endpointId);
        if (endpoint != null) {
            endpoint.failureCount = 0;
        }
    }

    private static void removeAndCleanup(Subscription subscription) {
        ClientEndpoint endpoint = subscription.endpoint;
        subscription.delete();
        if (Subscription.count("endpoint = ?1", endpoint) == 0) {
            endpoint.delete();
        }
    }

    public int evtMaxTry() {
        return evtMaxTry;
    }

    /** Current consecutive-failure count for a sink, or {@code -1} if it no longer exists (FR-019). */
    @Transactional
    public int failureCount(String eventTo) {
        ClientEndpoint endpoint = ClientEndpoint.findByEventTo(eventTo);
        return endpoint == null ? -1 : endpoint.failureCount;
    }

    public record SubscribeResult(UUID subscriptionId, Instant terminationTime) {
    }

    public record Renewal(UUID subscriptionId, Instant terminationTime) {
    }

    public record SubscriptionView(UUID subscriptionId, String eventTo, String topic, String filter) {
    }
}
