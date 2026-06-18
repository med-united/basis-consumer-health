package de.servicehealtherx.cetp;

/**
 * Type of a konnektor system event (gemSpec_Kon TAB_KON_030, TUC_KON_256).
 * Mirrors {@code de.gematik.ws.conn.eventservice.v7.EventType}.
 */
public enum EventType {
    Operation,
    Security,
    Infrastructure
}
