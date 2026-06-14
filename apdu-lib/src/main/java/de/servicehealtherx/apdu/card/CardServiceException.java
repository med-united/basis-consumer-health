package de.servicehealtherx.apdu.card;

/**
 * Carries a gemSpec_Kon card-service error code that falls outside the {@code TucException}
 * validated range ([4001, 4094]) — e.g. 4278 (max comfort signature sessions), 4288 (unknown
 * sessionID), 4101 (invalid handle). The numeric code is preserved for caller interpretation.
 */
public final class CardServiceException extends RuntimeException {

    private final int errorCode;

    public CardServiceException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
