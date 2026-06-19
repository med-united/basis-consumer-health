package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.transport.FakeCardReaderPort;
import de.servicehealtherx.apdu.model.GematikISO7816;

class CardPinVerifierTest {

    private static final int SLOT = 1;
    private static final int PIN_SMC = 0x01;

    @Test
    void sends_verify_apdu_with_format2_pin_block_for_pin_smc() throws Exception {
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader");
        AtomicReference<CommandAPDU> sent = new AtomicReference<>();
        port.setResponder((slot, cmd) -> {
            sent.set(cmd);
            return new ResponseAPDU(new byte[] {(byte) 0x90, 0x00});
        });

        CardPinVerifier.Result result = new CardPinVerifier(port, SLOT).verify(PIN_SMC, "123456");

        assertEquals(CardPinVerifier.Outcome.VERIFIED, result.outcome());
        CommandAPDU cmd = sent.get();
        assertEquals(GematikISO7816.CLA_ISO, cmd.getCLA());
        assertEquals(GematikISO7816.INS_VERIFY, cmd.getINS());
        assertEquals(0x00, cmd.getP1());
        assertEquals(PIN_SMC, cmd.getP2());
        // ISO 9564 format-2 block for "123456": 0x26 12 34 56 FF FF FF FF.
        assertArrayEquals(
                new byte[] {0x26, 0x12, 0x34, 0x56, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                cmd.getData());
    }

    @Test
    void maps_wrong_pin_status_word_to_remaining_tries() throws Exception {
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader");
        port.setCannedResponse(new ResponseAPDU(new byte[] {0x63, (byte) 0xC2})); // 2 tries left

        CardPinVerifier.Result result = new CardPinVerifier(port, SLOT).verify(PIN_SMC, "999999");

        assertEquals(CardPinVerifier.Outcome.WRONG, result.outcome());
        assertEquals(2, result.triesRemaining());
    }

    @Test
    void maps_blocked_and_transport_status_words() throws Exception {
        FakeCardReaderPort blocked = FakeCardReaderPort.pcsc("blocked");
        blocked.setCannedResponse(new ResponseAPDU(new byte[] {0x69, (byte) 0x83}));
        assertEquals(CardPinVerifier.Outcome.BLOCKED,
                new CardPinVerifier(blocked, SLOT).verify(PIN_SMC, "123456").outcome());

        FakeCardReaderPort transport = FakeCardReaderPort.pcsc("transport");
        transport.setCannedResponse(new ResponseAPDU(new byte[] {0x69, (byte) 0x84}));
        assertEquals(CardPinVerifier.Outcome.TRANSPORT_PIN,
                new CardPinVerifier(transport, SLOT).verify(PIN_SMC, "123456").outcome());
    }
}
