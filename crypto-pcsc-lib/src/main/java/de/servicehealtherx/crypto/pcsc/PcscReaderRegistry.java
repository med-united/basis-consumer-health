package de.servicehealtherx.crypto.pcsc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.smartcardio.CardTerminal;
import javax.smartcardio.TerminalFactory;

import de.servicehealtherx.apdu.card.CardLifecycleListener;
import de.servicehealtherx.apdu.card.CardObjectFactory;
import de.servicehealtherx.apdu.card.CardPresenceCoordinator;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.model.CardType;

/**
 * Discovers directly connected PC/SC readers, registers a {@link PcscCardReaderPort} +
 * {@link CardPresenceCoordinator} per reader against the provider's {@link CmCardList}, and keeps
 * the set reconciled (US2 tasks T025/T026).
 *
 * <p>{@link #refreshTerminals()} performs the startup scan and reconnect/unplug reconciliation
 * (FR-069): a newly present reader is registered (replug rebuild), a vanished reader has all its
 * cards invalidated via {@link CmCardList#removeAllForTerminal(UUID)}. {@link #pollAll()} polls each
 * active reader for card insert/remove (FR-001). Both are driven by a scheduler in production and
 * called directly from tests. The {@code terminalSource} is injectable so tests need no PC/SC
 * subsystem.
 */
public final class PcscReaderRegistry {

    private final CmCardList cardList;
    private final CardObjectFactory factory;
    private final CardLifecycleListener listener;
    private final CardPresenceCoordinator.CardTypeResolver typeResolver;
    private final Supplier<List<PcscTerminal>> terminalSource;

    private final Map<UUID, ActiveReader> active = new ConcurrentHashMap<>();

    private record ActiveReader(PcscCardReaderPort port, CardPresenceCoordinator coordinator) {}

    public PcscReaderRegistry(CmCardList cardList, CardObjectFactory factory,
                              CardLifecycleListener listener,
                              CardPresenceCoordinator.CardTypeResolver typeResolver,
                              Supplier<List<PcscTerminal>> terminalSource) {
        this.cardList = Objects.requireNonNull(cardList, "cardList");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.listener = listener != null ? listener : CardLifecycleListener.NO_OP;
        this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
        this.terminalSource = Objects.requireNonNull(terminalSource, "terminalSource");
    }

    /** Default terminal source: enumerate the JDK PC/SC subsystem (empty if none/unavailable). */
    public static Supplier<List<PcscTerminal>> defaultTerminalSource() {
        return () -> {
            List<PcscTerminal> result = new ArrayList<>();
            try {
                List<CardTerminal> terminals = TerminalFactory.getDefault().terminals().list();
                for (CardTerminal t : terminals) {
                    result.add(new SmartcardioPcscTerminal(t));
                }
            } catch (Exception e) {
                // No PC/SC subsystem / no readers — return empty rather than failing startup.
            }
            return result;
        };
    }

    /** Reconcile the active reader set against currently connected readers (FR-069 scan/rebuild). */
    public synchronized void refreshTerminals() {
        List<PcscTerminal> terminals = terminalSource.get();
        java.util.Set<UUID> seen = new java.util.HashSet<>();

        for (PcscTerminal terminal : terminals) {
            UUID ctid = PcscCardReaderPort.synthesizeCtid(terminal.name());
            seen.add(ctid);
            active.computeIfAbsent(ctid, k -> register(terminal));
        }

        // Readers that vanished since last scan: invalidate all their cards (unplug).
        for (UUID ctid : new ArrayList<>(active.keySet())) {
            if (!seen.contains(ctid)) {
                ActiveReader removed = active.remove(ctid);
                if (removed != null) {
                    removed.coordinator().stop();
                    cardList.removeAllForTerminal(ctid);
                }
            }
        }
    }

    /** Poll every active reader once for card insert/remove (FR-001). */
    public void pollAll() {
        active.values().forEach(r -> r.port().poll());
    }

    /** Number of currently registered readers. */
    public int activeReaderCount() {
        return active.size();
    }

    /** The {@link PcscCardReaderPort} for a terminal id, or {@code null} if no such reader is active. */
    public PcscCardReaderPort portFor(UUID ctid) {
        ActiveReader reader = active.get(ctid);
        return reader == null ? null : reader.port();
    }

    private ActiveReader register(PcscTerminal terminal) {
        PcscCardReaderPort port = new PcscCardReaderPort(terminal);
        CardPresenceCoordinator coordinator =
                new CardPresenceCoordinator(port, cardList, factory, listener, typeResolver);
        coordinator.start();
        // Pick up a card already present at registration time (startup scan).
        port.poll();
        return new ActiveReader(port, coordinator);
    }

    /**
     * Default PC/SC card-type resolution: the transport-neutral AID-probing resolver shared with
     * the SICCT provider, which detects every known health card (eGK, HBA, SMC-B) and falls back to
     * {@link CardType#UNKNOWN}. See {@link CardPresenceCoordinator#defaultTypeResolver()}.
     */
    public static CardPresenceCoordinator.CardTypeResolver defaultTypeResolver() {
        return CardPresenceCoordinator.defaultTypeResolver();
    }
}
