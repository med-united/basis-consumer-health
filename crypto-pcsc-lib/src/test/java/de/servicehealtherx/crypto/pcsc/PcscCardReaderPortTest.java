package de.servicehealtherx.crypto.pcsc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;

/**
 * Unit tests for {@link PcscCardReaderPort} (US2 task T020; FR-068, FR-001).
 */
class PcscCardReaderPortTest {

    private static final class Rec implements CardReaderPort.PresenceListener {
        final List<Integer> inserted = new ArrayList<>();
        final List<Integer> removed = new ArrayList<>();

        @Override
        public void onCardInserted(int slotNo) {
            inserted.add(slotNo);
        }

        @Override
        public void onCardRemoved(int slotNo) {
            removed.add(slotNo);
        }
    }

    @Test
    void test_FR_068_ctid_is_stable_name_derived_uuid() {
        String name = "ACME PC/SC Reader 00 00";
        UUID expected = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));

        PcscCardReaderPort a = new PcscCardReaderPort(new FakePcscTerminal(name, false));
        PcscCardReaderPort b = new PcscCardReaderPort(new FakePcscTerminal(name, false));

        assertEquals(expected, a.ctid());
        assertEquals(a.ctid(), b.ctid(), "same reader name → same ctid across reconnects");
    }

    @Test
    void test_pcsc_reader_capabilities_default_to_no_display_no_eject() {
        PcscCardReaderPort port = new PcscCardReaderPort(new FakePcscTerminal("r", false));
        assertFalse(port.capabilities().hasDisplay());
        assertFalse(port.capabilities().hasMechanicalEject());
        assertFalse(port.capabilities().hasSlotSelection());
    }

    @Test
    void test_FR_001_poll_fires_insert_then_remove_on_presence_change() {
        FakePcscTerminal terminal = new FakePcscTerminal("r", false);
        PcscCardReaderPort port = new PcscCardReaderPort(terminal);
        Rec rec = new Rec();
        port.addPresenceListener(rec);

        port.poll(); // absent → no event
        terminal.setPresent(true);
        port.poll(); // insert
        port.poll(); // unchanged → no duplicate
        terminal.setPresent(false);
        port.poll(); // remove

        assertEquals(List.of(PcscCardReaderPort.PCSC_SLOT), rec.inserted);
        assertEquals(List.of(PcscCardReaderPort.PCSC_SLOT), rec.removed);
    }

    @Test
    void test_card_already_present_at_first_poll_is_an_insertion() {
        FakePcscTerminal terminal = new FakePcscTerminal("r", true);
        PcscCardReaderPort port = new PcscCardReaderPort(terminal);
        Rec rec = new Rec();
        port.addPresenceListener(rec);

        assertTrue(port.poll());
        assertEquals(List.of(PcscCardReaderPort.PCSC_SLOT), rec.inserted);
    }
}
