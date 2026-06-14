package de.servicehealtherx.sicct.jpa;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted admin-overview log entry for an inserted G2.0 SMC-B / HBA card (A_25801 / FR-050).
 * Survives Konnektor restarts (unlike the in-memory CM_CARD_LIST). One row per ICCSN — re-insertion
 * updates {@code lastInsertTime} / {@code pseudonym} via {@link #upsert}.
 *
 * <p>The pseudonym is computed transport-neutrally by {@code apdu-lib}'s {@code G2Pseudonym} and
 * passed in, so this module keeps no dependency on apdu-lib.
 */
@Entity
@Table(name = "G2_CARD_LOG")
public class G2CardLog extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "ID", nullable = false, updatable = false)
    public UUID id;

    @Column(name = "ICCSN", nullable = false, unique = true, length = 32)
    public String iccsn;

    @Column(name = "PSEUDONYM", nullable = false, length = 10)
    public String pseudonym;

    @Column(name = "CARD_TYPE", nullable = false, length = 16)
    public String cardType;

    @Column(name = "CARD_HOLDER_NAME", length = 256)
    public String cardHolderName;

    @Column(name = "EXPIRATION_DATE", nullable = false)
    public LocalDate expirationDate;

    @Column(name = "LAST_INSERT_TIME", nullable = false)
    public Instant lastInsertTime;

    /**
     * Insert a new row for {@code iccsn}, or update the existing one's {@code pseudonym},
     * {@code lastInsertTime} (and other mutable fields) on re-insertion.
     */
    public static G2CardLog upsert(String iccsn, String pseudonym, String cardType,
                                   String cardHolderName, LocalDate expirationDate, Instant insertTime) {
        G2CardLog entry = find("iccsn", iccsn).firstResult();
        if (entry == null) {
            entry = new G2CardLog();
            entry.iccsn = iccsn;
        }
        entry.pseudonym = pseudonym;
        entry.cardType = cardType;
        entry.cardHolderName = cardHolderName;
        entry.expirationDate = expirationDate;
        entry.lastInsertTime = insertTime;
        entry.persist();
        return entry;
    }
}
