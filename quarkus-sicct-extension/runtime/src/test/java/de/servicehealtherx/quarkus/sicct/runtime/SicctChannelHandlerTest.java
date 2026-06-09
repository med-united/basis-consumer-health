package de.servicehealtherx.quarkus.sicct.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class SicctChannelHandlerTest {

    @Test
    public void test_sicct_channel_active() {
        // Instantiate a channel handler with mocked dependencies, then trigger
        // channelActive and verify that INIT CT SESSION is sent
        // result 6B00000001000000000E8028000008690613001300130000

        SicctTerminalConnection connection = mock(SicctTerminalConnection.class);

        SicctChannelHandler handler = new SicctChannelHandler(connection, null);
        // Mock ChannelHandlerContext and Channel to capture the output
        // Trigger channelActive and verify the output

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.alloc()).thenReturn(Unpooled.buffer().alloc());

        handler.channelActive(ctx);

        ArgumentCaptor<ByteBuf> byteBufCaptor = ArgumentCaptor.forClass(ByteBuf.class);

        verify(ctx, times(3)).writeAndFlush(byteBufCaptor.capture());

        List<ByteBuf> sentBuffers = byteBufCaptor.getAllValues();

        // Init CT SESSION
        checkMessage(sentBuffers, "6B00000001000000000E8028000008690613001300130000", 0);
        // Get Status All ICC
        checkMessage(sentBuffers, "6B0000000200000000058013008000", 1);
        // Get Status Card Terminal Manufacturer
        checkMessage(sentBuffers, "6B0000000300000000058013004600", 2);

    }

    private void checkMessage(List<ByteBuf> sentBuffers, String expected, int i) {
        ByteBuf sentBuf = sentBuffers.get(i); // Get the first sent buffer (INIT CT SESSION)
        byte[] sentBytes = new byte[sentBuf.readableBytes()];
        sentBuf.readBytes(sentBytes);
        String sentHex = bytesToHex(sentBytes);
        // Verify that the sent message contains the expected APDU data (hex string)
        assertEquals(expected, sentHex);
    }

    @Test
    public void test_format_mac_address_for_display() {
        String formatted = SicctChannelHandler.formatMacAddressForDisplay("aa:bb:cc:dd:ee:ff");
        assertEquals("AABBCC:DDEEFF", formatted);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

}
