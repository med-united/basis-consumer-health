package de.servicehealtherx.quarkus.sicct.runtime;

import java.util.List;

import org.jboss.logging.Logger;

import de.servicehealtherx.sicct.codec.SicctCodec;
import io.netty.buffer.ByteBuf;
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

        if (LOG.isDebugEnabled()) {
            // Non-destructive hex dump of the bytes still in the cumulation buffer.
            LOG.debugf("Received SICCT message: %s", ByteBufUtil.hexDump(in));
        }

        // SicctCodec.decode consumes one complete SICCT frame from the buffer and returns null when
        // the frame is not yet fully available (after partially consuming its header). Mark the
        // reader index so an incomplete frame can be rewound and retried once more bytes arrive.
        // ByteToMessageDecoder calls this method again for any trailing bytes, so multiple frames
        // cumulated into a single read are each decoded in turn.
        in.markReaderIndex();
        SicctEnvelope decodedEnvelope;
        try {
            decodedEnvelope = SicctCodec.decode(in);
        } catch (Exception e) {
            in.resetReaderIndex();
            throw e;
        }

        if (decodedEnvelope == null) {
            // Not enough bytes for a complete frame yet — rewind and wait for more data.
            in.resetReaderIndex();
            return;
        }

        out.add(decodedEnvelope);
    }

}
