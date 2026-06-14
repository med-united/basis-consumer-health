package de.servicehealtherx.crypto.pcsc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.model.CardType;

/**
 * Tests EjectCard on a PC/SC reader without mechanical throwout (US2 task T022; FR-070, FR-071).
 */
class PcscEjectTest {

    private final PcscEjectHandler handler = new PcscEjectHandler();

    private static PcscCardReaderPort pcscPort() {
        return new PcscCardReaderPort(new FakePcscTerminal("PCSC Reader 0", true));
    }

    private static CardObject cardFor(PcscCardReaderPort port) {
        return CardObject.builder()
                .ctid(port.ctid()).slotNo(PcscCardReaderPort.PCSC_SLOT)
                .type(CardType.EGK).insertTime(Instant.now()).build();
    }

    @Test
    void test_FR_070_logical_eject_returns_ok_without_4203() {
        PcscCardReaderPort port = pcscPort();
        CmCardList list = new CmCardList();
        CardObject card = cardFor(port);
        list.add(card);

        PcscEjectHandler.EjectResult result = handler.ejectByHandle(list, port, card.cardHandle());

        assertTrue(result.success());
        assertTrue(result.logical(), "PC/SC reader has no mechanical throwout → logical eject");
        assertNull(result.errorCode(), "no error 4203 for logical eject");
        assertFalse(list.findByHandle(card.cardHandle()).isPresent(), "handle invalidated");
    }

    @Test
    void test_FR_070_unknown_handle_returns_4101() {
        PcscCardReaderPort port = pcscPort();
        CmCardList list = new CmCardList();

        PcscEjectHandler.EjectResult result = handler.ejectByHandle(list, port, "no-such-handle");

        assertFalse(result.success());
        assertEquals(PcscEjectHandler.ERR_INVALID_HANDLE, result.errorCode());
    }
}
