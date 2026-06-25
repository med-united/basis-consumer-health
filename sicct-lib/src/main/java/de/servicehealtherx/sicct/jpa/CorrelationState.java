package de.servicehealtherx.sicct.jpa;

/**
 * Korrelationsstatus eines Kartenterminals zum Konnektor (TUC_KON_050 / TUC_KON_053).
 *
 * <p>
 * This is the single, canonical representation of a terminal's correlation
 * state. It is persisted on {@link CardTerminal#correlation} and is the only
 * place the state is stored — the live {@code SicctTerminalConnection} reads and
 * writes it through the entity rather than keeping a second copy.
 */
public enum CorrelationState {

    /** Über Service Announcement / Service Discovery gelernte Kartenterminals. */
    BEKANNT,

    /**
     * Durch den Administrator aus dem Bereich der bekannten Kartenterminals
     * zugewiesen oder manuell konfigurierte Kartenterminals.
     */
    ZUGEWIESEN,

    /** Pairing erfolgreich, aber noch nicht zum Verbindungsaufbau freigegeben. */
    GEPAIRT,

    /** Durch den Administrator zum Verbindungsaufbau freigegeben. */
    AKTIV,

    /**
     * Laufender Updatevorgang, ausgelöst durch den Konnektor. Der Zustand tritt
     * ein, wenn der Kartenterminaldienst das Event „KSR/UPDATE/START" fängt und
     * endet mit dem Event „KSR/UPDATE/END".
     */
    AKTUALISIEREND
}
