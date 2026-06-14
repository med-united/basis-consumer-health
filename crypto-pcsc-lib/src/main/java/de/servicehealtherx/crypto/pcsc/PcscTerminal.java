package de.servicehealtherx.crypto.pcsc;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardTransportException;

/**
 * Thin seam over a single {@code javax.smartcardio.CardTerminal} so {@link PcscCardReaderPort} can
 * be unit-tested without a live PC/SC subsystem. The real implementation is
 * {@link SmartcardioPcscTerminal}; tests supply a fake.
 */
public interface PcscTerminal {

    /** The PC/SC reader name (basis for the synthesized {@code ctid}, FR-068). */
    String name();

    /** Whether a card is currently present in the reader. */
    boolean isCardPresent();

    /** Connect (if needed), transmit one APDU, and return the response. */
    ResponseAPDU transmit(CommandAPDU command) throws CardTransportException;
}
