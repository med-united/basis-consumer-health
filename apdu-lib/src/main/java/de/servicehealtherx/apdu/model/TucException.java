package de.servicehealtherx.apdu.model;

public final class TucException extends RuntimeException {

    private final int errorCode;
    private final String tucIdentifier;

    public TucException(int errorCode, String tucIdentifier, String message) {
        super(message);
        validateErrorCode(errorCode);
        validateIdentifier(tucIdentifier);
        this.errorCode = errorCode;
        this.tucIdentifier = tucIdentifier;
    }

    public TucException(int errorCode, String tucIdentifier, String message, Throwable cause) {
        super(message, cause);
        validateErrorCode(errorCode);
        validateIdentifier(tucIdentifier);
        this.errorCode = errorCode;
        this.tucIdentifier = tucIdentifier;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getTucIdentifier() {
        return tucIdentifier;
    }

    public static TucException pinBlocked(String tucIdentifier) {
        return new TucException(4063, tucIdentifier, "PIN is blocked — no retries remaining");
    }

    public static TucException transportPin(String tucIdentifier) {
        return new TucException(4065, tucIdentifier, "Transport PIN active — must be changed before use");
    }

    public static TucException cardReservedByOther(String tucIdentifier) {
        return new TucException(4093, tucIdentifier, "Card is reserved by another session");
    }

    public static TucException cardAccessTimeout(String tucIdentifier) {
        return new TucException(4094, tucIdentifier, "Card access timeout");
    }

    public static TucException invalidPinRef(String tucIdentifier) {
        return new TucException(4072, tucIdentifier, "Invalid PIN reference for this card type");
    }

    public static TucException remoteKtNotConfigured(String tucIdentifier) {
        return new TucException(4092, tucIdentifier, "Remote-PIN card terminal not configured for mandant");
    }

    public static TucException internalError(String tucIdentifier, String detail) {
        return new TucException(4001, tucIdentifier, "Internal error: " + detail);
    }

    public static TucException kvkWriteRejected(String tucIdentifier) {
        return new TucException(4001, tucIdentifier, "Write/clear operations are prohibited on KVK cards");
    }

    public static TucException missingC2CCard(String tucIdentifier) {
        return new TucException(4071, tucIdentifier, "Required card for Card-to-Card authentication is missing");
    }

    private static void validateErrorCode(int code) {
        if (code < 4001 || code > 4094) {
            throw new IllegalArgumentException("Error code must be in range [4001, 4094], got: " + code);
        }
    }

    private static void validateIdentifier(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("TUC identifier must not be null or blank");
        }
    }
}
