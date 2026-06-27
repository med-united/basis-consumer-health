package de.servicehealtherx.quarkus.sicct.runtime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.logging.Logger;

import de.servicehealtherx.apdu.card.CardLifecycleListener;
import de.servicehealtherx.apdu.card.CardObjectFactory;
import de.servicehealtherx.apdu.card.CardPresenceCoordinator;
import de.servicehealtherx.crypto.sicct.SicctCardReaderPort;
import de.servicehealtherx.crypto.sicct.SicctCryptoProvider;
import de.servicehealtherx.sicct.codec.IccStatusDecoder.IccStatusValue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Runtime integration that turns SICCT slot-status into card handles (the "Phase 6 / runtime
 * integration" the {@code SicctCryptoProvider} javadoc defers): for each connected, validly-paired
 * terminal it owns a {@link SicctCardReaderPort} + {@link CardPresenceCoordinator} that drive the
 * provider's CM_CARD_LIST via {@link CardObjectFactory} (TUC_KON_001), exactly as the PC/SC
 * {@code PcscReaderRegistry} does. It also binds the provider's port resolver so card-handle
 * addressed crypto operations resolve the live terminal by {@code ctid}.
 */
@ApplicationScoped
public class SicctCardDiscovery {

    private static final Logger LOG = Logger.getLogger(SicctCardDiscovery.class);

    @Inject
    SicctCryptoProvider provider;

    /**
     * Optional runtime card-lifecycle listener (CDI), used to publish TUC_KON_256 CARD/INSERTED &
     * CARD/REMOVED events. Falls back to {@link CardLifecycleListener#NO_OP} when no bean is present.
     */
    @Inject
    Instance<CardLifecycleListener> lifecycleListener;

    /** One CardReaderPort per terminal (ctid); also the resolver target for crypto operations. */
    private final ConcurrentHashMap<UUID, SicctCardReaderPort> portsByCtid = new ConcurrentHashMap<>();

    /** Card reads block on the SICCT round-trip, so discovery runs off the Netty event loop. */
    private final ExecutorService discoveryExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sicct-card-discovery");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void bindPortResolver() {
        provider.bindPortResolver(ctid -> Optional.ofNullable(portsByCtid.get(ctid)));
    }

    /**
     * Builds/refreshes card handles for the inserted ICCs of a connected, validly-paired terminal,
     * from a GET STATUS ALL ICC result (one entry per 0-based ICC index). Each slot is handled on a
     * worker thread because reading the card (ICCSN, type, …) issues blocking APDU round-trips.
     * <p>
     * Card slot numbers are <b>1-based</b> (slot 1 = first ICC), matching the PC/SC reader registry
     * and the {@link de.servicehealtherx.apdu.card.CardObject} contract ({@code slotNo >= 1}); the
     * raw GET STATUS list is 0-based, so ICC index {@code i} is slot {@code i + 1}. Passing the
     * 0-based index here is what previously made slot 0 fail with "slotNo must be >= 1".
     */
    public void discoverCards(SicctTerminalConnection connection, List<IccStatusValue> iccStatus) {
        SicctCardReaderPort port = portFor(connection);
        for (int slot = 0; slot < iccStatus.size(); slot++) {
            int slotNo = slot + 1;
            boolean present = iccStatus.get(slot) != IccStatusValue.CC_ABSENT;
            discoveryExecutor.execute(() -> {
                try {
                    // Reading the inserted card's type/ICCSN issues a multi-APDU SELECT+READ sequence;
                    // run it under the terminal lock so it does not interleave with a concurrent
                    // foreground crypto operation on the same card (which would clobber its selected
                    // file — e.g. a cert read's SELECT DF.ESIGN being reset by discovery's SELECT MF).
                    port.runExclusively(() -> {
                        if (present) {
                            // Idempotent re-discovery: a SICCT terminal re-runs full card discovery on
                            // every session (re)establish, and the Konnektor establishes several
                            // sessions. Rebuilding a still-present card would remove its CardObject and
                            // mint a fresh CardHandle (createAndRegister → removeBySlot +
                            // generateCardHandle), invalidating a handle a client is mid-operation with
                            // ("No card found for handle ..."). Keep the existing handle for an
                            // already-known slot; only build when the slot is newly occupied. A genuine
                            // card change still re-enumerates: removal first fires onCardRemoved (below),
                            // clearing the slot, so the next insert builds a fresh handle.
                            if (provider.cmCardList().findBySlot(port.ctid(), slotNo).isEmpty()) {
                                port.onCardInserted(slotNo);
                            }
                        } else {
                            port.onCardRemoved(slotNo);
                        }
                        return null;
                    });
                } catch (Exception e) {
                    LOG.warnf(e, "[SICCT] card discovery failed on slot=%d of terminal=%s",
                            slotNo, connection.getTerminalId());
                }
            });
        }
    }

    /** Drops a terminal's port when it disconnects so crypto operations fail fast (best-effort). */
    public void removeTerminal(UUID ctid) {
        if (ctid != null) {
            portsByCtid.remove(ctid);
        }
    }

    private SicctCardReaderPort portFor(SicctTerminalConnection connection) {
        UUID ctid = connection.getTerminal().ctid;
        return portsByCtid.computeIfAbsent(ctid, id -> {
            SicctCardReaderPort port = new SicctCardReaderPort(new SicctConnectionChannel(connection));
            CardLifecycleListener listener = lifecycleListener != null && lifecycleListener.isResolvable()
                    ? lifecycleListener.get()
                    : CardLifecycleListener.NO_OP;
            CardPresenceCoordinator coordinator = new CardPresenceCoordinator(
                    port, provider.cmCardList(), new CardObjectFactory(), listener,
                    CardPresenceCoordinator.defaultTypeResolver());
            coordinator.start();
            return port;
        });
    }

    @PreDestroy
    void shutdown() {
        discoveryExecutor.shutdownNow();
    }
}
