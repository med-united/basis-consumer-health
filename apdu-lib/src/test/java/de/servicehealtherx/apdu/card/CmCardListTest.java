package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.model.CardType;

/**
 * Unit tests for {@link CmCardList} (FR-060, FR-062, FR-067, FR-003). Verifies add/find/remove,
 * the (ctid, slotNo) index, duplicate rejection, and the 48-hour no-reuse blacklist — entirely
 * transport-neutral (no PC/SC or SICCT type involved).
 */
class CmCardListTest {

    private static CardObject card(UUID ctid, int slot, CardType type) {
        return CardObject.builder().ctid(ctid).slotNo(slot).type(type).insertTime(Instant.now()).build();
    }

    @Test
    void test_FR_060_add_and_find_by_handle_and_slot() {
        CmCardList list = new CmCardList();
        UUID ctid = UUID.randomUUID();
        CardObject c = card(ctid, 1, CardType.EGK);

        list.add(c);

        assertSame(c, list.findByHandle(c.cardHandle()).orElseThrow());
        assertSame(c, list.findBySlot(ctid, 1).orElseThrow());
        assertEquals(1, list.size());
    }

    @Test
    void test_FR_060_duplicate_card_handle_is_rejected() {
        CmCardList list = new CmCardList();
        CardObject c = card(UUID.randomUUID(), 1, CardType.HBA);
        list.add(c);

        CardObject dup = CardObject.builder()
                .cardHandle(c.cardHandle()).ctid(UUID.randomUUID()).slotNo(2).type(CardType.HBA)
                .insertTime(Instant.now()).build();

        assertThrows(IllegalStateException.class, () -> list.add(dup));
    }

    @Test
    void test_FR_003_invalidated_handle_is_blacklisted_for_reuse() {
        CmCardList list = new CmCardList();
        CardObject c = card(UUID.randomUUID(), 1, CardType.SMC_B);
        list.add(c);

        list.removeByHandle(c.cardHandle());

        assertTrue(list.isBlacklisted(c.cardHandle()));
        CardObject reuse = CardObject.builder()
                .cardHandle(c.cardHandle()).ctid(UUID.randomUUID()).slotNo(1).type(CardType.SMC_B)
                .insertTime(Instant.now()).build();
        assertThrows(IllegalStateException.class, () -> list.add(reuse));
    }

    @Test
    void test_FR_018_remove_by_slot_clears_the_entry() {
        CmCardList list = new CmCardList();
        UUID ctid = UUID.randomUUID();
        CardObject c = card(ctid, 3, CardType.EGK);
        list.add(c);

        assertTrue(list.removeBySlot(ctid, 3).isPresent());
        assertFalse(list.findByHandle(c.cardHandle()).isPresent());
        assertEquals(0, list.size());
    }

    @Test
    void test_FR_069_remove_all_for_terminal_invalidates_every_card() {
        CmCardList list = new CmCardList();
        UUID ctid = UUID.randomUUID();
        list.add(card(ctid, 1, CardType.EGK));
        list.add(card(ctid, 2, CardType.SMC_B));
        list.add(card(UUID.randomUUID(), 1, CardType.HBA)); // different terminal — must survive

        var removed = list.removeAllForTerminal(ctid);

        assertEquals(2, removed.size());
        assertEquals(1, list.size());
    }

    @Test
    void test_FR_067_handles_are_unique_per_instance() {
        CmCardList list = new CmCardList();
        CardObject a = card(UUID.randomUUID(), 1, CardType.EGK);
        CardObject b = card(UUID.randomUUID(), 1, CardType.EGK);

        list.add(a);
        list.add(b);

        assertFalse(a.cardHandle().equals(b.cardHandle()), "random UUID handles must differ");
        assertEquals(2, list.size());
    }
}
