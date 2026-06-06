package de.servicehealtherx.quarkus.sicct.runtime.discovery;

import de.servicehealtherx.quarkus.sicct.runtime.discovery.CardTerminalDiscovery.DiscoveredTerminal;
import de.servicehealtherx.sicct.EhealthAuthenticator;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for CardTerminalDiscovery.
 *
 * Tests cover:
 *   - TLV packet encoding (Dienstanfragepaket, Tabelle 27)
 *   - TLV packet parsing  (Dienstbeschreibungspaket, Tabelle 28)
 *   - BER length field helpers
 *   - Live UDP broadcast against a real SICCT terminal at {@value #TERMINAL_HOST}
 *
 * Run with: mvn verify -Pit
 *           mvn verify -Pit -Dsicct.it.host=192.168.100.90
 */
@QuarkusTest
@TestProfile(CardTerminalDiscoveryIT.ItProfile.class)
class CardTerminalDiscoveryIT {

    static final String TERMINAL_HOST =
        System.getProperty("sicct.it.host", "192.168.100.90");

    @Inject
    CardTerminalDiscovery discovery;

    // EhealthAuthenticator is pulled in transitively via SicctTerminalManager
    @InjectMock
    EhealthAuthenticator ehealthAuthenticator;

    // -------------------------------------------------------------------------
    // 1. CDI wiring
    // -------------------------------------------------------------------------

    @Test
    void discovery_bean_isInjected() {
        assertNotNull(discovery, "CardTerminalDiscovery must be available as CDI bean");
    }

    // -------------------------------------------------------------------------
    // 2. Dienstanfragepaket encoding — SICCT §6.2.3.1, Tabelle 27
    // -------------------------------------------------------------------------

    @Test
    void buildRequestPacket_totalLengthIs16Bytes() {
        byte[] ip = {(byte) 192, (byte) 168, 0, 1};
        byte[] pkt = discovery.buildRequestPacket(ip, 4742);
        assertEquals(16, pkt.length, "A0 <0E> + 14 inner bytes = 16 total");
    }

    @Test
    void buildRequestPacket_outerTagAndLength() {
        byte[] pkt = discovery.buildRequestPacket(new byte[]{10, 0, 0, 1}, 4742);
        assertEquals((byte) 0xA0, pkt[0], "outer tag must be A0 (Dienstanfragepaket)");
        assertEquals(14, pkt[1] & 0xFF, "outer length must be 14 (0x0E)");
    }

    @Test
    void buildRequestPacket_protocolVersionIs1_30() {
        byte[] pkt = discovery.buildRequestPacket(new byte[]{10, 0, 0, 1}, 4742);
        // 80 02 01 1E at offset 2
        assertEquals((byte) 0x80, pkt[2], "version tag");
        assertEquals(0x02, pkt[3] & 0xFF, "version length");
        assertEquals(0x01, pkt[4] & 0xFF, "major version = 1");
        assertEquals(0x1E, pkt[5] & 0xFF, "minor version = 30 (0x1E)");
    }

    @Test
    void buildRequestPacket_ipAddressEncodedNetworkByteOrder() {
        byte[] ip = {(byte) 192, (byte) 168, 1, 10};
        byte[] pkt = discovery.buildRequestPacket(ip, 4742);
        // 81 04 <ip> at offset 6
        assertEquals((byte) 0x81, pkt[6], "IP tag");
        assertEquals(0x04, pkt[7] & 0xFF, "IP length");
        assertEquals((byte) 192, pkt[8], "IP[0]");
        assertEquals((byte) 168, pkt[9], "IP[1]");
        assertEquals(1, pkt[10] & 0xFF, "IP[2]");
        assertEquals(10, pkt[11] & 0xFF, "IP[3]");
    }

    @Test
    void buildRequestPacket_portEncodedBigEndian() {
        // port 4742 = 0x1286
        byte[] pkt = discovery.buildRequestPacket(new byte[]{10, 0, 0, 1}, 4742);
        // 82 02 12 86 at offset 12
        assertEquals((byte) 0x82, pkt[12], "port tag");
        assertEquals(0x02, pkt[13] & 0xFF, "port length");
        assertEquals(0x12, pkt[14] & 0xFF, "port high byte (0x12)");
        assertEquals(0x86, pkt[15] & 0xFF, "port low byte (0x86)");
    }

    @Test
    void buildRequestPacket_specExampleBytes() {
        // Spec figure 7: client IP 192.168.0.1, port 4742, protocol version 1.30
        // Expected (with v1.30): A0 0E 80 02 01 1E 81 04 C0 A8 00 01 82 02 12 86
        byte[] ip = {(byte) 0xC0, (byte) 0xA8, 0x00, 0x01};
        byte[] pkt = discovery.buildRequestPacket(ip, 4742);
        byte[] expected = {
            (byte) 0xA0, 0x0E,
            (byte) 0x80, 0x02, 0x01, 0x1E,
            (byte) 0x81, 0x04, (byte) 0xC0, (byte) 0xA8, 0x00, 0x01,
            (byte) 0x82, 0x02, 0x12, (byte) 0x86
        };
        assertArrayEquals(expected, pkt, "full packet must match SICCT spec byte pattern");
    }

    // -------------------------------------------------------------------------
    // 3. Dienstbeschreibungspaket parsing — SICCT §6.2.3, Tabelle 28
    // -------------------------------------------------------------------------

    /**
     * Verbatim unencrypted response from spec figure 7 (Terminal "TERMINAL", 192.168.0.5).
     * A1 20 80 02 01 14 81 04 C0 A8 00 05 83 06 08 00 20 AE FE 7E 84 08 54 45 52 4D 49 4E 41 4C 82 02 12 86
     */
    static final byte[] SPEC_EXAMPLE_RESPONSE = {
        (byte) 0xA1, 0x20,
        (byte) 0x80, 0x02, 0x01, 0x14,
        (byte) 0x81, 0x04, (byte) 0xC0, (byte) 0xA8, 0x00, 0x05,
        (byte) 0x83, 0x06, 0x08, 0x00, 0x20, (byte) 0xAE, (byte) 0xFE, 0x7E,
        (byte) 0x84, 0x08, 0x54, 0x45, 0x52, 0x4D, 0x49, 0x4E, 0x41, 0x4C,
        (byte) 0x82, 0x02, 0x12, (byte) 0x86
    };

    @Test
    void parseResponsePacket_specExample_isNotNull() {
        assertNotNull(CardTerminalDiscovery.parseResponsePacket(SPEC_EXAMPLE_RESPONSE));
    }

    @Test
    void parseResponsePacket_specExample_ipAddress() {
        DiscoveredTerminal t = CardTerminalDiscovery.parseResponsePacket(SPEC_EXAMPLE_RESPONSE);
        assertEquals("192.168.0.5", t.ipAddress());
    }

    @Test
    void parseResponsePacket_specExample_macAddress() {
        DiscoveredTerminal t = CardTerminalDiscovery.parseResponsePacket(SPEC_EXAMPLE_RESPONSE);
        assertEquals("08:00:20:AE:FE:7E", t.macAddressHex());
    }

    @Test
    void parseResponsePacket_specExample_terminalName() {
        DiscoveredTerminal t = CardTerminalDiscovery.parseResponsePacket(SPEC_EXAMPLE_RESPONSE);
        assertEquals("TERMINAL", t.name());
    }

    @Test
    void parseResponsePacket_specExample_commandPort() {
        DiscoveredTerminal t = CardTerminalDiscovery.parseResponsePacket(SPEC_EXAMPLE_RESPONSE);
        assertEquals(4742, t.commandPort());
    }

    @Test
    void parseResponsePacket_specExample_protocolVersion() {
        DiscoveredTerminal t = CardTerminalDiscovery.parseResponsePacket(SPEC_EXAMPLE_RESPONSE);
        assertEquals(1, t.protocolVersionMajor(), "major version");
        assertEquals(20, t.protocolVersionMinor(), "minor version");
    }

    @Test
    void parseResponsePacket_withSecurityProtocol_ignoredCorrectly() {
        // Response with optional A3 (security protocol) tag — must be parsed ignoring A3
        // Inner: 4 (version) + 6 (IP) + 8 (MAC) + 10 (name) + 4 (port) + 5 (A3) = 37 (0x25)
        byte[] withSecProto = {
            (byte) 0xA1, 0x25,
            (byte) 0x80, 0x02, 0x01, 0x1E,
            (byte) 0x81, 0x04, (byte) 0xC0, (byte) 0xA8, 0x00, 0x05,
            (byte) 0x83, 0x06, 0x08, 0x00, 0x20, (byte) 0xAE, (byte) 0xFD, 0x7E,
            (byte) 0x84, 0x08, 'T', 'E', 'R', 'M', 'I', 'N', 'A', 'L',
            (byte) 0x82, 0x02, 0x12, (byte) 0x86,
            (byte) 0xA3, 0x03, (byte) 0x8A, 0x01, 0x40   // TLS 1.3 security proto
        };
        DiscoveredTerminal t = CardTerminalDiscovery.parseResponsePacket(withSecProto);
        assertNotNull(t, "terminal must be parsed despite optional A3 security-protocol field");
        assertEquals("TERMINAL", t.name());
        assertEquals("192.168.0.5", t.ipAddress());
        assertEquals(4742, t.commandPort());
    }

    @Test
    void parseResponsePacket_fieldsInNonCanonicalOrder_parsedCorrectly() {
        // Spec §6.2.3.3: field order is irrelevant except for Protokoll Version (must be first)
        // Swap MAC and name order in an otherwise valid packet
        // Inner: 4 (version) + 10 (name) + 6 (IP) + 8 (MAC) + 4 (port) = 32 (0x20)
        byte[] reordered = {
            (byte) 0xA1, 0x20,
            (byte) 0x80, 0x02, 0x01, 0x14,                              // version first (mandatory)
            (byte) 0x84, 0x08, 'T', 'E', 'R', 'M', 'I', 'N', 'A', 'L', // name
            (byte) 0x81, 0x04, (byte) 0xC0, (byte) 0xA8, 0x00, 0x05,   // IP
            (byte) 0x83, 0x06, 0x08, 0x00, 0x20, (byte) 0xAE, (byte) 0xFE, 0x7E, // MAC
            (byte) 0x82, 0x02, 0x12, (byte) 0x86                        // port
        };
        DiscoveredTerminal t = CardTerminalDiscovery.parseResponsePacket(reordered);
        assertNotNull(t);
        assertEquals("TERMINAL", t.name());
        assertEquals("192.168.0.5", t.ipAddress());
    }

    // -------------------------------------------------------------------------
    // 4. Parsing edge cases
    // -------------------------------------------------------------------------

    @Test
    void parseResponsePacket_null_returnsNull() {
        assertNull(CardTerminalDiscovery.parseResponsePacket(null));
    }

    @Test
    void parseResponsePacket_emptyArray_returnsNull() {
        assertNull(CardTerminalDiscovery.parseResponsePacket(new byte[0]));
    }

    @Test
    void parseResponsePacket_singleByte_returnsNull() {
        assertNull(CardTerminalDiscovery.parseResponsePacket(new byte[]{(byte) 0xA1}));
    }

    @Test
    void parseResponsePacket_wrongOuterTag_returnsNull() {
        // Tag A0 is the request tag — must not be accepted as a response
        byte[] wrongTag = {(byte) 0xA0, 0x04, (byte) 0x80, 0x02, 0x01, 0x14};
        assertNull(CardTerminalDiscovery.parseResponsePacket(wrongTag));
    }

    @Test
    void parseResponsePacket_missingMacAddress_returnsNull() {
        // Valid version + IP, but no MAC or name → mandatory fields missing
        // Inner: 80 02 01 14 (4) + 81 04 C0 A8 00 05 (6) = 10 (0x0A)
        byte[] noMac = {
            (byte) 0xA1, 0x0A,
            (byte) 0x80, 0x02, 0x01, 0x14,
            (byte) 0x81, 0x04, (byte) 0xC0, (byte) 0xA8, 0x00, 0x05
        };
        assertNull(CardTerminalDiscovery.parseResponsePacket(noMac));
    }

    @Test
    void parseResponsePacket_missingTerminalName_returnsNull() {
        // Valid version + IP + MAC, but no name → mandatory field missing
        // Inner: 4 + 6 + 8 = 18 (0x12)
        byte[] noName = {
            (byte) 0xA1, 0x12,
            (byte) 0x80, 0x02, 0x01, 0x14,
            (byte) 0x81, 0x04, (byte) 0xC0, (byte) 0xA8, 0x00, 0x05,
            (byte) 0x83, 0x06, 0x08, 0x00, 0x20, (byte) 0xAE, (byte) 0xFE, 0x7E
        };
        assertNull(CardTerminalDiscovery.parseResponsePacket(noName));
    }

    @Test
    void parseResponsePacket_truncatedInnerData_returnsNull() {
        // Outer length says 20 bytes but only 6 are present
        byte[] truncated = {(byte) 0xA1, 0x14, (byte) 0x80, 0x02, 0x01, 0x14, 0x00, 0x00};
        assertNull(CardTerminalDiscovery.parseResponsePacket(truncated));
    }

    // -------------------------------------------------------------------------
    // 5. BER length helpers — SICCT §6.2.3.3
    // -------------------------------------------------------------------------

    @Test
    void berReadLength_shortForm_returnsSingleByteValue() {
        byte[] data = {0x0E};
        assertEquals(14, CardTerminalDiscovery.berReadLength(data, 0));
    }

    @Test
    void berReadLength_shortFormMax_returns127() {
        byte[] data = {0x7F};
        assertEquals(127, CardTerminalDiscovery.berReadLength(data, 0));
    }

    @Test
    void berReadLength_twoByteExtended_returnsUnsignedByte() {
        byte[] data = {(byte) 0x81, (byte) 0xFF};
        assertEquals(255, CardTerminalDiscovery.berReadLength(data, 0));
    }

    @Test
    void berReadLength_threeByteExtended_returnsUnsignedShort() {
        byte[] data = {(byte) 0x82, 0x01, 0x00};
        assertEquals(256, CardTerminalDiscovery.berReadLength(data, 0));
    }

    @Test
    void berReadLength_atOffset_readsFromCorrectPosition() {
        byte[] data = {0x00, 0x00, 0x14};
        assertEquals(20, CardTerminalDiscovery.berReadLength(data, 2));
    }

    @Test
    void berReadLength_beyondEnd_returnsNegativeOne() {
        assertEquals(-1, CardTerminalDiscovery.berReadLength(new byte[]{}, 0));
        assertEquals(-1, CardTerminalDiscovery.berReadLength(new byte[]{(byte) 0x81}, 0)); // needs 2 bytes
    }

    @Test
    void berLengthWidth_shortForm_isOne() {
        byte[] data = {0x20};
        assertEquals(1, CardTerminalDiscovery.berLengthWidth(data, 0));
    }

    @Test
    void berLengthWidth_twoByteExtended_isTwo() {
        byte[] data = {(byte) 0x81, (byte) 0xFF};
        assertEquals(2, CardTerminalDiscovery.berLengthWidth(data, 0));
    }

    @Test
    void berLengthWidth_threeByteExtended_isThree() {
        byte[] data = {(byte) 0x82, 0x01, 0x00};
        assertEquals(3, CardTerminalDiscovery.berLengthWidth(data, 0));
    }

    // -------------------------------------------------------------------------
    // 6. Live UDP tests — require reachable SICCT terminal
    // -------------------------------------------------------------------------

    @Test
    @Timeout(10)
    void discover_broadcastCompletesWithoutException() throws Exception {
        // Sends a real SICCT Dienstanfrage broadcast; we don't assert on the count
        // since no terminal may be present in the CI environment.
        List<DiscoveredTerminal> found = discovery.discover("255.255.255.255",
            CardTerminalDiscovery.SICCT_DISCOVERY_PORT, 500);
        assertNotNull(found, "discover() must return a non-null list");
    }

    @Test
    @Timeout(15)
    void discover_unicastToKnownTerminal_completesWithoutException() throws Exception {
        // Sends directly to the known terminal IP (unicast).
        // If the terminal supports SICCT service discovery it will respond;
        // if not, the list will be empty — both outcomes are acceptable here.
        List<DiscoveredTerminal> found = discovery.discover(TERMINAL_HOST,
            CardTerminalDiscovery.SICCT_DISCOVERY_PORT, 3_000);
        assertNotNull(found);
        found.forEach(t -> {
            assertNotNull(t.ipAddress(), "discovered terminal must have an IP");
            assertNotNull(t.name(), "discovered terminal must have a name");
            assertNotNull(t.macAddress(), "discovered terminal must have a MAC");
            assertEquals(6, t.macAddress().length, "MAC address must be 6 bytes");
            assertTrue(t.commandPort() > 0, "command port must be positive");
        });
    }

    // -------------------------------------------------------------------------
    // Test profile — isolated Derby DB, discovery disabled (scheduler won't fire)
    // -------------------------------------------------------------------------

    public static class ItProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.datasource.jdbc.url",
                    "jdbc:derby:memory:sicct-discovery-it-db;create=true",
                "quarkus.hibernate-orm.database.generation", "drop-and-create",
                // Keep scheduler suppressed — tests invoke discover() directly
                "sicct.discovery.enabled", "false"
            );
        }

        @Override
        public String getConfigProfile() {
            return "it";
        }
    }
}
