package de.servicehealtherx.crypto.sicct;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

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
        return channel.transmit(slotNo, command);
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
