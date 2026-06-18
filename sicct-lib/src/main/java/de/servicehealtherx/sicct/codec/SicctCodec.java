package de.servicehealtherx.sicct.codec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import javax.smartcardio.ResponseAPDU;

import org.jboss.logging.Logger;

import com.beanit.asn1bean.ber.ReverseByteArrayOutputStream;

import com.beanit.asn1bean.ber.types.BerInteger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;

import sicct.protocol._1._3._0.CTSESSDO;
import sicct.protocol._1._3._0.CommandAPDU;
import sicct.protocol._1._3._0.CommandAPDU.CommandData;
import sicct.protocol._1._3._0.CommandHeader;
import sicct.protocol._1._3._0.ResponseAPDU.ResponseData;
import sicct.protocol._1._3._0.SicctDataObject;
import sicct.protocol._1._3._0.SicctEnvelope;
import sicct.protocol._1._3._0.SicctFuAddress;
import sicct.protocol._1._3._0.SicctMessageType;
import sicct.protocol._1._3._0.SicctPayload;
import sicct.protocol._1._3._0.SicctSequenceNumber;
import sicct.protocol._1._3._0.StatusWord;

public class SicctCodec {

    private static final Logger LOG = Logger.getLogger(SicctCodec.class);

    public static ByteBuf encode(SicctEnvelope e) {
        try {
            SicctPayload payload = e.getAbCmd();

            ByteBuf bufPayload;
            // when there is no payload then the payload is a a command apdu as byte array
            if (payload.getCommandApdu() == null) {
                ByteBuf buf = Unpooled.buffer(12);
                ByteBufOutputStream bbos = new ByteBufOutputStream(buf);
                payload.encode(bbos);
                bufPayload = buf;
            } else {
                bufPayload = encode(payload);
            }

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
        if (in.readableBytes() < 10) {
            return null;
        }

        int messageType = in.readUnsignedByte();
        int srcOrDesAddr = in.readUnsignedShort();
        int seq = in.readUnsignedShort();
        in.readByte(); // RFU
        long dwLength = in.readUnsignedInt();

        if (in.readableBytes() < dwLength) {
            return null;
        }

        byte[] payloadBytes = new byte[(int) dwLength];
        in.readBytes(payloadBytes);

        SicctEnvelope envelope = new SicctEnvelope();
        envelope.setBMessageType(new SicctMessageType(messageType));
        envelope.setWSrcOrDesAddr(new SicctFuAddress(srcOrDesAddr));
        envelope.setWSeq(new SicctSequenceNumber(seq));
        envelope.setDwLength(new BerInteger(dwLength));

        // SICCT event notifications (bMessageType = 0x50) carry an EventNotification
        // payload, not a Response-APDU. Decode them into the eventData CHOICE so the
        // channel handler can process card-inserted / removed / keypad / keep-alive
        // events (gemSpec_COS / SICCT 5.5). Everything else is a Response-APDU.
        if (messageType == EVENT_MESSAGE_TYPE) {
            envelope.setAbCmd(decodeEventPayload(payloadBytes));
        } else {
            envelope.setAbCmd(decodeSicctPayload(payloadBytes));
        }

        return envelope;
    }

    /** bMessageType value for SICCT EVENT messages (0x50). */
    private static final int EVENT_MESSAGE_TYPE = 0x50;

    public static SicctPayload decodeEventPayload(byte[] payload) {
        SicctPayload sicctPayload = new SicctPayload();
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload)) {
            sicct.protocol._1._3._0.EventNotification eventNotification = new sicct.protocol._1._3._0.EventNotification();
            eventNotification.decode(in);
            sicctPayload.setEventData(eventNotification);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to decode SICCT event notification payload");
        }
        return sicctPayload;
    }

    public static SicctPayload decodeSicctPayload(byte[] payload) {

        ResponseAPDU responseApduJava = new ResponseAPDU(payload);

        SicctPayload sicctPayload = new SicctPayload();
        // fill payload with response apdu data for further processing in the channel
        // handler
        sicct.protocol._1._3._0.ResponseAPDU responseApduSicct = new sicct.protocol._1._3._0.ResponseAPDU();

        StatusWord statusWord = new StatusWord();
        statusWord.setSw1(new BerInteger(responseApduJava.getSW1()));
        statusWord.setSw2(new BerInteger(responseApduJava.getSW2()));

        responseApduSicct.setTrailer(statusWord);

        sicctPayload.setResponseApdu(responseApduSicct);

        byte[] data = responseApduJava.getData();
        ResponseData responseData = new sicct.protocol._1._3._0.ResponseAPDU.ResponseData(data);
        responseApduSicct.setResponseData(responseData);

        SicctDataObject dataObject = new SicctDataObject();
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            if (data != null && data.length > 0) {
                dataObject.decode(in);
                responseData.getSicctDataObject().add(dataObject);
            }
        } catch (IOException e) {
            LOG.warnf(e,
                    "Failed to decode data object from response APDU. This is normal and happens when no data was supplied");
        }

        return sicctPayload;
    }
}
