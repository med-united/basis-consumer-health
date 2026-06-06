package de.servicehealtherx.quarkus.sicct.runtime;

import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Parses SICCT UDP announcements and persists new CardTerminal JPA entities.
 * Reference: SICCT §6.2.3, FR-091.
 */
public class SicctDiscoveryHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final Logger LOG = Logger.getLogger(SicctDiscoveryHandler.class);

    private final SicctTerminalManager manager;

    public SicctDiscoveryHandler(SicctTerminalManager manager) {
        this.manager = manager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        try {
            byte[] bytes = new byte[packet.content().readableBytes()];
            packet.content().readBytes(bytes);

            String message = new String(bytes, "UTF-8").trim();
            String senderHost = packet.sender().getAddress().getHostAddress();
            int senderPort = packet.sender().getPort();

            LOG.infof("[SICCT-DISCOVERY] received from %s:%d: %s", senderHost, senderPort, message);

            if (message.startsWith("SICCT-ANNOUNCE:")) {
                String terminalId = parseTerminalId(message);
                persistNewTerminal(terminalId, senderHost, 4876);
                manager.connectTerminal(terminalId, senderHost, 4876);
            }
        } catch (Exception e) {
            LOG.warnf("[SICCT-DISCOVERY] failed to process UDP announcement: %s", e.getMessage());
        }
    }

    private String parseTerminalId(String message) {
        String[] parts = message.split(":");
        return parts.length > 1 ? parts[1].trim() : "unknown-" + System.currentTimeMillis();
    }

    @Transactional
    void persistNewTerminal(String terminalId, String host, int port) {
        if (CardTerminal.findByTerminalId(terminalId) == null) {
            CardTerminal terminal = new CardTerminal();
            terminal.terminalId = terminalId;
            terminal.host = host;
            terminal.port = port;
            terminal.pairingStatus = "DISCOVERED";
            terminal.persist();
            LOG.infof("[SICCT-DISCOVERY] new CardTerminal persisted: terminalId=%s host=%s", terminalId, host);
        }
    }
}
