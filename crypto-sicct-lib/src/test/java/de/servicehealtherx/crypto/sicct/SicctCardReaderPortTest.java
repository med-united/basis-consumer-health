package de.servicehealtherx.crypto.sicct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.CardLifecycleListener;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CardObjectFactory;
import de.servicehealtherx.apdu.card.CardPresenceCoordinator;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.CardType;

/**
 * Tests {@link SicctCardReaderPort} drives the shared transport-agnostic core symmetrically to the
 * PC/SC port (US3 tasks T032/T033; FR-062, FR-063, SC-018). Uses a fake {@link SicctChannel} — no
 * Netty/SICCT terminal required.
 */
class SicctCardReaderPortTest {

    private static final class FakeSicctChannel implements SicctChannel {
        private final UUID ctid = UUID.randomUUID();

        @Override
        public UUID ctid() {
            return ctid;
        }

        @Override
        public String name() {
            return "SICCT-KT-01";
        }

        @Override
        public boolean isCardPresent(int slotNo) {
            return true;
        }

        @Override
        public ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException {
            return new ResponseAPDU(new byte[] {0x5A, 0x02, 0x12, 0x34, (byte) 0x90, 0x00});
        }
    }

    @Test
    void test_sicct_reader_has_display_and_mechanical_eject() {
        SicctCardReaderPort port = new SicctCardReaderPort(new FakeSicctChannel());
        assertTrue(port.capabilities().hasDisplay());
        assertTrue(port.capabilities().hasMechanicalEject());
    }

    @Test
    void test_FR_062_FR_063_sicct_insertion_event_creates_card_in_its_own_list() {
        FakeSicctChannel channel = new FakeSicctChannel();
        SicctCardReaderPort port = new SicctCardReaderPort(channel);
        CmCardList sicctList = new CmCardList();
        CardPresenceCoordinator coordinator = new CardPresenceCoordinator(
                port, sicctList, new CardObjectFactory(), CardLifecycleListener.NO_OP,
                (p, slot) -> CardType.SMC_B);
        coordinator.start();

        port.onCardInserted(1); // SICCT runtime delivers CT/SLOT_IN_USE

        assertEquals(1, sicctList.size());
        CardObject card = sicctList.findBySlot(channel.ctid(), 1).orElseThrow();
        assertEquals(CardType.SMC_B, card.type());
        assertEquals("1234", card.iccsn());
    }

    @Test
    void test_FR_018_sicct_removal_event_invalidates_card() {
        FakeSicctChannel channel = new FakeSicctChannel();
        SicctCardReaderPort port = new SicctCardReaderPort(channel);
        CmCardList sicctList = new CmCardList();
        new CardPresenceCoordinator(port, sicctList, new CardObjectFactory(),
                CardLifecycleListener.NO_OP, (p, slot) -> CardType.SMC_B).start();
        port.onCardInserted(1);

        port.onCardRemoved(1);

        assertEquals(0, sicctList.size());
    }
}
