package de.servicehealtherx.apdu.card;

import java.util.Objects;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.model.CardType;

/**
 * Builds a {@link CardObject} for an inserted card (TUC_KON_001 "Karte öffnen") and registers it
 * into a provider's {@link CmCardList}. Transport-neutral: it only talks to a {@link CardReaderPort},
 * so the same factory serves both the PC/SC and SICCT providers (FR-061, FR-063).
 *
 * <p>This Phase-2 foundation assembles a CardObject from already-resolved card attributes and the
 * reader's identity. Deep on-card attribute reading (EF.Version → {@link CardVersionInfo}; AUT
 * certificate → cardholder/KVNR/expiry) is layered on top in the US1 implementation (task T016)
 * via {@link #readAndCreate}.
 */
public final class CardObjectFactory {

    /**
     * Build a CardObject from resolved attributes and add it to {@code list}. The {@code ctid} and
     * {@code slotNo} come from the {@link CardReaderPort}; {@code cardHandle} is a fresh random UUID.
     *
     * @return the registered CardObject
     */
    public CardObject createAndRegister(CmCardList list, CardReaderPort port, int slotNo,
                                        CardType type, CardAttributes attributes) {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(type, "type");
        CardAttributes attrs = attributes != null ? attributes : CardAttributes.empty();

        CardObject card = CardObject.builder()
                .ctid(port.ctid())
                .slotNo(slotNo)
                .type(type)
                .iccsn(attrs.iccsn())
                .cardVersion(attrs.cardVersion())
                .cardHolderName(attrs.cardHolderName())
                .kvnr(type == CardType.EGK ? attrs.kvnr() : null) // KVNR only for eGK (FR-010)
                .certExpirationDate(attrs.certExpirationDate())
                .build();

        list.add(card);
        return card;
    }

    /**
     * Read on-card attributes via the transport port and create+register the CardObject.
     * <p>Placeholder for US1 task T016 — the EF.Version / AUT-certificate reading is implemented
     * there. Kept here so the call site is stable across the Phase-2 → US1 boundary.
     */
    public CardObject readAndCreate(CmCardList list, CardReaderPort port, int slotNo, CardType type) {
        // T016 (US1) fills in: transmit SELECT/READ BINARY for EF.Version, parse CARDVERSION,
        // read AUT certificate for cardHolderName/kvnr/certExpirationDate. For now, register with
        // empty attributes so the lifecycle and CM_CARD_LIST wiring is exercisable.
        return createAndRegister(list, port, slotNo, type, CardAttributes.empty());
    }

    /**
     * Card attributes resolved from the card surface. All fields nullable — an unreadable field is
     * stored as {@code null} rather than failing handle creation (FR-005, FR-007).
     */
    public record CardAttributes(
            String iccsn,
            CardVersionInfo cardVersion,
            String cardHolderName,
            String kvnr,
            java.time.LocalDate certExpirationDate) {

        public static CardAttributes empty() {
            return new CardAttributes(null, CardVersionInfo.empty(), null, null, null);
        }
    }
}
