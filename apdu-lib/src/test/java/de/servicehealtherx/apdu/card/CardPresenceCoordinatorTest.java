package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.smartcardio.ResponseAPDU;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.transport.FakeCardReaderPort;
import de.servicehealtherx.apdu.model.CardType;

/**
 * Unit tests for {@link CardPresenceCoordinator} (US1 tasks T015/T017; FR-001, FR-018, FR-046,
 * FR-047). Drives the fake transport port to insert/remove and asserts CM_CARD_LIST + lifecycle
 * notifications — the transport-agnostic core both real providers reuse (SC-018).
 */
class CardPresenceCoordinatorTest {

    private static final class RecordingListener implements CardLifecycleListener {
        final List<String> inserted = new ArrayList<>();
        final List<String> removed = new ArrayList<>();

        @Override
        public void onCardInserted(CardObject card) {
            inserted.add(card.cardHandle());
        }

        @Override
        public void onCardRemoved(CardObject card) {
            removed.add(card.cardHandle());
        }
    }

    private static FakeCardReaderPort portWithCard() {
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader-A");
        // canned EF.GDO with a short ICCSN so readAndCreate succeeds
        port.setCannedResponse(new ResponseAPDU(new byte[] {0x5A, 0x02, 0x12, 0x34, (byte) 0x90, 0x00}));
        return port;
    }

    @Test
    void test_FR_001_insertion_creates_card_object_and_fires_listener() {
        FakeCardReaderPort port = portWithCard();
        CmCardList list = new CmCardList();
        RecordingListener listener = new RecordingListener();
        CardPresenceCoordinator coordinator = new CardPresenceCoordinator(
                port, list, new CardObjectFactory(), listener, (p, slot) -> CardType.EGK);
        coordinator.start();

        port.simulateInsert(1);

        assertEquals(1, list.size());
        CardObject card = list.findBySlot(port.ctid(), 1).orElseThrow();
        assertEquals(CardType.EGK, card.type());
        assertEquals(List.of(card.cardHandle()), listener.inserted);
    }

    @Test
    void test_FR_018_removal_invalidates_handle_and_fires_listener() {
        FakeCardReaderPort port = portWithCard();
        CmCardList list = new CmCardList();
        RecordingListener listener = new RecordingListener();
        CardPresenceCoordinator coordinator = new CardPresenceCoordinator(
                port, list, new CardObjectFactory(), listener, (p, slot) -> CardType.EGK);
        coordinator.start();
        port.simulateInsert(1);
        String handle = list.findBySlot(port.ctid(), 1).orElseThrow().cardHandle();

        port.simulateRemove(1);

        assertEquals(0, list.size());
        assertFalse(list.findByHandle(handle).isPresent());
        assertEquals(List.of(handle), listener.removed);
        assertTrue(list.isBlacklisted(handle), "removed handle is held back from reuse (FR-003)");
    }

    @Test
    void test_stop_unsubscribes_from_port_events() {
        FakeCardReaderPort port = portWithCard();
        CmCardList list = new CmCardList();
        CardPresenceCoordinator coordinator = new CardPresenceCoordinator(
                port, list, new CardObjectFactory(), CardLifecycleListener.NO_OP, (p, slot) -> CardType.HBA);
        coordinator.start();
        coordinator.stop();

        port.simulateInsert(1);

        assertEquals(0, list.size(), "no handle created after stop()");
    }
}
