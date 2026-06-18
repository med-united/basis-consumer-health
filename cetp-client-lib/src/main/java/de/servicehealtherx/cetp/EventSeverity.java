package de.servicehealtherx.cetp;

/**
 * Severity of a konnektor system event (gemSpec_Kon TAB_KON_030).
 * Mirrors {@code de.gematik.ws.conn.eventservice.v7.EventSeverityType}.
 */
public enum EventSeverity {
    Info,
    Warning,
    Error,
    Fatal
}
