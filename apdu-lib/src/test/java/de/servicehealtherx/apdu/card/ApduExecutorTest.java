package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.card.transport.ReaderCapabilities;
import de.servicehealtherx.apdu.model.ExpectedStatusSet;
import de.servicehealtherx.apdu.model.GeneratedApduStep;
import de.servicehealtherx.apdu.model.TucGenerationResult;

class ApduExecutorTest {

    private static GeneratedApduStep step(int sw) {
        return new GeneratedApduStep(new CommandAPDU(0x00, 0xA4, 0x04, 0x0C),
                ExpectedStatusSet.of(sw), "step");
    }

    @Test
    void executes_steps_in_order_and_returns_responses() throws Exception {
        ScriptPort port = new ScriptPort(List.of(sw(0x9000), sw(0x9000)));
        var result = TucGenerationResult.of(List.of(step(0x9000), step(0x9000)));
        var responses = new ApduExecutor(port).execute(result, 1);
        assertEquals(2, responses.size());
        assertEquals(0x9000, responses.get(0).getSW());
    }

    @Test
    void throws_on_unexpected_status_word() {
        ScriptPort port = new ScriptPort(List.of(sw(0x6A82)));
        var result = TucGenerationResult.of(List.of(step(0x9000)));
        ApduExecutionException ex = assertThrows(ApduExecutionException.class,
                () -> new ApduExecutor(port).execute(result, 1));
        assertEquals(0x6A82, ex.statusWord());
    }

    private static byte[] sw(int sw) {
        return new byte[]{(byte) (sw >> 8), (byte) (sw & 0xFF)};
    }

    private static final class ScriptPort implements CardReaderPort {
        private final Deque<ResponseAPDU> q = new ArrayDeque<>();

        ScriptPort(List<byte[]> responses) {
            responses.forEach(r -> q.add(new ResponseAPDU(r)));
        }

        @Override
        public ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException {
            return q.poll();
        }

        @Override public String readerName() { return "s"; }
        @Override public UUID ctid() { return UUID.randomUUID(); }
        @Override public ReaderCapabilities capabilities() { return null; }
        @Override public boolean isCardPresent(int slotNo) { return true; }
        @Override public void addPresenceListener(PresenceListener l) {}
        @Override public void removePresenceListener(PresenceListener l) {}
    }
}
