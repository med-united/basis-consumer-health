package de.servicehealtherx.quarkus.sicct.runtime;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

/**
 * A lightweight, WireMock-style mock SICCT card terminal for tests. It listens on an ephemeral TCP
 * port, decodes the SICCT framing of every command the konnektor sends and replies with the stubbed
 * Response-APDU registered for that command via {@link #messageStubFor(SicctStubMapping)}.
 *
 * <p>Usage mirrors WireMock's {@code stubFor(...)}:
 *
 * <pre>{@code
 * try (SicctMockServer terminal = new SicctMockServer()) {
 *     int port = terminal.start();
 *     terminal.messageStubFor(forInstruction(SICCT.INS_INIT_CT_SESSION).willRespondWith("8300...9000"));
 *     terminal.messageStubFor(forInstruction(SICCT.INS_GET_STATUS).withP2(0x80).willRespondWith("8300...9000"));
 *     // ... connect a konnektor client to 127.0.0.1:port ...
 * }
 * }</pre>
 *
 * <p>The server rewrites the sequence number of each stubbed response to echo the sequence number of
 * the matching request, so the konnektor can correlate request/response without the stub having to
 * hard-code it. Unsolicited messages (e.g. EVENT notifications) can be sent with {@link #push(String)}.
 */
public class SicctMockServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SicctMockServer.class);

    private static final int HEADER_LENGTH = 10;

    private final List<SicctStubMapping> stubs = new CopyOnWriteArrayList<>();
    private final List<SicctRequest> receivedRequests = new CopyOnWriteArrayList<>();
    private final CountDownLatch clientConnected = new CountDownLatch(1);

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile Socket clientSocket;
    private volatile OutputStream clientOut;
    private volatile boolean running;

    /** Binds to an ephemeral port and starts accepting one konnektor connection. Returns the port. */
    public int start() throws IOException {
        serverSocket = new ServerSocket(0);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "sicct-mock-terminal");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return port();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Registers a stub that maps matching command-APDUs to a fixed Response-APDU. */
    public void messageStubFor(SicctStubMapping mapping) {
        stubs.add(mapping);
    }

    /** All command-APDUs received so far, in order. */
    public List<SicctRequest> getReceivedRequests() {
        return receivedRequests;
    }

    /** Waits until a konnektor has connected. */
    public boolean awaitClient(long timeout, TimeUnit unit) throws InterruptedException {
        return clientConnected.await(timeout, unit);
    }

    /** Sends an unsolicited SICCT message (raw hex, e.g. an EVENT notification) to the konnektor. */
    public synchronized void push(String hex) throws IOException {
        if (clientOut == null) {
            throw new IllegalStateException("No konnektor connected yet");
        }
        clientOut.write(HexFormat.of().parseHex(hex));
        clientOut.flush();
    }

    private void acceptLoop() {
        try {
            clientSocket = serverSocket.accept();
            clientOut = clientSocket.getOutputStream();
            clientConnected.countDown();
            DataInputStream in = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
            while (running) {
                SicctRequest request = readFrame(in);
                if (request == null) {
                    break;
                }
                receivedRequests.add(request);
                respond(request);
            }
        } catch (IOException e) {
            if (running) {
                LOG.debugf(e, "[mock-terminal] accept loop ended");
            }
        }
    }

    private SicctRequest readFrame(DataInputStream in) throws IOException {
        byte[] header = new byte[HEADER_LENGTH];
        try {
            in.readFully(header);
        } catch (EOFException eof) {
            return null;
        }
        int messageType = header[0] & 0xFF;
        int seq = ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
        int length = ((header[6] & 0xFF) << 24) | ((header[7] & 0xFF) << 16)
                | ((header[8] & 0xFF) << 8) | (header[9] & 0xFF);
        byte[] payload = new byte[length];
        in.readFully(payload);
        return new SicctRequest(messageType, seq, payload);
    }

    private synchronized void respond(SicctRequest request) throws IOException {
        for (SicctStubMapping stub : stubs) {
            if (stub.matches(request)) {
                byte[] response = stub.response().clone();
                // Echo the request sequence number (big-endian at offset 3-4) for correlation.
                if (response.length >= 5) {
                    response[3] = (byte) (request.seq() >> 8);
                    response[4] = (byte) request.seq();
                }
                clientOut.write(response);
                clientOut.flush();
                LOG.debugf("[mock-terminal] ins=0x%02X p1=0x%02X p2=0x%02X -> %s",
                        request.ins(), request.p1(), request.p2(), HexFormat.of().formatHex(response));
                return;
            }
        }
        LOG.warnf("[mock-terminal] no stub matched ins=0x%02X p1=0x%02X p2=0x%02X",
                request.ins(), request.p1(), request.p2());
    }

    @Override
    public void close() {
        running = false;
        closeQuietly(clientSocket);
        closeQuietly(serverSocket);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    // -------------------------------------------------------------------------
    // WireMock-style fluent stub builders
    // -------------------------------------------------------------------------

    /** Matches any command-APDU carrying the given INS byte. */
    public static SicctStubBuilder forInstruction(byte ins) {
        return new SicctStubBuilder(ins & 0xFF);
    }

    /** Matches any message (e.g. for a catch-all default response). */
    public static SicctStubBuilder forAnyMessage() {
        return new SicctStubBuilder(null);
    }

    public static final class SicctStubBuilder {
        private final Integer ins;
        private Integer p1;
        private Integer p2;

        private SicctStubBuilder(Integer ins) {
            this.ins = ins;
        }

        public SicctStubBuilder withP1(int p1) {
            this.p1 = p1 & 0xFF;
            return this;
        }

        public SicctStubBuilder withP2(int p2) {
            this.p2 = p2 & 0xFF;
            return this;
        }

        public SicctStubMapping willRespondWith(String responseHex) {
            return new SicctStubMapping(ins, p1, p2, HexFormat.of().parseHex(responseHex));
        }
    }

    /** A request matcher (INS/P1/P2, {@code null} = wildcard) paired with the canned response bytes. */
    public record SicctStubMapping(Integer ins, Integer p1, Integer p2, byte[] response) {

        boolean matches(SicctRequest request) {
            return (ins == null || ins == request.ins())
                    && (p1 == null || p1 == request.p1())
                    && (p2 == null || p2 == request.p2());
        }
    }

    /** A decoded SICCT command frame received from the konnektor. */
    public record SicctRequest(int messageType, int seq, byte[] payload) {

        public int cla() {
            return payload.length > 0 ? payload[0] & 0xFF : -1;
        }

        public int ins() {
            return payload.length > 1 ? payload[1] & 0xFF : -1;
        }

        public int p1() {
            return payload.length > 2 ? payload[2] & 0xFF : -1;
        }

        public int p2() {
            return payload.length > 3 ? payload[3] & 0xFF : -1;
        }
    }
}
