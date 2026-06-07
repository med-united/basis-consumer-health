package de.servicehealtherx.quarkus.sicct.runtime;

import java.io.InputStream;
import java.util.List;

import org.jboss.logging.Logger;

import de.servicehealtherx.sicct.codec.SicctCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import sicct.protocol._1._3._0.SicctEnvelope;

public class SicctDecoder extends ByteToMessageDecoder {

    private static final Logger LOG = Logger.getLogger(SicctDecoder.class);

    public SicctDecoder() {

    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {

        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        LOG.debugf("Received SICCT message: %s", bytesToHex(bytes));
        in.resetReaderIndex();

        SicctEnvelope decodedEnvelope = SicctCodec.decode(in);
        if (decodedEnvelope != null) {
            out.add(decodedEnvelope);
        }

    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

}
