package de.servicehealtherx.crypto.sicct;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.ApduTrace;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.card.transport.ReaderCapabilities;

/**
 * {@link CardReaderPort} over a SICCT terminal (US3 task T032), backed by a {@link SicctChannel}
 * seam. Symmetric to the PC/SC port so every card-handle use case runs identically over both
 * transports (FR-063). A SICCT terminal has a display and mechanical eject
 * ({@link ReaderCapabilities#sicctDefault()}).
 *
 * <p>Unlike PC/SC (poll-based), SICCT delivers insert/remove asynchronously via the terminal's
 * event channel; {@link #onCardInserted(int)} / {@link #onCardRemoved(int)} are invoked by the
 * SICCT runtime ({@code SicctChannelHandler}) and fan out to the registered presence listeners.
 */
public final class SicctCardReaderPort implements CardReaderPort {

    /**
     * One lock per terminal (CtID): a single SICCT terminal channel multiplexes background slot
     * discovery and foreground crypto onto the same physical cards, and a card has a single
     * selected-file/security state. Serialising logical operations per terminal stops, e.g., a
     * discovery {@code SELECT MF} landing between a cert read's {@code SELECT DF.ESIGN} and its
     * {@code READ BINARY} (which then fails 6A82). Keyed by CtID and shared across port instances so
     * the guarantee holds even if a terminal is re-wrapped in a fresh port.
     */
    private static final ConcurrentHashMap<UUID, ReentrantLock> CARD_LOCKS = new ConcurrentHashMap<>();

    private final SicctChannel channel;
    private final ReaderCapabilities capabilities;
    private final List<PresenceListener> listeners = new CopyOnWriteArrayList<>();

    public SicctCardReaderPort(SicctChannel channel) {
        this(channel, ReaderCapabilities.sicctDefault());
    }

    public SicctCardReaderPort(SicctChannel channel, ReaderCapabilities capabilities) {
        this.channel = channel;
        this.capabilities = capabilities;
    }

    @Override
    public String readerName() {
        return channel.name();
    }

    @Override
    public UUID ctid() {
        return channel.ctid();
    }

    @Override
    public ReaderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public boolean isCardPresent(int slotNo) {
        return channel.isCardPresent(slotNo);
    }

    @Override
    public ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException {
        return ApduTrace.trace(readerName(), slotNo, command, () -> channel.transmit(slotNo, command));
    }

    /** Serialises the operation against all other card access on the same terminal (see field doc). */
    @Override
    public <T> T runExclusively(CardOperation<T> action) throws CardTransportException {
        ReentrantLock lock = CARD_LOCKS.computeIfAbsent(ctid(), id -> new ReentrantLock());
        lock.lock();
        try {
            return action.run();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addPresenceListener(PresenceListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removePresenceListener(PresenceListener listener) {
        listeners.remove(listener);
    }

    /** Invoked by the SICCT runtime on a CT/SLOT_IN_USE event for {@code slotNo}. */
    public void onCardInserted(int slotNo) {
        listeners.forEach(l -> l.onCardInserted(slotNo));
    }

    /** Invoked by the SICCT runtime on a CT/SLOT_FREE event for {@code slotNo}. */
    public void onCardRemoved(int slotNo) {
        listeners.forEach(l -> l.onCardRemoved(slotNo));
    }
}
