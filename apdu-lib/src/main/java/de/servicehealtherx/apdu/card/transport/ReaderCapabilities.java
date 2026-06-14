package de.servicehealtherx.apdu.card.transport;

/**
 * Capability flags a {@link CardReaderPort} exposes so that capability-gated steps degrade
 * gracefully (FR-063, FR-070, FR-071) rather than failing on readers that lack a feature.
 *
 * @param hasDisplay         reader can show a prompt (SICCT terminal: true; typical PC/SC: false)
 * @param hasMechanicalEject reader can physically eject a card (SICCT: true; typical PC/SC: false)
 * @param hasSlotSelection   reader supports addressing a specific slot
 */
public record ReaderCapabilities(boolean hasDisplay, boolean hasMechanicalEject, boolean hasSlotSelection) {

    /** Typical directly PC/SC-connected reader: no display, no mechanical eject, single slot. */
    public static ReaderCapabilities pcscDefault() {
        return new ReaderCapabilities(false, false, false);
    }

    /** Typical SICCT terminal: display, mechanical eject, slot selection. */
    public static ReaderCapabilities sicctDefault() {
        return new ReaderCapabilities(true, true, true);
    }
}
