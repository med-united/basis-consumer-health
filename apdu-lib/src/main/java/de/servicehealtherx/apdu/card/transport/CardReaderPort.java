package de.servicehealtherx.apdu.card.transport;

import java.util.UUID;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

/**
 * Transport-neutral boundary between the transport-agnostic Kartendienst logic and a physical
 * card (research D9, FR-066). Defined in {@code apdu-lib}; implemented once per transport:
 * {@code PcscCardReaderPort} (crypto-pcsc-lib, over {@code javax.smartcardio}) and
 * {@code SicctCardReaderPort} (crypto-sicct-lib, over {@code sicct-lib}).
 *
 * <p>This interface is the ONLY place transport-specific APDU transmission occurs; CM_CARD_LIST
 * and the domain objects never reference a concrete transport. {@code apdu-lib} therefore keeps
 * its constraint of not depending on {@code sicct-lib} and not transmitting APDUs itself.
 */
public interface CardReaderPort {

    /** Stable name of the underlying reader/terminal (used to synthesize {@link #ctid()}). */
    String readerName();

    /**
     * Stable terminal id for this reader. For PC/SC this is synthesized as
     * {@code UUID.nameUUIDFromBytes(readerName)} so the same reader maps to the same id across
     * reconnects (FR-068); for SICCT it is the terminal id.
     */
    UUID ctid();

    /** Capability flags driving graceful degradation (FR-063). */
    ReaderCapabilities capabilities();

    /** Whether a card is currently present in the given slot. */
    boolean isCardPresent(int slotNo);

    /**
     * Transmit a constructed command APDU to the card in the given slot and return its response.
     *
     * @throws CardTransportException if transmission fails or the reader/slot is unavailable
     */
    ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException;

    /** Register a listener notified when a card is inserted into or removed from a slot. */
    void addPresenceListener(PresenceListener listener);

    /** Remove a previously registered presence listener. */
    void removePresenceListener(PresenceListener listener);

    /** Callback for card insertion/removal detected by the transport. */
    interface PresenceListener {
        void onCardInserted(int slotNo);

        void onCardRemoved(int slotNo);
    }
}
