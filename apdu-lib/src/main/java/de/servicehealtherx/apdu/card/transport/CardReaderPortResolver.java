package de.servicehealtherx.apdu.card.transport;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the live {@link CardReaderPort} for a terminal id ({@code ctid}). A CryptoProvider holds
 * one to turn a {@link de.servicehealtherx.apdu.card.CardObject}'s {@code ctid} into the port that
 * can transmit APDUs to that card — the seam the transport runtime wires once it owns the terminals.
 */
@FunctionalInterface
public interface CardReaderPortResolver {

    /** A resolver that knows no ports (no terminal bound yet). */
    CardReaderPortResolver NONE = ctid -> Optional.empty();

    Optional<CardReaderPort> portFor(UUID ctid);
}
