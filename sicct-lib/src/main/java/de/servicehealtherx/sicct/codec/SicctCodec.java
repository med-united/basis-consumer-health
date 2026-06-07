package de.servicehealtherx.sicct.codec;

import java.io.IOException;
import java.util.List;

import org.jboss.logging.Logger;

import com.beanit.asn1bean.ber.ReverseByteArrayOutputStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import sicct.protocol._1._3._0.CTSESSDO;
import sicct.protocol._1._3._0.CommandAPDU;
import sicct.protocol._1._3._0.CommandAPDU.CommandData;
import sicct.protocol._1._3._0.CommandHeader;
import sicct.protocol._1._3._0.SicctDataObject;
import sicct.protocol._1._3._0.SicctEnvelope;
import sicct.protocol._1._3._0.SicctPayload;

public class SicctCodec {

    private static final Logger LOG = Logger.getLogger(SicctCodec.class);

    public static ByteBuf encode(SicctEnvelope e) {
        try {
            SicctPayload payload = e.getAbCmd();
            ByteBuf bufPayload = encode(payload);

            ByteBuf buf = Unpooled.buffer(12 + bufPayload.readableBytes());
            buf.writeByte(e.getBMessageType().byteValue()); // 1 Byte LE
            buf.writeShort(e.getWSrcOrDesAddr().shortValue()); // 2 Bytes LE
            buf.writeShort(e.getWSeq().shortValue()); // 2 Bytes LE
            buf.writeByte(0x00); // Padding? oder High-Byte dwLength
            buf.writeInt(bufPayload.readableBytes()); // 4 Bytes LE ← dwLength
            buf.writeBytes(bufPayload); // Payload

            return buf;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to encode payload", ex);
        }
    }

    public static ByteBuf encode(SicctPayload sicctPayload) throws IOException {
        try (ByteBufOutputStream bbos = new ByteBufOutputStream(Unpooled.buffer())) {

            CommandAPDU commandApdu = sicctPayload.getCommandApdu();

            CommandHeader header = commandApdu.getHeader();

            bbos.writeByte(header.getCla().byteValue());
            bbos.writeByte(header.getIns().byteValue());
            bbos.writeByte(header.getP1().byteValue());
            bbos.writeByte(header.getP2().byteValue());

            CommandData commandData = commandApdu.getCommandData();

            List<SicctDataObject> sicctDataObjects = commandData.getSicctDataObject();

            for (SicctDataObject dataObject : sicctDataObjects) {
                CTSESSDO ctsess = dataObject.getCtsess();
                if (ctsess != null) {
                    ByteBuf bufCtsess = encode(ctsess);
                    bbos.writeByte(bufCtsess.readableBytes()); // Lc of APDU
                    bbos.buffer().writeBytes(bufCtsess);
                    continue;
                }
                try {
                    dataObject.encode(bbos);
                } catch (IOException ex) {
                    LOG.debugf(ex,
                            "Failed to encode data object: %s. This is normal and happens when no data was supplied",
                            dataObject);
                }
            }
            bbos.writeByte(0); // Le of APDU (0 = no response data expected)
            return bbos.buffer();
        }
    }

    public static ByteBuf encode(CTSESSDO ctsessdo) throws IOException {
        try (ReverseByteArrayOutputStream reverseOS = new ReverseByteArrayOutputStream(1024)) {
            ctsessdo.encode(reverseOS, true);
            return Unpooled.wrappedBuffer(reverseOS.getArray());
        }
    }

    public static SicctEnvelope decode(ByteBuf in) throws IOException {
        // Implement decoding logic to read from ByteBuf and construct a SicctEnvelope
        // This will involve reading the header fields and then the payload based on the
        // length
        try (ByteBufInputStream bais = new ByteBufInputStream(in)) {
            bais.readAllBytes();
        }
        return null; // Placeholder for actual implementation
    }
}
