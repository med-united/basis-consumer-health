package de.servicehealtherx.cetp.subscription;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A client system's persisted registration of interest in one topic at one sink under one caller
 * context (gemSpec_Kon §4.1.6.5.4 Subscribe, Ablauf step 3 {@code saveSubscription}, TIP1-A_4608).
 *
 * <p>Business uniqueness key (FR-012): {@code (endpoint, topic, mandantId, clientSystemId,
 * workplaceId)}. A repeat Subscribe matching this key returns the existing {@link #subscriptionId}
 * and refreshes {@link #filter} + {@link #terminationTime} rather than inserting a duplicate.
 */
@Entity
@Table(name = "CETP_SUBSCRIPTION",
        uniqueConstraints = @UniqueConstraint(name = "UK_CETP_SUBSCRIPTION",
                columnNames = {"ENDPOINT_ID", "TOPIC", "MANDANT_ID", "CLIENT_SYSTEM_ID", "WORKPLACE_ID"}))
public class Subscription extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "ID", nullable = false, updatable = false)
    public UUID id;

    /** Business identifier returned by Subscribe; used by Unsubscribe/Renew/GetSubscription. */
    @Column(name = "SUBSCRIPTION_ID", nullable = false, unique = true)
    public UUID subscriptionId;

    @ManyToOne(optional = false)
    @jakarta.persistence.JoinColumn(name = "ENDPOINT_ID", nullable = false)
    public ClientEndpoint endpoint;

    /** Subscribed topic (tree path, e.g. {@code CARD/INSERTED}). */
    @Column(name = "TOPIC", nullable = false, length = 256)
    public String topic;

    /** Optional XPath filter (default-NS = EventService v7.2, prefix {@code EVT}). */
    @Column(name = "FILTER", length = 1024)
    public String filter;

    @Column(name = "MANDANT_ID", nullable = false, length = 128)
    public String mandantId;

    @Column(name = "CLIENT_SYSTEM_ID", nullable = false, length = 128)
    public String clientSystemId;

    @Column(name = "WORKPLACE_ID", nullable = false, length = 128)
    public String workplaceId;

    /** Validity limit; set to {@code now + 25h} on Subscribe/Renew (FR-010, TIP1-A_4608). */
    @Column(name = "TERMINATION_TIME", nullable = false)
    public Instant terminationTime;

    /** All subscriptions still valid at {@code now} (delivery-selection set, FR-002). */
    public static List<Subscription> findValid(Instant now) {
        return list("terminationTime > ?1", now);
    }

    public static Subscription findBySubscriptionId(UUID subscriptionId) {
        return find("subscriptionId = ?1", subscriptionId).firstResult();
    }
}
