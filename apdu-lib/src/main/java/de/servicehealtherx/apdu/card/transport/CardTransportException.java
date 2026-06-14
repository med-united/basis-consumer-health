package de.servicehealtherx.apdu.card.transport;

/**
 * Thrown by a {@link CardReaderPort} when APDU transmission fails or the reader/slot is
 * unavailable. Transport-neutral: carries no PC/SC- or SICCT-specific type.
 */
public class CardTransportException extends Exception {

    public CardTransportException(String message) {
        super(message);
    }

    public CardTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
