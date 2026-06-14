package de.servicehealtherx.apdu.card;

import java.util.Objects;

/**
 * A single achieved security state within a CardSession (data-model §AuthState). Either a
 * Card-to-Card (C2C) authentication entry (key reference + role per gemSpec_PKI_TI#Tab_PKI_918)
 * or a Card Holder Verification (CHV) entry (PIN reference).
 *
 * <p>Named {@code AuthEntry} to avoid clashing with the feature-007
 * {@code de.servicehealtherx.apdu.model.AuthState} PIN/key-tracking class.
 */
public record AuthEntry(Kind kind, String keyRef, String role, String pinRef) {

    public enum Kind { C2C, CHV }

    public AuthEntry {
        Objects.requireNonNull(kind, "kind");
    }

    /** C2C authentication: key reference + role. */
    public static AuthEntry c2c(String keyRef, String role) {
        return new AuthEntry(Kind.C2C, Objects.requireNonNull(keyRef, "keyRef"), role, null);
    }

    /** Card Holder Verification: PIN reference. */
    public static AuthEntry chv(String pinRef) {
        return new AuthEntry(Kind.CHV, null, null, Objects.requireNonNull(pinRef, "pinRef"));
    }
}
