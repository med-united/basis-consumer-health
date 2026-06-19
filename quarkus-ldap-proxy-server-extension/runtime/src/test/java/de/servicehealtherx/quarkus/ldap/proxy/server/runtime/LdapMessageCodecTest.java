package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import com.unboundid.ldap.protocol.LDAPMessage;
import com.unboundid.ldap.protocol.SearchRequestProtocolOp;
import com.unboundid.ldap.sdk.DereferencePolicy;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.SearchScope;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LdapMessageCodecTest {

    private static LDAPMessage sampleSearch() throws Exception {
        SearchRequestProtocolOp op = new SearchRequestProtocolOp(
                "dc=vzd,dc=ti,dc=de", SearchScope.SUB, DereferencePolicy.NEVER, 0, 0, false,
                Filter.create("(cn=Praxis*)"), List.of("cn", "telematikID"));
        return new LDAPMessage(42, op);
    }

    private static byte[] encode(LDAPMessage msg) {
        return msg.encode().encode();
    }

    @Test
    void decodesCompleteMessage() throws Exception {
        byte[] bytes = encode(sampleSearch());
        EmbeddedChannel channel = new EmbeddedChannel(new LdapMessageDecoder(1024 * 1024));

        assertEquals(true, channel.writeInbound(Unpooled.wrappedBuffer(bytes)));
        LDAPMessage decoded = channel.readInbound();

        assertNotNull(decoded);
        assertEquals(42, decoded.getMessageID());
        SearchRequestProtocolOp op = decoded.getSearchRequestProtocolOp();
        assertEquals("dc=vzd,dc=ti,dc=de", op.getBaseDN());
        assertEquals(SearchScope.SUB, op.getScope());
        assertNull(channel.readInbound());
    }

    @Test
    void waitsForFragmentedMessage() throws Exception {
        byte[] bytes = encode(sampleSearch());
        EmbeddedChannel channel = new EmbeddedChannel(new LdapMessageDecoder(1024 * 1024));

        // First fragment carries only part of the message: nothing should be decoded yet.
        int split = bytes.length / 2;
        channel.writeInbound(Unpooled.wrappedBuffer(bytes, 0, split));
        assertNull(channel.readInbound());

        // Second fragment completes the frame.
        channel.writeInbound(Unpooled.wrappedBuffer(bytes, split, bytes.length - split));
        LDAPMessage decoded = channel.readInbound();
        assertNotNull(decoded);
        assertEquals(42, decoded.getMessageID());
    }

    @Test
    void decodesTwoBackToBackMessages() throws Exception {
        byte[] one = encode(sampleSearch());
        byte[] two = encode(new LDAPMessage(43, new SearchRequestProtocolOp(
                "o=x", SearchScope.BASE, DereferencePolicy.NEVER, 0, 0, false,
                Filter.createPresenceFilter("objectClass"), List.of())));
        byte[] combined = new byte[one.length + two.length];
        System.arraycopy(one, 0, combined, 0, one.length);
        System.arraycopy(two, 0, combined, one.length, two.length);

        EmbeddedChannel channel = new EmbeddedChannel(new LdapMessageDecoder(1024 * 1024));
        channel.writeInbound(Unpooled.wrappedBuffer(combined));

        LDAPMessage first = channel.readInbound();
        LDAPMessage second = channel.readInbound();
        assertEquals(42, first.getMessageID());
        assertEquals(43, second.getMessageID());
    }

    @Test
    void encoderProducesByteIdenticalFrame() throws Exception {
        LDAPMessage msg = sampleSearch();
        EmbeddedChannel channel = new EmbeddedChannel(new LdapMessageEncoder());

        channel.writeOutbound(msg);
        ByteBuf out = channel.readOutbound();
        byte[] actual = new byte[out.readableBytes()];
        out.readBytes(actual);

        assertArrayEquals(encode(msg), actual);
    }
}
