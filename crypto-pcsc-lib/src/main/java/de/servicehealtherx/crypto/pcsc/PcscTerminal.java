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

    /**
     * VERIFY a PIN using the reader's secure PIN pad (PC/SC class-2 reader, {@code FEATURE_VERIFY_PIN
     * _DIRECT}). The secret is entered by the card holder on the reader and never touches this
     * process. {@code selectApdu} (may be {@code null}) is transmitted first on the same connection so
     * a dfSpecific PIN (e.g. PIN.QES in DF.QES) is verified in the right application context.
     *
     * @return the card's status word for the VERIFY (e.g. {@code 0x9000}, {@code 0x63Cx})
     * @throws CardTransportException if the reader has no PIN pad or the exchange fails
     */
    default int verifyPinOnPad(CommandAPDU selectApdu, int pinRef) throws CardTransportException {
        throw new CardTransportException("Reader " + name() + " does not support PIN-pad VERIFY");
    }

    /**
     * Release any card connection this terminal currently holds. Called when the card is removed or
     * the reader is unplugged so the held selection/security state is dropped and the next insertion
     * starts from a fresh connection. No-op when nothing is connected.
     */
    default void disconnect() {
    }
}
