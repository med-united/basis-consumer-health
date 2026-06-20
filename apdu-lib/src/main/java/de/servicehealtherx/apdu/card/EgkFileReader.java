package de.servicehealtherx.apdu.card;

import java.io.ByteArrayOutputStream;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * Reads an elementary file from the eGK's DF.HCA application. Generalises the multi-block
 * READ-BINARY loop proven by {@link CardCertificateReader} for arbitrary EFs (identified by their
 * two-byte file identifier), and optionally routes each command/response through an
 * {@link ApduSecureChannel} (used for EF.GVD after C2C).
 *
 * <p>The payloads are returned <strong>byte-for-byte</strong> as stored on the card (the VSDM
 * containers are gzip-compressed on the eGK) — this reader does not decompress, parse or validate
 * the content (FR-005).
 */
public final class EgkFileReader {

    /** SW returned when READ BINARY reaches end-of-file but still delivered the trailing bytes. */
    private static final int SW_END_OF_FILE = 0x6282;

    /** Upper bound on a single EF read (short offset addressing is limited to 0x7FFF). */
    private static final int MAX_OFFSET = 0x8000;

    /**
     * READ BINARY chunk size. {@code 0xFF} rather than 256 (Le=0x00): some contact readers reject the
     * Le=0x00 (256) short form with SW 6700, while every reader accepts an explicit Le of 255.
     */
    private static final int READ_CHUNK = 0xFF;

    /** SELECT DF.HCA (by AID). Must be called once before reading any EF in that application. */
    public void selectHca(CardReaderPort port, int slotNo) throws CardTransportException {
        CommandAPDU select = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_DF_NAME, 0x0C, GematikISO7816.AID_DF_HCA);
        ResponseAPDU resp = port.transmit(slotNo, select);
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new ApduExecutionException("SELECT DF.HCA", resp.getSW());
        }
    }

    /** Read an AlwaysRead file by its two-byte file identifier (plain APDUs, no Secure Messaging). */
    public byte[] read(CardReaderPort port, int slotNo, short fileIdentifier) throws CardTransportException {
        return read(port, slotNo, fileIdentifier, ApduSecureChannel.NONE);
    }

    /**
     * Read the EF with {@code fileIdentifier}, routing the SELECT/READ-BINARY APDUs through
     * {@code channel}. For EF.GVD pass the C2C Secure-Messaging session; for AlwaysRead files pass
     * {@link ApduSecureChannel#NONE}.
     *
     * @throws ApduExecutionException on a SELECT/READ failure (mapped by callers to VSDM 3011)
     */
    public byte[] read(CardReaderPort port, int slotNo, short fileIdentifier, ApduSecureChannel channel)
            throws CardTransportException {
        short fid = fileIdentifier;
        String label = "EF " + String.format("%04X", fid & 0xFFFF);
        byte[] fidBytes = {(byte) (fid >> 8), (byte) (fid & 0xFF)};
        CommandAPDU select = channel.wrap(new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fidBytes));
        ResponseAPDU selResp = channel.unwrap(port.transmit(slotNo, select));
        if (selResp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new ApduExecutionException("SELECT " + label, selResp.getSW());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < MAX_OFFSET) {
            CommandAPDU read = channel.wrap(new CommandAPDU(
                    GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY,
                    (offset >> 8) & 0x7F, offset & 0xFF, READ_CHUNK));
            ResponseAPDU resp = channel.unwrap(port.transmit(slotNo, read));
            int sw = resp.getSW();
            if (sw != GematikISO7816.SW_SUCCESS && sw != SW_END_OF_FILE) {
                throw new ApduExecutionException("READ BINARY " + label + " offset=" + offset, sw);
            }
            byte[] chunk = resp.getData();
            out.write(chunk, 0, chunk.length);
            offset += chunk.length;
            if (sw == SW_END_OF_FILE || chunk.length < READ_CHUNK || chunk.length == 0) {
                break;
            }
        }
        return out.toByteArray();
    }
}
