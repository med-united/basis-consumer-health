package de.servicehealtherx.cetp.testsupport;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process CETP event sink for tests: a TCP (or TLS) server on {@code 127.0.0.1} that records the
 * raw bytes of each accepted connection. Used to assert that {@link de.servicehealtherx.cetp.CetpClient}
 * delivers well-formed CETP frames (quickstart Scenarios 1 + 4).
 */
public class FakeEventSink implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread acceptLoop;
    private final CopyOnWriteArrayList<byte[]> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private volatile CountDownLatch frameLatch = new CountDownLatch(1);
    private volatile boolean running = true;

    private FakeEventSink(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        this.acceptLoop = new Thread(this::runAcceptLoop, "fake-event-sink");
        this.acceptLoop.setDaemon(true);
        this.acceptLoop.start();
    }

    /** Plain-TCP sink on an ephemeral port. */
    public static FakeEventSink plain() throws IOException {
        ServerSocket ss = ServerSocketFactory.getDefault().createServerSocket(
                0, 50, InetAddress.getByName("127.0.0.1"));
        return new FakeEventSink(ss);
    }

    /** TLS sink on an ephemeral port; {@code wantClientAuth} requests (not requires) client auth. */
    public static FakeEventSink tls(SSLContext context, boolean wantClientAuth) throws IOException {
        SSLServerSocket ss = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(
                0, 50, InetAddress.getByName("127.0.0.1"));
        ss.setWantClientAuth(wantClientAuth);
        return new FakeEventSink(ss);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public String eventTo() {
        return "cetp://127.0.0.1:" + port();
    }

    /** Number of connections accepted so far (counts connection-establishment, even if TLS later fails). */
    public int connectionCount() {
        return connectionCount.get();
    }

    /** Block until the next full delivery is recorded, or return {@code null} on timeout. */
    public byte[] awaitFrame(long timeout, TimeUnit unit) throws InterruptedException {
        if (!frameLatch.await(timeout, unit)) {
            return null;
        }
        return received.isEmpty() ? null : received.get(received.size() - 1);
    }

    public java.util.List<byte[]> allFrames() {
        return java.util.List.copyOf(received);
    }

    private void runAcceptLoop() {
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                connectionCount.incrementAndGet();
                byte[] frame = readAll(socket.getInputStream());
                if (frame.length > 0) {
                    received.add(frame);
                    frameLatch.countDown();
                }
            } catch (IOException e) {
                // socket closed or handshake failure — ignore in test sink
                if (running) {
                    connectionCount.incrementAndGet();
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best effort
        }
        acceptLoop.interrupt();
    }
}
