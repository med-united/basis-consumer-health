package de.servicehealtherx.cetp.subscription;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A client-side CETP delivery target (Ereignissenke), identified by its {@code cetp://host:port}
 * URL. Persisted independently of its {@link Subscription}s so it can still receive the
 * {@code BOOTUP/BOOTUP_COMPLETE} notification after the subscription list is cleared at bootup
 * (gemSpec_Kon TUC_KON_256 BOOTUP variant, FR-008/FR-017).
 *
 * <p>Owns the per-sink consecutive-failure counter: Auto-Unsubscribe (TIP1-A_4611) is defined
 * "analog Unsubscribe by EventTo URL", i.e. per sink, so a cascade delete of this endpoint removes
 * all of its subscriptions at once.
 */
@Entity
@Table(name = "CETP_CLIENT_ENDPOINT")
public class ClientEndpoint extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "ID", nullable = false, updatable = false)
    public UUID id;

    /** Full sink URL {@code cetp://host:port} from the Subscribe EventTo field. */
    @Column(name = "EVENT_TO", nullable = false, unique = true, length = 256)
    public String eventTo;

    @Column(name = "HOST", nullable = false, length = 253)
    public String host;

    @Column(name = "PORT", nullable = false)
    public int port;

    /**
     * Longest {@code terminationTime} across this sink's subscriptions; the endpoint URL is
     * retained until this passes so a delayed BOOTUP_COMPLETE can still be delivered (FR-008).
     */
    @Column(name = "RETAINED_UNTIL", nullable = false)
    public Instant retainedUntil;

    /** Consecutive failed connection/delivery attempts to this sink (FR-019); reset on success. */
    @Column(name = "FAILURE_COUNT", nullable = false)
    public int failureCount = 0;

    @OneToMany(mappedBy = "endpoint", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<Subscription> subscriptions = new ArrayList<>();

    public static ClientEndpoint findByEventTo(String eventTo) {
        return find("eventTo = ?1", eventTo).firstResult();
    }
}
