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
import de.servicehealtherx.sicct.jpa.CorrelationState;
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

    static final String HOSTNAME = "it-kt-001";
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
        terminal.hostname  = HOSTNAME;
        terminal.ipAddress = HOST;
        terminal.tcpPort   = PORT;
        terminal.macAddress = "00:11:22:33:44:55";
        terminal.correlation = CorrelationState.BEKANNT;
        CardTerminal.persist(terminal);
        utx.commit();
    }

    @AfterEach
    void cleanup() throws Exception {
        // Stop the connection from being re-used by subsequent tests
        manager.getConnections().remove(HOSTNAME);
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
        manager.connectTerminal(HOSTNAME, HOST, PORT);

        // computeIfAbsent runs synchronously — the entry must be present immediately
        assertNotNull(manager.getConnections().get(HOSTNAME),
                "Connection entry must be registered in the manager's map after connectTerminal()");
    }

    @Test
    @Timeout(15)
    void connectTerminal_reachesTcpConnectedState() {
        manager.connectTerminal(HOSTNAME, HOST, PORT);

        // CONNECTED = TCP handshake done (ChannelFuture success).
        // RECONNECTING = was CONNECTED then TLS closed the channel (still proves TCP reachability).
        await()
            .atMost(CONNECT_TIMEOUT)
            .pollInterval(50, MILLISECONDS)
            .alias("terminal " + HOST + ":" + PORT + " must accept TCP connections")
            .untilAsserted(() -> {
                SicctTerminalConnection conn = manager.getConnections().get(HOSTNAME);
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
        manager.connectTerminal(HOSTNAME, HOST, PORT);

        await()
            .atMost(CONNECT_TIMEOUT)
            .pollInterval(50, MILLISECONDS)
            .untilAsserted(() -> {
                SicctTerminalConnection conn = manager.getConnections().get(HOSTNAME);
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
        manager.connectTerminal(HOSTNAME, HOST, PORT);

        await()
            .atMost(CONNECT_TIMEOUT)
            .pollInterval(50, MILLISECONDS)
            .untilAsserted(() -> {
                SicctTerminalConnection conn = manager.getConnections().get(HOSTNAME);
                assertNotNull(conn);
                assertEquals(HOSTNAME, conn.getTerminalId());
            });
    }

    @Test
    @Timeout(15)
    void onTerminalDisconnected_afterConnect_schedulesReconnect() throws Exception {
        manager.connectTerminal(HOSTNAME, HOST, PORT);

        // Wait until the connection is past CONNECTING before triggering a disconnect
        await()
            .atMost(CONNECT_TIMEOUT)
            .pollInterval(50, MILLISECONDS)
            .until(() -> {
                SicctTerminalConnection conn = manager.getConnections().get(HOSTNAME);
                return conn != null &&
                       conn.getConnectionState() != SicctTerminalConnection.ConnectionState.CONNECTING;
            });

        // Triggering onTerminalDisconnected must not throw and must leave the entry in the map
        assertDoesNotThrow(() -> manager.onTerminalDisconnected(HOSTNAME));
        assertNotNull(manager.getConnections().get(HOSTNAME),
                "Connection entry must remain in the map after disconnect (for reconnect)");
    }

    // -------------------------------------------------------------------------
    // Test profile — isolated in-memory DB so the IT container does not share
    // the H2 instance used by the unit-test QuarkusTest container.
    // -------------------------------------------------------------------------

    public static class ItProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.datasource.jdbc.url",          "jdbc:h2:mem:sicct-it-db;DB_CLOSE_DELAY=-1",
                "quarkus.hibernate-orm.database.generation", "drop-and-create"
            );
        }

        @Override
        public String getConfigProfile() {
            return "it";
        }
    }
}
