package de.servicehealtherx.apdu.card.transport;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Set;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

/**
 * Transport-neutral {@link CardReaderPort} test double. Lets a test script card insertion/removal
 * and canned APDU responses without any real PC/SC or SICCT hardware — the single double both
 * transports' behaviour is validated against (plan.md testing; SC-018).
 */
public final class FakeCardReaderPort implements CardReaderPort {

    private static final byte[] SW_OK = new byte[] {(byte) 0x90, 0x00};

    private final String readerName;
    private final UUID ctid;
    private final ReaderCapabilities capabilities;
    private final Set<Integer> occupiedSlots = ConcurrentHashMap.newKeySet();
    private final List<PresenceListener> listeners = new CopyOnWriteArrayList<>();

    private ResponseAPDU cannedResponse = new ResponseAPDU(SW_OK);
    private boolean failTransmit = false;

    public FakeCardReaderPort(String readerName, ReaderCapabilities capabilities) {
        this.readerName = readerName;
        this.ctid = UUID.nameUUIDFromBytes(readerName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.capabilities = capabilities;
    }

    /** A fake configured like a typical directly PC/SC-connected reader. */
    public static FakeCardReaderPort pcsc(String readerName) {
        return new FakeCardReaderPort(readerName, ReaderCapabilities.pcscDefault());
    }

    /** A fake configured like a typical SICCT terminal. */
    public static FakeCardReaderPort sicct(String readerName) {
        return new FakeCardReaderPort(readerName, ReaderCapabilities.sicctDefault());
    }

    @Override
    public String readerName() {
        return readerName;
    }

    @Override
    public UUID ctid() {
        return ctid;
    }

    @Override
    public ReaderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public boolean isCardPresent(int slotNo) {
        return occupiedSlots.contains(slotNo);
    }

    @Override
    public ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException {
        if (failTransmit) {
            throw new CardTransportException("simulated transmit failure on slot " + slotNo);
        }
        return cannedResponse;
    }

    @Override
    public void addPresenceListener(PresenceListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removePresenceListener(PresenceListener listener) {
        listeners.remove(listener);
    }

    // --- test scripting helpers ---------------------------------------------------------------

    /** Simulate a card being inserted into a slot; notifies listeners. */
    public void simulateInsert(int slotNo) {
        occupiedSlots.add(slotNo);
        listeners.forEach(l -> l.onCardInserted(slotNo));
    }

    /** Simulate a card being removed from a slot; notifies listeners. */
    public void simulateRemove(int slotNo) {
        occupiedSlots.remove(slotNo);
        listeners.forEach(l -> l.onCardRemoved(slotNo));
    }

    public void setCannedResponse(ResponseAPDU response) {
        this.cannedResponse = response;
    }

    public void setFailTransmit(boolean failTransmit) {
        this.failTransmit = failTransmit;
    }
}
