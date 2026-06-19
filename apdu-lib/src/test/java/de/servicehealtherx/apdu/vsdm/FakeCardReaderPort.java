package de.servicehealtherx.apdu.vsdm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.card.transport.ReaderCapabilities;

/**
 * Test double for {@link CardReaderPort} that replays a scripted list of responses in transmit
 * order, ignoring the command content. Deterministic — no hardware. An optional pre-transmit gate
 * supports timeout/concurrency tests.
 */
public final class FakeCardReaderPort implements CardReaderPort {

    private final UUID ctid;
    private final Deque<ResponseAPDU> scripted = new ArrayDeque<>();
    private Runnable beforeTransmit = () -> {};

    public FakeCardReaderPort(UUID ctid, List<byte[]> responses) {
        this.ctid = ctid;
        for (byte[] r : responses) {
            scripted.add(new ResponseAPDU(r));
        }
    }

    public void onBeforeTransmit(Runnable hook) {
        this.beforeTransmit = hook;
    }

    /** Build a {@code data || SW} response payload. */
    public static byte[] resp(byte[] data, int sw) {
        byte[] out = new byte[(data == null ? 0 : data.length) + 2];
        if (data != null) {
            System.arraycopy(data, 0, out, 0, data.length);
        }
        out[out.length - 2] = (byte) (sw >> 8);
        out[out.length - 1] = (byte) (sw & 0xFF);
        return out;
    }

    public static byte[] ok() {
        return resp(null, 0x9000);
    }

    @Override
    public ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException {
        beforeTransmit.run();
        if (scripted.isEmpty()) {
            throw new CardTransportException("no scripted response left");
        }
        return scripted.poll();
    }

    @Override
    public String readerName() {
        return "fake";
    }

    @Override
    public UUID ctid() {
        return ctid;
    }

    @Override
    public ReaderCapabilities capabilities() {
        return null;
    }

    @Override
    public boolean isCardPresent(int slotNo) {
        return true;
    }

    @Override
    public void addPresenceListener(PresenceListener listener) {
    }

    @Override
    public void removePresenceListener(PresenceListener listener) {
    }
}
