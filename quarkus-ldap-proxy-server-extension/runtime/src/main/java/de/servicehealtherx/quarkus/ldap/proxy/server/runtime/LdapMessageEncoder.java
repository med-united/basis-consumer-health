package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import com.unboundid.ldap.protocol.LDAPMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Netty encoder that serialises an UnboundID {@link LDAPMessage} to its BER representation
 * for transmission to the LDAP client.
 */
public class LdapMessageEncoder extends MessageToByteEncoder<LDAPMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, LDAPMessage msg, ByteBuf out) {
        out.writeBytes(msg.encode().encode());
    }
}
