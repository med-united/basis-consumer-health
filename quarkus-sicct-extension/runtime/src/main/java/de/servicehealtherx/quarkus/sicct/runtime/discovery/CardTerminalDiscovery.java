package de.servicehealtherx.quarkus.sicct.runtime.discovery;

import de.servicehealtherx.quarkus.sicct.runtime.SicctTerminalManager;
import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Implements the SICCT Dienstanfrage (Service Discovery) per SICCT §6.2.3.1.
 *
 * Sends a TLV-encoded Dienstanfragepaket as a UDP broadcast and collects
 * Dienstbeschreibungspakete from responding SICCT terminals.
 * Runs periodically at startup, interval configurable via
 * {@code sicct.discovery.interval} (default: {@code 60s}).
 *
 * Relevant configuration keys:
 * 
 * <pre>
 *   sicct.discovery.enabled          = true          # set false to disable
 *   sicct.discovery.interval         = 60s           # Quarkus duration expression
 *   sicct.discovery.broadcast-address= 255.255.255.255
 *   sicct.discovery.port             = 4742
 *   sicct.discovery.wait-ms          = 3000
 * </pre>
 */
@ApplicationScoped
public class CardTerminalDiscovery {

    private static final Logger LOG = Logger.getLogger(CardTerminalDiscovery.class);

    public static final int SICCT_DISCOVERY_PORT = 4742;

    // Protocol version 1.30: high-byte major=0x01, low-byte minor=0x1E (30 decimal)
    static final byte PROTOCOL_VERSION_MAJOR = 0x01;
    static final byte PROTOCOL_VERSION_MINOR = 0x1E;

    // TLV tags — SICCT §6.2.3, Tabelle 27 & 28
    static final int TAG_REQUEST_PACKET = 0xA0;
    static final int TAG_RESPONSE_PACKET = 0xA1;
    static final int TAG_PROTOCOL_VERSION = 0x80;
    static final int TAG_IP_ADDRESS = 0x81;
    static final int TAG_COMMAND_PORT = 0x82;
    static final int TAG_MAC_ADDRESS = 0x83;
    static final int TAG_TERMINAL_NAME = 0x84;

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    @Inject
    @ConfigProperty(name = "sicct.discovery.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "sicct.discovery.broadcast-address", defaultValue = "255.255.255.255")
    String broadcastAddress;

    @Inject
    @ConfigProperty(name = "sicct.discovery.port", defaultValue = "4742")
    int discoveryPort;

    @Inject
    @ConfigProperty(name = "sicct.discovery.wait-ms", defaultValue = "3000")
    int responseWaitMs;

    @Inject
    SicctTerminalManager manager;

    // -------------------------------------------------------------------------
    // Scheduled entry point
    // -------------------------------------------------------------------------

    /**
     * Runs the SICCT Dienstanfrage broadcast periodically.
     * The interval is controlled by {@code sicct.discovery.interval} (default 60s).
     * Set {@code sicct.discovery.enabled=false} to suppress all broadcasts.
     */
    @Scheduled(every = "${sicct.discovery.interval:60s}", delay = 0, delayUnit = TimeUnit.SECONDS)
    void runPeriodicDiscovery() {
        if (!enabled) {
            LOG.debugf("[SICCT-DISCOVERY] discovery disabled via sicct.discovery.enabled=false");
            return;
        }
        try {
            List<DiscoveredTerminal> found = discover(broadcastAddress, discoveryPort, responseWaitMs);
            if (!found.isEmpty()) {
                registerNewTerminals(found);
            }
        } catch (Exception e) {
            LOG.warnf("[SICCT-DISCOVERY] periodic discovery failed: %s", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sends a broadcast Dienstanfrage to 255.255.255.255:4742 and returns all
     * terminals that respond within the configured wait time.
     */
    public List<DiscoveredTerminal> discover() throws Exception {
        return discover(broadcastAddress, discoveryPort, responseWaitMs);
    }

    /**
     * Sends a Dienstanfrage to {@code targetBroadcast}:{@code port} and returns
     * all terminals that respond within {@code waitMs} ms.
     */
    public List<DiscoveredTerminal> discover(String targetBroadcast, int port, int waitMs)
            throws Exception {

        InetAddress localAddr = getLocalIpAddress();
        byte[] request = buildRequestPacket(localAddr.getAddress(), port);

        List<DiscoveredTerminal> results = new CopyOnWriteArrayList<>();
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .handler(new DiscoveryResponseHandler(results));

            Channel ch = bootstrap.bind(port).sync().channel();
            try {
                InetSocketAddress target = new InetSocketAddress(targetBroadcast, port);
                ch.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(request), target)).sync();
                LOG.infof("[SICCT-DISCOVERY] Dienstanfrage broadcast sent to %s:%d", targetBroadcast, port);
                TimeUnit.MILLISECONDS.sleep(waitMs);
            } finally {
                ch.close().sync();
            }
        } finally {
            group.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
        }

        List<DiscoveredTerminal> snapshot = Collections.unmodifiableList(new ArrayList<>(results));
        LOG.infof("[SICCT-DISCOVERY] found %d terminal(s) after %d ms", snapshot.size(), waitMs);
        return snapshot;
    }

    // -------------------------------------------------------------------------
    // Auto-registration
    // -------------------------------------------------------------------------

    /**
     * Persists newly discovered terminals and triggers a TCP connection.
     * Terminals already present in the DB (by name/terminalId) are skipped.
     */
    @Transactional
    void registerNewTerminals(List<DiscoveredTerminal> terminals) {
        for (DiscoveredTerminal t : terminals) {
            // Use the terminal's SICCT name as the stable terminalId
            String terminalId = t.name();
            if (CardTerminal.findByTerminalId(terminalId) != null) {
                LOG.debugf("[SICCT-DISCOVERY] terminal '%s' already registered, skipping", terminalId);
                continue;
            }
            CardTerminal entity = new CardTerminal();
            entity.terminalId = terminalId;
            entity.host = t.ipAddress();
            entity.port = t.commandPort();
            entity.pairingStatus = "DISCOVERED";
            CardTerminal.persist(entity);
            LOG.infof("[SICCT-DISCOVERY] new terminal persisted: id='%s' host=%s port=%d mac=%s",
                    terminalId, t.ipAddress(), t.commandPort(), t.macAddressHex());

            manager.connectTerminal(terminalId, t.ipAddress(), t.commandPort());
        }
    }

    // -------------------------------------------------------------------------
    // Dienstanfragepaket encoding — Tabelle 27
    // -------------------------------------------------------------------------

    /**
     * Builds the TLV-encoded Dienstanfragepaket (protocol version 1.30).
     *
     * <pre>
     *   A0 0E
     *     80 02 01 1E          -- Protocol Version 1.30
     *     81 04 &lt;ip[0..3]&gt;    -- Client IP (network-byte-order)
     *     82 02 &lt;port&gt;        -- Client UDP port (network-byte-order)
     * </pre>
     */
    byte[] buildRequestPacket(byte[] clientIp, int clientPort) {
        byte[] inner = new byte[14]; // 4 (version) + 6 (IP TLV) + 4 (port TLV)
        int off = 0;

        inner[off++] = (byte) TAG_PROTOCOL_VERSION;
        inner[off++] = 0x02;
        inner[off++] = PROTOCOL_VERSION_MAJOR;
        inner[off++] = PROTOCOL_VERSION_MINOR;

        inner[off++] = (byte) TAG_IP_ADDRESS;
        inner[off++] = 0x04;
        inner[off++] = clientIp[0];
        inner[off++] = clientIp[1];
        inner[off++] = clientIp[2];
        inner[off++] = clientIp[3];

        inner[off++] = (byte) TAG_COMMAND_PORT;
        inner[off++] = 0x02;
        inner[off++] = (byte) ((clientPort >> 8) & 0xFF);
        inner[off] = (byte) (clientPort & 0xFF);

        byte[] packet = new byte[2 + inner.length];
        packet[0] = (byte) TAG_REQUEST_PACKET;
        packet[1] = (byte) inner.length;
        System.arraycopy(inner, 0, packet, 2, inner.length);
        return packet;
    }

    // -------------------------------------------------------------------------
    // Dienstbeschreibungspaket parsing — Tabelle 28
    // -------------------------------------------------------------------------

    /**
     * Parses a TLV-encoded Dienstbeschreibungspaket.
     * Returns {@code null} if the packet is invalid or mandatory fields are absent.
     */
    static DiscoveredTerminal parseResponsePacket(byte[] data) {
        if (data == null || data.length < 2)
            return null;
        if ((data[0] & 0xFF) != TAG_RESPONSE_PACKET)
            return null;

        int pos = 1;
        int outerLen = berReadLength(data, pos);
        if (outerLen < 0)
            return null;
        pos += berLengthWidth(data, pos);

        int end = pos + outerLen;
        if (end > data.length)
            return null;

        int protocolVersion = 0;
        byte[] ipBytes = null;
        byte[] macBytes = null;
        String name = null;
        int commandPort = SICCT_DISCOVERY_PORT;

        while (pos < end) {
            if (pos >= end)
                break;
            int tag = data[pos++] & 0xFF;
            if (pos >= end)
                break;

            int len = berReadLength(data, pos);
            if (len < 0)
                break;
            pos += berLengthWidth(data, pos);
            if (pos + len > end)
                break;

            byte[] value = Arrays.copyOfRange(data, pos, pos + len);
            pos += len;

            switch (tag) {
                case TAG_PROTOCOL_VERSION -> {
                    if (len == 2)
                        protocolVersion = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
                }
                case TAG_IP_ADDRESS -> {
                    if (len == 4)
                        ipBytes = value;
                }
                case TAG_MAC_ADDRESS -> {
                    if (len == 6)
                        macBytes = value;
                }
                case TAG_TERMINAL_NAME -> name = new String(value, StandardCharsets.US_ASCII).trim();
                case TAG_COMMAND_PORT -> {
                    if (len == 2)
                        commandPort = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
                }
                // all other tags (e.g. 0xA3 security protocol): skip
            }
        }

        if (ipBytes == null || macBytes == null || name == null)
            return null;
        try {
            return new DiscoveredTerminal(
                    protocolVersion,
                    InetAddress.getByAddress(ipBytes).getHostAddress(),
                    macBytes, name, commandPort);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Netty channel handler
    // -------------------------------------------------------------------------

    private static class DiscoveryResponseHandler
            extends SimpleChannelInboundHandler<DatagramPacket> {

        private final List<DiscoveredTerminal> results;

        DiscoveryResponseHandler(List<DiscoveredTerminal> results) {
            this.results = results;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            byte[] bytes = new byte[packet.content().readableBytes()];
            packet.content().readBytes(bytes);
            String sender = packet.sender().getAddress().getHostAddress();

            DiscoveredTerminal t = parseResponsePacket(bytes);
            if (t != null) {
                LOG.infof("[SICCT-DISCOVERY] Dienstbeschreibungspaket from %s: name='%s' ip=%s mac=%s port=%d",
                        sender, t.name(), t.ipAddress(), t.macAddressHex(), t.commandPort());
                results.add(t);
            } else {
                LOG.debugf("[SICCT-DISCOVERY] ignored non-SICCT UDP packet from %s (%d bytes)", sender, bytes.length);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warnf("[SICCT-DISCOVERY] channel error: %s", cause.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // BER TLV helpers
    // -------------------------------------------------------------------------

    static int berReadLength(byte[] data, int pos) {
        if (pos >= data.length)
            return -1;
        int b = data[pos] & 0xFF;
        if (b <= 0x7F)
            return b;
        if (b == 0x81 && pos + 1 < data.length)
            return data[pos + 1] & 0xFF;
        if (b == 0x82 && pos + 2 < data.length)
            return ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
        return -1;
    }

    static int berLengthWidth(byte[] data, int pos) {
        if (pos >= data.length)
            return 0;
        int b = data[pos] & 0xFF;
        if (b <= 0x7F)
            return 1;
        if (b == 0x81)
            return 2;
        if (b == 0x82)
            return 3;
        return 0;
    }

    // -------------------------------------------------------------------------
    // Network helpers
    // -------------------------------------------------------------------------

    static InetAddress getLocalIpAddress() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || ni.isLoopback() || ni.isVirtual())
                continue;
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();
                if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                    return addr;
                }
            }
        }
        return InetAddress.getLocalHost();
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Represents a SICCT terminal discovered via the Dienstanfrage protocol.
     *
     * @param protocolVersion 16-bit: high byte = major, low byte = minor
     * @param ipAddress       terminal IP as dotted-decimal string
     * @param macAddress      terminal MAC (6 bytes, MSB first per §6.2.3.3)
     * @param name            SICCT-Terminalname (ASCII, max 32 chars)
     * @param commandPort     TCP port of the SICCT Kommandointerpreter
     */
    public record DiscoveredTerminal(
            int protocolVersion,
            String ipAddress,
            byte[] macAddress,
            String name,
            int commandPort) {
        public String macAddressHex() {
            StringBuilder sb = new StringBuilder(17);
            for (int i = 0; i < macAddress.length; i++) {
                if (i > 0)
                    sb.append(':');
                sb.append(String.format("%02X", macAddress[i] & 0xFF));
            }
            return sb.toString();
        }

        public int protocolVersionMajor() {
            return (protocolVersion >> 8) & 0xFF;
        }

        public int protocolVersionMinor() {
            return protocolVersion & 0xFF;
        }
    }

    public static void main(String[] args) throws Exception {
        CardTerminalDiscovery discovery = new CardTerminalDiscovery();
        discovery.broadcastAddress = "192.168.100.255";
        discovery.discoveryPort = 4742;
        discovery.responseWaitMs = 3000;
        List<DiscoveredTerminal> terminals = discovery.discover();
        for (DiscoveredTerminal t : terminals) {
            System.out.printf("Found terminal: name='%s' ip=%s mac=%s port=%d protocolVersion=%d.%d%n",
                    t.name(), t.ipAddress(), t.macAddressHex(), t.commandPort(),
                    t.protocolVersionMajor(), t.protocolVersionMinor());
        }
    }
}
