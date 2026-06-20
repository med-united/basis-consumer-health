package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.transport.FakeCardReaderPort;

class CardPinStatusReaderTest {

    private static final int SLOT = 1;
    private static final int PIN_SMC = 0x01;

    @Test
    void sends_get_pin_status_apdu_without_a_pin_block() throws Exception {
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader");
        AtomicReference<CommandAPDU> sent = new AtomicReference<>();
        port.setResponder((slot, cmd) -> {
            sent.set(cmd);
            return new ResponseAPDU(new byte[] {(byte) 0x90, 0x00});
        });

        CardPinStatusReader.Result result = new CardPinStatusReader(port, SLOT).read(PIN_SMC);

        assertEquals(CardPinStatusReader.State.VERIFIED, result.state());
        CommandAPDU cmd = sent.get();
        // GET PIN STATUS is the proprietary 80 20 00 <ref> command — class 0x80, no data, no Le.
        assertEquals(0x80, cmd.getCLA());
        assertEquals(0x20, cmd.getINS());
        assertEquals(0x00, cmd.getP1());
        assertEquals(PIN_SMC, cmd.getP2());
        assertEquals(0, cmd.getData().length);
    }

    @Test
    void maps_retry_counter_to_verifiable_and_zero_to_blocked() throws Exception {
        FakeCardReaderPort verifiable = FakeCardReaderPort.pcsc("verifiable");
        verifiable.setCannedResponse(new ResponseAPDU(new byte[] {0x63, (byte) 0xC3})); // 3 tries left
        CardPinStatusReader.Result v = new CardPinStatusReader(verifiable, SLOT).read(PIN_SMC);
        assertEquals(CardPinStatusReader.State.VERIFIABLE, v.state());
        assertEquals(3, v.triesRemaining());

        FakeCardReaderPort exhausted = FakeCardReaderPort.pcsc("exhausted");
        exhausted.setCannedResponse(new ResponseAPDU(new byte[] {0x63, (byte) 0xC0})); // counter 0
        assertEquals(CardPinStatusReader.State.BLOCKED,
                new CardPinStatusReader(exhausted, SLOT).read(PIN_SMC).state());
    }

    @Test
    void maps_blocked_transport_disabled_and_empty_status_words() throws Exception {
        assertState(new byte[] {0x69, (byte) 0x83}, CardPinStatusReader.State.BLOCKED);
        assertState(new byte[] {0x69, (byte) 0x84}, CardPinStatusReader.State.TRANSPORT_PIN);
        assertState(new byte[] {0x69, (byte) 0x85}, CardPinStatusReader.State.DISABLED);
        assertState(new byte[] {0x6A, (byte) 0x88}, CardPinStatusReader.State.EMPTY_PIN);
        assertState(new byte[] {0x6F, 0x00}, CardPinStatusReader.State.ERROR);
    }

    private static void assertState(byte[] sw, CardPinStatusReader.State expected) throws Exception {
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("r");
        port.setCannedResponse(new ResponseAPDU(sw));
        assertEquals(expected, new CardPinStatusReader(port, SLOT).read(PIN_SMC).state());
    }
}
