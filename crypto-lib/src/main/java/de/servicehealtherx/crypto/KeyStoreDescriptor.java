package de.servicehealtherx.crypto;

import java.time.Instant;

public class KeyStoreDescriptor {

    public final KeyAlias alias;
    public final SourceType sourceType;
    private volatile KeyStoreAvailability availability;
    private volatile String errorMessage;
    private volatile Instant lastUpdated;

    public KeyStoreDescriptor(KeyAlias alias, SourceType sourceType) {
        this.alias = alias;
        this.sourceType = sourceType;
        this.availability = KeyStoreAvailability.UNAVAILABLE;
        this.lastUpdated = Instant.now();
    }

    public KeyStoreAvailability getAvailability() {
        return availability;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void markAvailable() {
        this.availability = KeyStoreAvailability.AVAILABLE;
        this.errorMessage = null;
        this.lastUpdated = Instant.now();
    }

    public void markUnavailable() {
        this.availability = KeyStoreAvailability.UNAVAILABLE;
        this.errorMessage = null;
        this.lastUpdated = Instant.now();
    }

    public void markError(String errorMessage) {
        this.availability = KeyStoreAvailability.ERROR;
        this.errorMessage = errorMessage;
        this.lastUpdated = Instant.now();
    }
}
