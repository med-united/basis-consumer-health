package de.servicehealtherx.quarkus.sicct.runtime;

import de.servicehealtherx.sicct.jpa.CardTerminal;
import de.servicehealtherx.sicct.jpa.CorrelationState;
import io.netty.channel.Channel;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a single SICCT terminal TCP/TLS connection with state machine.
 * States: CONNECTING → CONNECTED → RECONNECTING → DISCONNECTED → FAILED.
 * Reference: FR-027, FR-134, FR-136.
 */
public class SicctTerminalConnection {

    private static final Logger LOG = Logger.getLogger(SicctTerminalConnection.class);

    public enum ConnectionState {
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        DISCONNECTED,
        FAILED
    }

    public enum TlsState {
        NO_SICCT_TLS,
        INVALID_CLIENT,
        CLIENT_WITHOUT_PAIRING,
        CLIENT_WITH_PAIRING
    }

    private final CardTerminal terminal;
    private final SicctTerminalManager manager;
    private final AtomicReference<ConnectionState> connectionState = new AtomicReference<>(ConnectionState.CONNECTING);
    private final AtomicReference<TlsState> tlsState = new AtomicReference<>(TlsState.NO_SICCT_TLS);

    /**
     * Set when an administrator explicitly disconnected the terminal (via JMX). While
     * true the manager must not auto-reconnect; cleared again on an explicit connect.
     */
    private final AtomicBoolean reconnectSuppressed = new AtomicBoolean(false);

    private volatile Channel channel;
    private volatile byte[] sessionKey;
    private SicctChannelHandler sicctChannelHandler;

    /** TUC_KON_050: role the session currently being established is opened for. */
    private volatile Role desiredRole = Role.USER;
    /** TUC_KON_050 step 6a: challenge sent in EHEALTH TERMINAL AUTHENTICATE VALIDATE. */
    private volatile byte[] sessionChallenge;
    /** True when the current sequence had to (re-)establish the TLS connection (step 10). */
    private volatile boolean tlsFreshlyEstablished;

    public SicctTerminalConnection(CardTerminal terminal) {
        this(terminal, null);
    }

    public SicctTerminalConnection(CardTerminal terminal, SicctTerminalManager manager) {
        this.terminal = terminal;
        this.manager = manager;
    }

    public String getTerminalId() {
        return terminal.hostname;
    }

    public ConnectionState getConnectionState() {
        return connectionState.get();
    }

    public TlsState getTlsState() {
        return tlsState.get();
    }

    public CorrelationState getCorrelationState() {
        return terminal.correlation;
    }

    public boolean isAktiv() {
        return terminal.correlation == CorrelationState.AKTIV;
    }

    public void onConnected(SicctChannelHandler sicctChannelHandler2, Channel ch) {
        setSicctChannelHandler(sicctChannelHandler2);
        this.channel = ch;
        setConnectionState(ConnectionState.CONNECTED);
        LOG.infof("[SICCT] terminal=%s CONNECTED", terminal.hostname);
    }

    public void onDisconnected() {
        // Discard session keys immediately on disconnect per FR-136
        if (sessionKey != null) {
            java.util.Arrays.fill(sessionKey, (byte) 0);
            sessionKey = null;
        }
        this.channel = null;
        if (connectionState.get() != ConnectionState.FAILED) {
            // An administrative disconnect terminates the connection for good (no
            // reconnect); an unsolicited drop moves to RECONNECTING so the manager
            // can re-establish it.
            setConnectionState(reconnectSuppressed.get()
                    ? ConnectionState.DISCONNECTED
                    : ConnectionState.RECONNECTING);
        }
        LOG.infof("[SICCT] terminal=%s session keys cleared", terminal.hostname);
    }

    public void onFailed(String reason) {
        setConnectionState(ConnectionState.FAILED);
        LOG.errorf("[SICCT] terminal=%s FAILED: %s", terminal.hostname, reason);
    }

    /** True while an administrator has disabled this terminal; suppresses auto-reconnect. */
    public boolean isReconnectSuppressed() {
        return reconnectSuppressed.get();
    }

    /** Re-enables auto-reconnect; called when an administrator explicitly connects. */
    public void clearReconnectSuppressed() {
        reconnectSuppressed.set(false);
    }

    /**
     * Records an administrative disconnect when there is no live channel to close
     * (e.g. the terminal was already offline). Marks the connection DISCONNECTED and
     * suppresses auto-reconnect.
     */
    public void markAdminDisconnected() {
        reconnectSuppressed.set(true);
        setConnectionState(ConnectionState.DISCONNECTED);
    }

    public void onTlsEstablished(boolean hasPairing) {
        tlsState.set(hasPairing ? TlsState.CLIENT_WITH_PAIRING : TlsState.CLIENT_WITHOUT_PAIRING);
    }

    public void onCtSessionInit() {
        // A terminal that is merely BEKANNT becomes ZUGEWIESEN once a CT session is
        // initialised. Terminals that are already further along the lifecycle
        // (GEPAIRT/AKTIV) keep their state across a reconnect.
        if (terminal.correlation == CorrelationState.BEKANNT) {
            setCorrelation(CorrelationState.ZUGEWIESEN);
        }
    }

    public void onPaired() {
        setCorrelation(CorrelationState.GEPAIRT);
        tlsState.set(TlsState.CLIENT_WITH_PAIRING);
    }

    public void onAktiv() {
        setCorrelation(CorrelationState.AKTIV);
        LOG.infof("[SICCT] terminal=%s AKTIV — ready for card operations", terminal.hostname);
    }

    /** KSR/UPDATE/START — the Konnektor started an update of this terminal. */
    public void onUpdateStart() {
        setCorrelation(CorrelationState.AKTUALISIEREND);
        LOG.infof("[SICCT] terminal=%s AKTUALISIEREND — update in progress", terminal.hostname);
    }

    /** KSR/UPDATE/END — the update finished; the terminal returns to AKTIV. */
    public void onUpdateEnd() {
        setCorrelation(CorrelationState.AKTIV);
        LOG.infof("[SICCT] terminal=%s update finished, back to AKTIV", terminal.hostname);
    }

    /**
     * Advances the transport state machine (TCP/TLS). This is distinct from the
     * TUC_KON_050 {@code CT.CONNECTED} session flag: per the TUC, {@code CONNECTED}
     * may only become {@code true} after a successful EHEALTH TERMINAL AUTHENTICATE
     * VALIDATE (step 9). Losing the transport, however, always invalidates the
     * session, so any non-{@link ConnectionState#CONNECTED} transport state clears it.
     */
    private void setConnectionState(ConnectionState newState) {
        ConnectionState previous = connectionState.getAndSet(newState);
        if (newState != ConnectionState.CONNECTED) {
            // Transport down/coming up: the authenticated session (if any) is gone.
            terminal.connected = false;
        }
        if (previous != newState) {
            LOG.infof("[SICCT] terminal=%s connection %s -> %s", terminal.hostname, previous, newState);
        }
        persist();
    }

    // -------------------------------------------------------------------------
    // TUC_KON_050 session state
    // -------------------------------------------------------------------------

    public Role getDesiredRole() {
        return desiredRole;
    }

    public void setDesiredRole(Role role) {
        this.desiredRole = role != null ? role : Role.USER;
    }

    public byte[] getSessionChallenge() {
        return sessionChallenge;
    }

    public void setSessionChallenge(byte[] challenge) {
        this.sessionChallenge = challenge;
    }

    public boolean isTlsFreshlyEstablished() {
        return tlsFreshlyEstablished;
    }

    public void setTlsFreshlyEstablished(boolean fresh) {
        this.tlsFreshlyEstablished = fresh;
    }

    /**
     * TUC_KON_050 step 9: the session was authenticated. Records the active role and
     * marks the terminal usable ({@code CT.CONNECTED = Ja}).
     */
    public void markSessionEstablished(Role role) {
        this.desiredRole = role;
        terminal.activeRole = role.name();
        terminal.connected = true;
        LOG.infof("[TUC_KON_050] terminal=%s session established (CONNECTED=Ja, ACTIVEROLE=%s)",
                terminal.hostname, role);
        persist();
    }

    /**
     * TUC_KON_050 step 4b / failed authentication: the terminal is reachable but not
     * usable for card operations ({@code CT.CONNECTED = Nein}).
     */
    public void markSessionNotUsable() {
        terminal.activeRole = null;
        terminal.connected = false;
        persist();
    }

    /** TUC_KON_050 step 11: records the slots currently holding a card. */
    public void setSlotsUsed(String slotsUsed) {
        terminal.slotsUsed = slotsUsed;
        persist();
    }

    /**
     * Mutates the single source of truth — the {@link CardTerminal} entity — for the
     * correlation lifecycle and persists it.
     */
    private void setCorrelation(CorrelationState newState) {
        CorrelationState previous = terminal.correlation;
        terminal.correlation = newState;
        if (previous != newState) {
            LOG.infof("[SICCT] terminal=%s correlation %s -> %s", terminal.hostname, previous, newState);
        }
        persist();
    }

    /**
     * Asks the manager to persist the terminal's lifecycle state (correlation +
     * connected) so the in-memory and database state stay in sync. Best-effort: a
     * failure leaves the live in-memory state correct and is logged by the manager.
     */
    private void persist() {
        if (manager != null) {
            manager.persistLifecycleState(terminal);
        }
    }

    public void setSessionKey(byte[] key) {
        this.sessionKey = key;
    }

    public Channel getChannel() {
        return channel;
    }

    public CardTerminal getTerminal() {
        return terminal;
    }

    public int getApduTimeoutMs() {
        return 30_000;
    }

    public java.util.concurrent.CompletableFuture<Boolean> pairTerminal() {
        if (getSicctChannelHandler() == null) {
            LOG.errorf("[SicctTerminalConnection] Cannot pair terminal=%s because SicctChannelHandler is not set yet",
                    terminal.hostname);
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("SicctChannelHandler not set for terminal=" + terminal.hostname));
        }
        return getSicctChannelHandler().ehealthTerminalAuthenticateCreate();
    }

    /**
     * TUC_KON_053 step 8: sends SICCT CLOSE CT SESSION to the terminal to end the
     * cardterminal session opened for pairing. No-op when no channel is established.
     */
    public void closeCtSession() {
        SicctChannelHandler handler = getSicctChannelHandler();
        if (handler == null) {
            LOG.warnf("[SicctTerminalConnection] Cannot close CT session for terminal=%s: no SICCT channel",
                    terminal.hostname);
            return;
        }
        handler.closeCtSession();
    }

    public void setSicctChannelHandler(SicctChannelHandler sicctChannelHandler) {
        this.sicctChannelHandler = sicctChannelHandler;
    }

    public SicctChannelHandler getSicctChannelHandler() {
        return sicctChannelHandler;
    }
}
