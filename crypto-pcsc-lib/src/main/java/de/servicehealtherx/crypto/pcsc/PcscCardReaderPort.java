package de.servicehealtherx.crypto.pcsc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.ApduTrace;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.card.transport.ReaderCapabilities;

/**
 * {@link CardReaderPort} over a directly connected PC/SC reader (US2 task T023). Wraps a
 * {@link PcscTerminal} seam so it is unit-testable without a live PC/SC subsystem.
 *
 * <p>A PC/SC reader is single-slot (slot {@value #PCSC_SLOT}); its {@code ctid} is synthesized as
 * {@code UUID.nameUUIDFromBytes(readerName)} so the same physical reader maps to the same id across
 * reconnects (FR-068). Insert/remove is detected by {@link #poll()} comparing card presence to the
 * last observed state — driven by {@link PcscReaderRegistry} on a schedule within the 2 s budget
 * (FR-001).
 */
public final class PcscCardReaderPort implements CardReaderPort {

    /** PC/SC readers expose a single slot. */
    public static final int PCSC_SLOT = 1;

    private final PcscTerminal terminal;
    private final UUID ctid;
    private final ReaderCapabilities capabilities;
    private final List<PresenceListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean lastPresent;

    public PcscCardReaderPort(PcscTerminal terminal) {
        this(terminal, ReaderCapabilities.pcscDefault());
    }

    public PcscCardReaderPort(PcscTerminal terminal, ReaderCapabilities capabilities) {
        this.terminal = terminal;
        this.ctid = synthesizeCtid(terminal.name());
        this.capabilities = capabilities;
        // Start as "absent" so the first poll() treats an already-present card as an insertion —
        // this is the startup-reconstruction path (FR-042/FR-069).
        this.lastPresent = false;
    }

    /** Stable, name-derived terminal id for a PC/SC reader (FR-068). */
    public static UUID synthesizeCtid(String readerName) {
        return UUID.nameUUIDFromBytes(readerName.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String readerName() {
        return terminal.name();
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
        return terminal.isCardPresent();
    }

    @Override
    public ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException {
        return ApduTrace.trace(readerName(), slotNo, command, () -> terminal.transmit(command));
    }

    @Override
    public void addPresenceListener(PresenceListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removePresenceListener(PresenceListener listener) {
        listeners.remove(listener);
    }

    /**
     * Poll the reader once and fire insert/remove notifications on a presence change. Idempotent
     * when presence is unchanged. Returns the current presence state.
     */
    public boolean poll() {
        boolean present = terminal.isCardPresent();
        if (present && !lastPresent) {
            lastPresent = true;
            listeners.forEach(l -> l.onCardInserted(PCSC_SLOT));
        } else if (!present && lastPresent) {
            lastPresent = false;
            listeners.forEach(l -> l.onCardRemoved(PCSC_SLOT));
        }
        return present;
    }
}
