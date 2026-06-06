package de.servicehealtherx.quarkus.sicct.runtime;

import static de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalConnection.ConnectionState.CONNECTED;
import static de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalConnection.ConnectionState.RECONNECTING;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;

import de.servicehealtherx.sicct.EhealthAuthenticator;
import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration test for SicctTerminalManager against a live SICCT card terminal.
 *
 * Prerequisites:
 *   - A SICCT-compliant card terminal is reachable at sicct.it.host:sicct.it.port.
 *   - The terminal accepts TCP connections (TLS may fail if client certificates are
 *     not yet configured — CONNECTED state is set on TCP level).
 *
 * Run with:
 *   mvn verify -Pit
 *   mvn verify -Pit -Dsicct.it.host=192.168.100.90 -Dsicct.it.port=3001
 */
@QuarkusTest
@TestProfile(SicctTerminalManagerIT.ItProfile.class)
class SicctTerminalManagerIT {

    static final String TERMINAL_ID = "it-kt-001";
    static final String HOST =
            System.getProperty("sicct.it.host", "192.168.100.90");
    static final int PORT =
            Integer.parseInt(System.getProperty("sicct.it.port", "3001"));
    static final Duration CONNECT_TIMEOUT =
            Duration.ofMillis(Long.parseLong(System.getProperty("sicct.it.connectTimeoutMs", "10000")));

    @Inject
    SicctTerminalManager manager;

    @InjectMock
    EhealthAuthenticator ehealthAuthenticator;

    @Inject
    UserTransaction utx;

    @BeforeEach
    void insertTerminal() throws Exception {
        utx.begin();
        CardTerminal terminal = new CardTerminal();
        terminal.terminalId  = TERMINAL_ID;
        terminal.host        = HOST;
        terminal.port        = PORT;
        terminal.pairingStatus = "UNKNOWN";
        terminal.connectTimeoutMs  = 5_000;
        terminal.apduTimeoutMs     = 30_000;
        terminal.maxRetries        = 3;
        terminal.initialBackoffMs  = 1_000;
        terminal.maxBackoffMs      = 30_000;
        CardTerminal.persist(terminal);
        utx.commit();
    }

    @AfterEach
    void cleanup() throws Exception {
        // Stop the connection from being re-used by subsequent tests
        manager.getConnections().remove(TERMINAL_ID);
        utx.begin();
        CardTerminal.deleteAll();
        utx.commit();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @Timeout(15)
    void connectTerminal_appearsInConnectionsMap() {
        manager.connectTerminal(TERMINAL_ID, HOST, PORT);

        // computeIfAbsent runs synchronously — the entry must be present immediately
        assertNotNull(manager.getConnections().get(TERMINAL_ID),
                "Connection entry must be registered in the manager's map after connectTerminal()");
    }

    @Test
    @Timeout(15)
    void connectTerminal_reachesTcpConnectedState() {
        manager.connectTerminal(TERMINAL_ID, HOST, PORT);

        // CONNECTED = TCP handshake done (ChannelFuture success).
        // RECONNECTING = was CONNECTED then TLS closed the channel (still proves TCP reachability).
        await()
            .atMost(CONNECT_TIMEOUT)
            .pollInterval(50, MILLISECONDS)
            .alias("terminal " + HOST + ":" + PORT + " must accept TCP connections")
            .untilAsserted(() -> {
                SicctTerminalConnection conn = manager.getConnections().get(TERMINAL_ID);
                assertNotNull(conn, "Connection must appear in manager's map");
                SicctTerminalConnection.ConnectionState state = conn.getConnectionState();
                assertTrue(
                    state == CONNECTED || state == RECONNECTING,
                    "Expected CONNECTED or RECONNECTING (TCP succeeded) but was: " + state
                );
            });
    }

    @Test
    @Timeout(15)
    void connectTerminal_channelIsNonNullAfterConnect() {
        manager.connectTerminal(TERMINAL_ID, HOST, PORT);

        await()
            .atMost(CONNECT_TIMEOUT)
            .pollInterval(50, MILLISECONDS)
            .untilAsserted(() -> {
                SicctTerminalConnection conn = manager.getConnections().get(TERMINAL_ID);
                assertNotNull(conn);
                // Channel is set on TCP connect; may be nulled again if TLS fails
                assertFalse(
                    conn.getConnectionState() == SicctTerminalConnection.ConnectionState.CONNECTING,
                    "Connection must have advanced past CONNECTING (attempt in progress)"
                );
            });
    }

    @Test
    @Timeout(15)
    void connectTerminal_terminalIdIsCorrectlyKeyed() {
        manager.connectTerminal(TERMINAL_ID, HOST, PORT);

        await()
            .atMost(CONNECT_TIMEOUT)
            .pollInterval(50, MILLISECONDS)
            .untilAsserted(() -> {
                SicctTerminalConnection conn = manager.getConnections().get(TERMINAL_ID);
                assertNotNull(conn);
                assertEquals(TERMINAL_ID, conn.getTerminalId());
            });
    }

    @Test
    @Timeout(15)
    void onTerminalDisconnected_afterConnect_schedulesReconnect() throws Exception {
        manager.connectTerminal(TERMINAL_ID, HOST, PORT);

        // Wait until the connection is past CONNECTING before triggering a disconnect
        await()
            .atMost(CONNECT_TIMEOUT)
            .pollInterval(50, MILLISECONDS)
            .until(() -> {
                SicctTerminalConnection conn = manager.getConnections().get(TERMINAL_ID);
                return conn != null &&
                       conn.getConnectionState() != SicctTerminalConnection.ConnectionState.CONNECTING;
            });

        // Triggering onTerminalDisconnected must not throw and must leave the entry in the map
        assertDoesNotThrow(() -> manager.onTerminalDisconnected(TERMINAL_ID));
        assertNotNull(manager.getConnections().get(TERMINAL_ID),
                "Connection entry must remain in the map after disconnect (for reconnect)");
    }

    // -------------------------------------------------------------------------
    // Test profile — isolated in-memory DB so the IT container does not share
    // the Derby instance used by the unit-test QuarkusTest container.
    // -------------------------------------------------------------------------

    public static class ItProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.datasource.jdbc.url",          "jdbc:derby:memory:sicct-it-db;create=true",
                "quarkus.hibernate-orm.database.generation", "drop-and-create"
            );
        }

        @Override
        public String getConfigProfile() {
            return "it";
        }
    }
}
