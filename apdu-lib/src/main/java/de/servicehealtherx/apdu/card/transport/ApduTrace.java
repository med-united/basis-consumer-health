package de.servicehealtherx.apdu.card.transport;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

/**
 * Hex tracing of APDU exchanges, shared by every {@link CardReaderPort} implementation (PC/SC and
 * SICCT). Each {@link CardReaderPort#transmit} routes through {@link #trace} so the sent command
 * APDU and the received response APDU are logged in hex at {@link Level#FINE} on the logger named
 * {@value #LOGGER_NAME}.
 *
 * <p>Tracing is <strong>off by default</strong> and adds no overhead unless {@code FINE} is enabled
 * (the byte-to-hex formatting is deferred behind {@link Logger#isLoggable}). Enable it from a
 * {@code logging.properties}:
 * <pre>
 * de.servicehealtherx.apdu.trace.level = FINE
 * </pre>
 *
 * <p><strong>Security note:</strong> raw APDUs carry sensitive material. PIN-bearing commands
 * (VERIFY, CHANGE/RESET REFERENCE DATA, EN/DISABLE verification) have their data field masked here
 * so PINs are never written to the log; certificate and personal data read from the card are still
 * emitted in full when tracing is on, so only enable it for debugging and protect the log output.
 */
public final class ApduTrace {

    /** Logger name to raise to {@code FINE} to switch APDU tracing on. */
    public static final String LOGGER_NAME = "de.servicehealtherx.apdu.trace";

    private static final Logger LOG = Logger.getLogger(LOGGER_NAME);

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** ISO 7816 INS bytes whose command data field carries a PIN and must not be logged. */
    private static final int INS_VERIFY = 0x20;
    private static final int INS_CHANGE_REFERENCE_DATA = 0x24;
    private static final int INS_RESET_RETRY_COUNTER = 0x2C;
    private static final int INS_DISABLE_VERIFICATION = 0x26;
    private static final int INS_ENABLE_VERIFICATION = 0x28;

    private ApduTrace() {
    }

    /** The APDU exchange a {@link CardReaderPort} performs, wrapped so it can be traced. */
    @FunctionalInterface
    public interface Exchange {
        ResponseAPDU transmit() throws CardTransportException;
    }

    /**
     * Run {@code exchange}, logging the {@code command} sent and the {@link ResponseAPDU} received
     * (both in hex) at {@link Level#FINE}. Returns the response unchanged; a failure is logged and
     * rethrown so tracing never alters transmit behaviour.
     */
    public static ResponseAPDU trace(String readerName, int slotNo, CommandAPDU command, Exchange exchange)
            throws CardTransportException {
        if (!LOG.isLoggable(Level.FINE)) {
            return exchange.transmit();
        }
        LOG.fine(() -> String.format("APDU > [%s slot %d] %s", readerName, slotNo, commandHex(command)));
        try {
            ResponseAPDU response = exchange.transmit();
            LOG.fine(() -> String.format("APDU < [%s slot %d] %s SW=%04X",
                    readerName, slotNo, toHex(response.getBytes()), response.getSW()));
            return response;
        } catch (CardTransportException | RuntimeException e) {
            LOG.log(Level.FINE, e, () -> String.format("APDU ! [%s slot %d] transmit failed", readerName, slotNo));
            throw e;
        }
    }

    /** Hex of the command, with the data field of PIN-bearing commands masked. */
    private static String commandHex(CommandAPDU command) {
        if (isPinBearing(command.getINS()) && command.getNc() > 0) {
            // Log only the four-byte header + a placeholder so the PIN is never written out.
            return String.format("%02X%02X%02X%02X <%d data bytes withheld>",
                    command.getCLA(), command.getINS(), command.getP1(), command.getP2(), command.getNc());
        }
        return toHex(command.getBytes());
    }

    private static boolean isPinBearing(int ins) {
        return ins == INS_VERIFY || ins == INS_CHANGE_REFERENCE_DATA || ins == INS_RESET_RETRY_COUNTER
                || ins == INS_DISABLE_VERIFICATION || ins == INS_ENABLE_VERIFICATION;
    }

    /** Uppercase, space-separated hex for a byte array ({@code null}/empty → {@code "<none>"}). */
    public static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "<none>";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            int b = bytes[i] & 0xFF;
            sb.append(HEX[b >> 4]).append(HEX[b & 0x0F]);
        }
        return sb.toString();
    }
}
