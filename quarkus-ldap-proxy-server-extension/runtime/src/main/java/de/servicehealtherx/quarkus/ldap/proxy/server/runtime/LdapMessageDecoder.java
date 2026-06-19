package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import com.unboundid.asn1.ASN1Element;
import com.unboundid.asn1.ASN1Exception;
import com.unboundid.ldap.protocol.LDAPMessage;
import com.unboundid.ldap.sdk.LDAPException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

/**
 * Netty frame decoder that splits the inbound byte stream into complete LDAPv3 messages and
 * decodes them into UnboundID {@link LDAPMessage} objects.
 *
 * <p>An LDAPMessage is a BER-encoded {@code SEQUENCE} ({@code RFC4511} §4.1.1). The total
 * element length is derived from the BER tag + length header without consuming bytes, so a
 * partial message is left in the buffer until the rest arrives.</p>
 */
public class LdapMessageDecoder extends ByteToMessageDecoder {

    /** BER tag for a universal, constructed SEQUENCE — the outer tag of every LDAPMessage. */
    private static final int LDAP_MESSAGE_SEQUENCE_TYPE = 0x30;

    private final int maxMessageSize;

    public LdapMessageDecoder(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Need at least the tag byte and the first length byte to make any decision.
        if (in.readableBytes() < 2) {
            return;
        }

        final int base = in.readerIndex();

        final int type = in.getUnsignedByte(base);
        if (type != LDAP_MESSAGE_SEQUENCE_TYPE) {
            throw new CorruptedFrameException(
                    String.format("Unexpected LDAP message tag 0x%02X (expected 0x30 SEQUENCE)", type));
        }

        final int firstLengthByte = in.getUnsignedByte(base + 1);
        final int contentLength;
        final int headerLength;

        if (firstLengthByte <= 0x7F) {
            // Short form: the length is the value of this single byte.
            contentLength = firstLengthByte;
            headerLength = 2;
        } else {
            // Long form: the low 7 bits give the number of subsequent length octets.
            final int numLengthOctets = firstLengthByte & 0x7F;
            if (numLengthOctets == 0) {
                throw new CorruptedFrameException("Indefinite BER length is not permitted for LDAP messages");
            }
            if (numLengthOctets > 4) {
                throw new CorruptedFrameException("LDAP message length field too large: " + numLengthOctets + " octets");
            }
            if (in.readableBytes() < 2 + numLengthOctets) {
                return; // length field not yet fully received
            }
            int length = 0;
            for (int i = 0; i < numLengthOctets; i++) {
                length = (length << 8) | in.getUnsignedByte(base + 2 + i);
            }
            contentLength = length;
            headerLength = 2 + numLengthOctets;
        }

        if (contentLength < 0 || contentLength > maxMessageSize) {
            throw new CorruptedFrameException("LDAP message length " + contentLength
                    + " exceeds maximum of " + maxMessageSize + " bytes");
        }

        final int totalLength = headerLength + contentLength;
        if (in.readableBytes() < totalLength) {
            return; // wait for the full message
        }

        final byte[] messageBytes = new byte[totalLength];
        in.readBytes(messageBytes);

        try {
            final ASN1Element element = ASN1Element.decode(messageBytes);
            out.add(LDAPMessage.decode(element));
        } catch (ASN1Exception | LDAPException e) {
            throw new CorruptedFrameException("Failed to decode LDAP message", e);
        }
    }
}
