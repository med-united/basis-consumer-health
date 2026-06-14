package de.servicehealtherx.crypto.pcsc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.CardLifecycleListener;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CardObjectFactory;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.model.CardType;

/**
 * Tests PC/SC insertion parity and reader-set reconciliation (US2 tasks T021/T026; FR-002, FR-068,
 * FR-069, SC-016). Uses an injectable terminal source so no PC/SC subsystem is required.
 */
class PcscInsertionTest {

    private PcscReaderRegistry registry(CmCardList list, List<PcscTerminal> terminals) {
        return new PcscReaderRegistry(list, new CardObjectFactory(), CardLifecycleListener.NO_OP,
                (port, slot) -> CardType.EGK, () -> terminals);
    }

    @Test
    void test_FR_002_insertion_creates_card_object_with_synthesized_ctid() {
        FakePcscTerminal terminal = new FakePcscTerminal("PCSC Reader 0", true);
        CmCardList list = new CmCardList();
        PcscReaderRegistry registry = registry(list, List.of(terminal));

        registry.refreshTerminals(); // startup scan registers + picks up the present card

        assertEquals(1, list.size());
        CardObject card = list.findBySlot(PcscCardReaderPort.synthesizeCtid("PCSC Reader 0"),
                PcscCardReaderPort.PCSC_SLOT).orElseThrow();
        assertEquals(CardType.EGK, card.type());
        assertEquals(PcscCardReaderPort.synthesizeCtid("PCSC Reader 0"), card.ctid());
        assertNotNull(card.cardHandle());
        assertNotNull(card.cardVersion());
    }

    @Test
    void test_SC_016_pcsc_card_object_field_structure_matches_sicct_shape() {
        FakePcscTerminal terminal = new FakePcscTerminal("PCSC Reader 0", true);
        CmCardList list = new CmCardList();
        registry(list, List.of(terminal)).refreshTerminals();

        CardObject card = list.findAll().get(0);
        // Same fields/types a SICCT-originated CardObject would carry; differ only by ctid source.
        assertNotNull(card.cardHandle());
        assertNotNull(card.insertTime());
        assertEquals("1234", card.iccsn()); // from canned EF.GDO
        assertNotNull(card.certStatus());
        assertNotNull(card.certOcspResponse());
    }

    @Test
    void test_FR_069_unplugged_reader_invalidates_all_its_cards() {
        FakePcscTerminal terminal = new FakePcscTerminal("PCSC Reader 0", true);
        java.util.List<PcscTerminal> source = new java.util.ArrayList<>(List.of(terminal));
        CmCardList list = new CmCardList();
        PcscReaderRegistry registry = new PcscReaderRegistry(list, new CardObjectFactory(),
                CardLifecycleListener.NO_OP, (port, slot) -> CardType.EGK, () -> source);

        registry.refreshTerminals();
        assertEquals(1, list.size());

        source.clear(); // reader unplugged
        registry.refreshTerminals();

        assertEquals(0, list.size(), "all cards for the unplugged reader invalidated (FR-069)");
        assertEquals(0, registry.activeReaderCount());
    }

    @Test
    void test_FR_069_replug_rebuilds_with_fresh_handle() {
        FakePcscTerminal terminal = new FakePcscTerminal("PCSC Reader 0", true);
        java.util.List<PcscTerminal> source = new java.util.ArrayList<>(List.of(terminal));
        CmCardList list = new CmCardList();
        PcscReaderRegistry registry = new PcscReaderRegistry(list, new CardObjectFactory(),
                CardLifecycleListener.NO_OP, (port, slot) -> CardType.EGK, () -> source);

        registry.refreshTerminals();
        String firstHandle = list.findAll().get(0).cardHandle();

        source.clear();
        registry.refreshTerminals();      // unplug
        source.add(terminal);
        registry.refreshTerminals();       // replug → fresh handle

        assertEquals(1, list.size());
        String secondHandle = list.findAll().get(0).cardHandle();
        assertFalse(firstHandle.equals(secondHandle), "replug yields a new cardHandle");
        assertTrue(true);
    }
}
