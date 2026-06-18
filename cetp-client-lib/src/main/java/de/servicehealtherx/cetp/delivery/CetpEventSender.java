package de.servicehealtherx.cetp.delivery;

import de.servicehealtherx.cetp.tls.CetpTlsContextFactory;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLSocket;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends a framed CETP event to a client system's event sink (gemSpec_Kon TIP1-A_4594/4595/5536).
 * Opens a fresh TCP (optionally TLS) connection per delivery and closes it after the frame is
 * written; no application-level reply is read (FR-006). Each attempt is bounded by a configurable
 * connect+write timeout (default 2 s, SC-008/FR-005a). Deliveries run on a thread pool so a slow
 * sink cannot block delivery to other sinks.
 */
@ApplicationScoped
public class CetpEventSender {

    private static final Logger LOG = Logger.getLogger(CetpEventSender.class);

    @ConfigProperty(name = "cetp.delivery.timeout-ms", defaultValue = "2000")
    int timeoutMs;

    @ConfigProperty(name = "cetp.tls.mandatory", defaultValue = "true")
    boolean tlsMandatory;

    @Inject
    CetpTlsContextFactory tlsContextFactory;

    private final ExecutorService deliveryPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cetp-delivery");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param eventTo  the sink URL {@code cetp://host:port}
     * @param xmlUtf8  the UTF-8 XML {@code Event} body (will be framed)
     * @return a future completing with {@code true} on successful send, {@code false} on any
     *         connection/write failure or timeout (counts as one failed attempt, FR-019/FR-028)
     */
    public CompletableFuture<Boolean> send(String eventTo, byte[] xmlUtf8) {
        return CompletableFuture.supplyAsync(() -> sendBlocking(eventTo, xmlUtf8), deliveryPool);
    }

    private boolean sendBlocking(String eventTo, byte[] xmlUtf8) {
        HostPort target;
        try {
            target = HostPort.parse(eventTo);
        } catch (RuntimeException e) {
            LOG.warnf("Invalid event sink URL '%s'; dropping delivery", eventTo);
            return false;
        }
        byte[] frame = CetpFrameCodec.encode(xmlUtf8);
        try (Socket socket = openSocket(target)) {
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(frame);
            out.flush();
            return true;
        } catch (Exception e) {
            LOG.debugf(e, "CETP delivery to %s failed", eventTo);
            return false;
        }
    }

    /**
     * Opens a fresh connection to the sink. With {@code cetp.tls.mandatory=true} (CETP1) the
     * connection is layered with TLS: the konnektor authenticates as client (SmkCSAKAut, when the
     * peer requests it) and the peer's server certificate is PKIX-validated against the client-system
     * trust store; a trust/handshake failure throws and counts as a failed attempt (FR-026/FR-028).
     * Otherwise (CETP2) a plain TCP connection is used (FR-027).
     */
    protected Socket openSocket(HostPort target) throws Exception {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(target.host(), target.port()), timeoutMs);
        if (!tlsMandatory) {
            return plain;
        }
        SSLSocket sslSocket = (SSLSocket) tlsContextFactory.socketFactory()
                .createSocket(plain, target.host(), target.port(), true);
        sslSocket.setUseClientMode(true);
        sslSocket.setSoTimeout(timeoutMs);
        sslSocket.startHandshake(); // forces PKIX validation now; throws on untrusted server cert
        return sslSocket;
    }

    @PreDestroy
    void shutdown() {
        deliveryPool.shutdownNow();
    }

    /** Parsed {@code cetp://host:port} sink address. */
    public record HostPort(String host, int port) {
        public static HostPort parse(String eventTo) {
            URI uri = URI.create(eventTo);
            if (!"cetp".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 0) {
                throw new IllegalArgumentException("Expected cetp://host:port, got " + eventTo);
            }
            return new HostPort(uri.getHost(), uri.getPort());
        }
    }
}
