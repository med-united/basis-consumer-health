package de.servicehealtherx.quarkus.sicct.runtime;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.crypto.sicct.SicctChannel;

/**
 * Production {@link SicctChannel} binding a {@code crypto-sicct-lib} card port to the runtime's
 * live {@link SicctTerminalConnection}. It wraps the <em>connection</em> (not the channel handler)
 * because the same connection instance is reused across reconnects while its
 * {@link SicctChannelHandler} is replaced — so each call resolves the current handler.
 *
 * <p>{@link #transmit} blocks the calling (worker) thread on the handler's async round-trip; it
 * must never be called from the Netty event loop (see {@link SicctChannelHandler#transmitCardApdu}).
 */
final class SicctConnectionChannel implements SicctChannel {

    private final SicctTerminalConnection connection;

    SicctConnectionChannel(SicctTerminalConnection connection) {
        this.connection = connection;
    }

    @Override
    public UUID ctid() {
        return connection.getTerminal().ctid;
    }

    @Override
    public String name() {
        return connection.getTerminalId();
    }

    @Override
    public boolean isCardPresent(int slotNo) {
        SicctChannelHandler handler = connection.getSicctChannelHandler();
        return handler != null && handler.isCardPresent(slotNo);
    }

    @Override
    public ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException {
        SicctChannelHandler handler = connection.getSicctChannelHandler();
        if (handler == null) {
            throw new CardTransportException("no active SICCT channel for terminal=" + name());
        }
        try {
            byte[] response = handler.transmitCardApdu(slotNo, command.getBytes())
                    .get(connection.getApduTimeoutMs(), TimeUnit.MILLISECONDS);
            return new ResponseAPDU(response);
        } catch (TimeoutException e) {
            throw new CardTransportException(
                    "SICCT card APDU timed out on slot " + slotNo + " of terminal=" + name(), e);
        } catch (ExecutionException e) {
            throw new CardTransportException(
                    "SICCT card APDU failed on slot " + slotNo + " of terminal=" + name(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CardTransportException("interrupted during SICCT card APDU on terminal=" + name(), e);
        }
    }
}
