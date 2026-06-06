package de.servicehealtherx.crypto;

import java.util.regex.Pattern;

public record KeyAlias(String value) {

    private static final Pattern PATTERN = Pattern.compile("^(p12|pkcs11|pcsc|sicct)/[a-z0-9\\-_]+$");
    private static final int MAX_LENGTH = 128;

    public KeyAlias {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("KeyAlias must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("KeyAlias exceeds maximum length of " + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "KeyAlias must match pattern ^(p12|pkcs11|pcsc|sicct)/[a-z0-9\\-_]+$ but was: " + value);
        }
    }

    public SourceType sourceType() {
        String prefix = value.substring(0, value.indexOf('/'));
        return switch (prefix) {
            case "p12" -> SourceType.P12;
            case "pkcs11" -> SourceType.PKCS11;
            case "pcsc" -> SourceType.PCSC;
            case "sicct" -> SourceType.SICCT;
            default -> throw new IllegalStateException("Unrecognized prefix: " + prefix);
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
