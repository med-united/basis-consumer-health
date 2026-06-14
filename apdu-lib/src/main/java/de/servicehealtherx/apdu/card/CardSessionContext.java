package de.servicehealtherx.apdu.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A single active context on a card (data-model §CardSession). Abstract base for the three
 * concrete session subtypes, each with its own identity key (FR-021):
 * <ul>
 *   <li>{@link EgkCardSession} — key {@code cardHandle} (one per eGK, Constraint C1)</li>
 *   <li>{@link SmbCardSession} — key {@code cardHandle + mandantId} (Constraint C3)</li>
 *   <li>{@link HbaxCardSession} — key {@code cardHandle + csid + userId} (Constraint C2)</li>
 * </ul>
 *
 * <p>Named {@code CardSessionContext} to avoid clashing with the feature-007
 * {@code de.servicehealtherx.apdu.model.CardSession} APDU-generation record.
 */
public abstract sealed class CardSessionContext
        permits EgkCardSession, SmbCardSession, HbaxCardSession {

    private final String mandantId;
    private final String csid;
    private final String userId;
    private final List<AuthEntry> authState = new ArrayList<>();

    protected CardSessionContext(String mandantId, String csid, String userId) {
        this.mandantId = Objects.requireNonNull(mandantId, "mandantId");
        this.csid = Objects.requireNonNull(csid, "csid");
        this.userId = userId; // nullable for eGK/SM-B; mandatory for HBAx (enforced in subclass)
    }

    public String mandantId() {
        return mandantId;
    }

    public String csid() {
        return csid;
    }

    public String userId() {
        return userId;
    }

    /** Mutable list of achieved authentication states; accumulates as the session progresses. */
    public List<AuthEntry> authState() {
        return authState;
    }
}
