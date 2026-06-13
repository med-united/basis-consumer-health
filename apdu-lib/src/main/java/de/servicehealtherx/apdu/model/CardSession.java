package de.servicehealtherx.apdu.model;

import java.util.Objects;
import java.util.UUID;

public final class CardSession {

    private final String cardHandle;
    private final CardType cardType;
    private final CardVersion cardVersion;
    private final AuthState authState;
    private UUID sessionId;
    private String lockOwner;

    public CardSession(String cardHandle, CardType cardType, CardVersion cardVersion, AuthState authState) {
        this.cardHandle = Objects.requireNonNull(cardHandle, "cardHandle");
        this.cardType = Objects.requireNonNull(cardType, "cardType");
        this.cardVersion = Objects.requireNonNull(cardVersion, "cardVersion");
        this.authState = Objects.requireNonNull(authState, "authState");
    }

    public String cardHandle() {
        return cardHandle;
    }

    public CardType cardType() {
        return cardType;
    }

    public CardVersion cardVersion() {
        return cardVersion;
    }

    public AuthState authState() {
        return authState;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public void assignSessionId(UUID id) {
        this.sessionId = id;
    }

    public String lockOwner() {
        return lockOwner;
    }

    public void setLockOwner(String owner) {
        this.lockOwner = owner;
    }

    public boolean isLockedByOther(String callerIdentity) {
        return lockOwner != null && !lockOwner.equals(callerIdentity);
    }
}
