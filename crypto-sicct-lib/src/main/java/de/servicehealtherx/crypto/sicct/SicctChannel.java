package de.servicehealtherx.crypto.sicct;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardTransportException;

/**
 * Thin seam over a SICCT terminal connection (one per CtID) so {@link SicctCardReaderPort} can be
 * unit-tested without a live Netty/SICCT channel. The real binding wraps {@code sicct-lib}'s
 * terminal connection; tests supply a fake.
 */
public interface SicctChannel {

    /** SICCT terminal id (CtID). */
    java.util.UUID ctid();

    /** Terminal name/label. */
    String name();

    /** Whether a card is present in the given slot. */
    boolean isCardPresent(int slotNo);

    /** Transmit one APDU to the card in the given slot. */
    ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException;
}
