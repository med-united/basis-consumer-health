package de.servicehealtherx.crypto.sicct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void test_runExclusively_serialises_concurrent_operations_on_the_same_terminal() throws Exception {
        // A single SICCT terminal multiplexes background slot discovery and foreground crypto onto
        // the same cards, so logical operations on it must not interleave (a discovery SELECT must
        // not land between a cert read's SELECT and READ). Two ops on one terminal must run serially.
        SicctCardReaderPort port = new SicctCardReaderPort(new FakeSicctChannel());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        Runnable op = () -> {
            try {
                port.runExclusively(() -> {
                    maxObserved.accumulateAndGet(active.incrementAndGet(), Math::max);
                    sleepQuietly(150);
                    active.decrementAndGet();
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Thread t1 = new Thread(op);
        Thread t2 = new Thread(op);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertEquals(1, maxObserved.get(), "operations on one terminal must not run concurrently");
    }

    @Test
    void test_runExclusively_does_not_serialise_across_different_terminals() throws Exception {
        // The lock is per terminal (CtID), not global: two different terminals must run concurrently.
        // The barrier (reached inside each runExclusively) only trips if both ops are inside at once;
        // a global lock would deadlock it, failing the test.
        SicctCardReaderPort a = new SicctCardReaderPort(new FakeSicctChannel());
        SicctCardReaderPort b = new SicctCardReaderPort(new FakeSicctChannel());
        CyclicBarrier bothInside = new CyclicBarrier(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        java.util.function.Consumer<SicctCardReaderPort> op = port -> {
            try {
                port.runExclusively(() -> {
                    awaitQuietly(bothInside);
                    maxObserved.accumulateAndGet(active.incrementAndGet(), Math::max);
                    sleepQuietly(50);
                    active.decrementAndGet();
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Thread t1 = new Thread(() -> op.accept(a));
        Thread t2 = new Thread(() -> op.accept(b));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertEquals(2, maxObserved.get(), "different terminals must be able to run concurrently");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
