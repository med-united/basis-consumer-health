package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.model.CardType;

/**
 * Unit tests for {@link CardListAggregator} (US3 tasks T028/T029; FR-062, FR-064, FR-065, FR-067).
 * Two separate {@link CmCardList} instances stand in for the PC/SC and SICCT providers' own lists.
 */
class CardListAggregatorTest {

    private static CardObject card(UUID ctid, int slot, CardType type) {
        return CardObject.builder().ctid(ctid).slotNo(slot).type(type).insertTime(Instant.now()).build();
    }

    @Test
    void test_FR_064_get_cards_aggregates_both_providers_lists() {
        CmCardList pcsc = new CmCardList();
        CmCardList sicct = new CmCardList();
        CardObject viaPcsc = card(UUID.randomUUID(), 1, CardType.EGK);
        CardObject viaSicct = card(UUID.randomUUID(), 2, CardType.SMC_B);
        pcsc.add(viaPcsc);
        sicct.add(viaSicct);

        CardListAggregator aggregator = new CardListAggregator().addSource(pcsc).addSource(sicct);

        assertEquals(2, aggregator.findAll().size());
        assertTrue(aggregator.findAll().contains(viaPcsc));
        assertTrue(aggregator.findAll().contains(viaSicct));
    }

    @Test
    void test_FR_065_resolve_owner_routes_handle_to_its_provider_list() {
        CmCardList pcsc = new CmCardList();
        CmCardList sicct = new CmCardList();
        CardObject viaSicct = card(UUID.randomUUID(), 2, CardType.SMC_B);
        pcsc.add(card(UUID.randomUUID(), 1, CardType.EGK));
        sicct.add(viaSicct);

        CardListAggregator aggregator = new CardListAggregator().addSource(pcsc).addSource(sicct);

        assertSame(viaSicct, aggregator.findByHandle(viaSicct.cardHandle()).orElseThrow());
        assertSame(sicct, aggregator.resolveOwner(viaSicct.cardHandle()).orElseThrow(),
                "handle resolves to exactly the SICCT provider's list");
    }

    @Test
    void test_FR_067_each_handle_appears_in_exactly_one_list() {
        CmCardList pcsc = new CmCardList();
        CmCardList sicct = new CmCardList();
        pcsc.add(card(UUID.randomUUID(), 1, CardType.EGK));
        sicct.add(card(UUID.randomUUID(), 1, CardType.EGK));
        CardListAggregator aggregator = new CardListAggregator(java.util.List.of(pcsc, sicct));

        long distinct = aggregator.findAll().stream().map(CardObject::cardHandle).distinct().count();
        assertEquals(2, distinct, "no handle duplicated across the union");
    }
}
