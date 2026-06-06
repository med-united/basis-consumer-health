package de.servicehealtherx.quarkus.sicct.runtime;

import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.netty.channel.Channel;
import org.jboss.logging.Logger;

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

    public enum CorrelationState {
        BEKANNT,
        ZUGEWIESEN,
        GEPAIRT,
        AKTIV
    }

    private final CardTerminal terminal;
    private final AtomicReference<ConnectionState> connectionState =
        new AtomicReference<>(ConnectionState.CONNECTING);
    private final AtomicReference<TlsState> tlsState =
        new AtomicReference<>(TlsState.NO_SICCT_TLS);
    private final AtomicReference<CorrelationState> correlationState =
        new AtomicReference<>(CorrelationState.BEKANNT);

    private volatile Channel channel;
    private volatile byte[] sessionKey;

    public SicctTerminalConnection(CardTerminal terminal) {
        this.terminal = terminal;
    }

    public String getTerminalId() {
        return terminal.terminalId;
    }

    public ConnectionState getConnectionState() {
        return connectionState.get();
    }

    public TlsState getTlsState() {
        return tlsState.get();
    }

    public CorrelationState getCorrelationState() {
        return correlationState.get();
    }

    public boolean isAktiv() {
        return correlationState.get() == CorrelationState.AKTIV;
    }

    public void onConnected(Channel ch) {
        this.channel = ch;
        connectionState.set(ConnectionState.CONNECTED);
        LOG.infof("[SICCT] terminal=%s CONNECTED", terminal.terminalId);
    }

    public void onDisconnected() {
        // Discard session keys immediately on disconnect per FR-136
        if (sessionKey != null) {
            java.util.Arrays.fill(sessionKey, (byte) 0);
            sessionKey = null;
        }
        this.channel = null;
        if (connectionState.get() != ConnectionState.FAILED) {
            connectionState.set(ConnectionState.RECONNECTING);
        }
        LOG.infof("[SICCT] terminal=%s DISCONNECTED, session keys cleared", terminal.terminalId);
    }

    public void onFailed(String reason) {
        connectionState.set(ConnectionState.FAILED);
        LOG.errorf("[SICCT] terminal=%s FAILED: %s", terminal.terminalId, reason);
    }

    public void onTlsEstablished(boolean hasPairing) {
        tlsState.set(hasPairing ? TlsState.CLIENT_WITH_PAIRING : TlsState.CLIENT_WITHOUT_PAIRING);
    }

    public void onCtSessionInit() {
        correlationState.set(CorrelationState.ZUGEWIESEN);
    }

    public void onPaired() {
        correlationState.set(CorrelationState.GEPAIRT);
        tlsState.set(TlsState.CLIENT_WITH_PAIRING);
    }

    public void onAktiv() {
        correlationState.set(CorrelationState.AKTIV);
        LOG.infof("[SICCT] terminal=%s AKTIV — ready for card operations", terminal.terminalId);
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
        return terminal.apduTimeoutMs;
    }
}
