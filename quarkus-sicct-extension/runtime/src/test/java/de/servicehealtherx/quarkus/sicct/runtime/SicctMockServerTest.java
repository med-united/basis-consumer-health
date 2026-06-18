package de.servicehealtherx.quarkus.sicct.runtime;

import static de.servicehealtherx.quarkus.sicct.runtime.SicctMockServer.forInstruction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.sicct.SICCT;
import de.servicehealtherx.sicct.codec.IccStatusDecoder.IccStatusValue;
import de.servicehealtherx.sicct.jpa.CardTerminal;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Drives the {@link SicctChannelHandler} against a {@link SicctMockServer} over a real (plaintext)
 * TCP connection: the konnektor's {@code channelActive} flow sends INIT CT SESSION + GET STATUS,
 * the mock terminal replies with the captured terminal answers, and we assert the channel handler
 * processed each Response-APDU correctly. Finally an unsolicited CARD INSERTED event is pushed.
 */
public class SicctMockServerTest {

    // Captured answers from a real Cherry ST-1506 terminal (see virtual-nfc-card-terminal CherryTest).
    private static final String INIT_CT_SESSION_RESPONSE =
            "83000000010000000016691213001300130C306C374569654A76534176439000";
    private static final String GET_STATUS_ALL_ICC_RESPONSE =
            "830000000300000000088004000015009000";
    private static final String GET_STATUS_MANUFACTURER_RESPONSE =
            "830000000400000000484644444543485930313230203031303020D7332020312020302"
                    + "020304B54202031202037202031202053543135303620203235303033303920203420203020203030303130399000";
    private static final String CARD_INSERTED_EVENT = "5000000009000000000484020001";

    @Test
    public void test_konnektor_processes_terminal_responses_over_mock_server() throws Exception {
        try (SicctMockServer terminal = new SicctMockServer()) {
            int port = terminal.start();

            terminal.messageStubFor(forInstruction(SICCT.INS_INIT_CT_SESSION)
                    .willRespondWith(INIT_CT_SESSION_RESPONSE));
            terminal.messageStubFor(forInstruction(SICCT.INS_GET_STATUS).withP2(SICCT.P2_GET_STATUS_ALL_ICC)
                    .willRespondWith(GET_STATUS_ALL_ICC_RESPONSE));
            terminal.messageStubFor(forInstruction(SICCT.INS_GET_STATUS)
                    .withP2(SICCT.P2_GET_STATUS_CARD_TERMINAL_MANUFACTURER)
                    .willRespondWith(GET_STATUS_MANUFACTURER_RESPONSE));

            CardTerminal cardTerminal = new CardTerminal();
            cardTerminal.hostname = "mock-terminal";
            cardTerminal.macAddress = "aa:bb:cc:dd:ee:ff";
            SicctTerminalConnection connection = new SicctTerminalConnection(cardTerminal);

            SicctTerminalManager manager = new SicctTerminalManager();
            SicctChannelHandler[] handlerRef = new SicctChannelHandler[1];
            EventLoopGroup group = new NioEventLoopGroup(1);
            try {
                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(group)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                SicctChannelHandler handler = new SicctChannelHandler(connection, manager);
                                handlerRef[0] = handler;
                                ch.pipeline().addLast(new SicctDecoder(), handler);
                            }
                        });

                Channel channel = bootstrap.connect("127.0.0.1", port).sync().channel();
                try {
                    assertTrue(terminal.awaitClient(5, TimeUnit.SECONDS), "konnektor did not connect");
                    SicctChannelHandler handler = handlerRef[0];

                    // The three channelActive commands are answered by the mock; wait for processing.
                    await(() -> handler.getSessionId() != null
                            && handler.getManufacturerInfo() != null
                            && !handler.getLastIccStatus().isEmpty());

                    assertEquals("0l7EieJvSAvC", handler.getSessionId(), "INIT CT SESSION session id");

                    assertTrue(handler.getManufacturerInfo().startsWith("DECHY0120"),
                            "manufacturer info was: " + handler.getManufacturerInfo());
                    assertEquals(handler.getManufacturerInfo(), cardTerminal.productInformation,
                            "manufacturer info should be persisted on the terminal");

                    assertEquals(4, handler.getLastIccStatus().size(), "one status byte per ICC interface");
                    assertTrue(handler.getLastIccStatus().contains(IccStatusValue.CC_SPECIFIC),
                            "slot 3 reports 0x15 (ready / specific): " + handler.getLastIccStatus());

                    // The konnektor must have sent INIT CT SESSION + both GET STATUS commands.
                    assertEquals(3, terminal.getReceivedRequests().size());

                    // An unsolicited CARD INSERTED event must be processed without correlation.
                    terminal.push(CARD_INSERTED_EVENT);
                    await(() -> "CARD_INSERTED:0001".equals(handler.getLastEvent()));
                    assertEquals("CARD_INSERTED:0001", handler.getLastEvent());
                } finally {
                    channel.close().sync();
                }
            } finally {
                group.shutdownGracefully();
            }
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within timeout");
    }
}
