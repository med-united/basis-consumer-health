package de.servicehealtherx.apdu.card;

import java.io.ByteArrayOutputStream;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;
import de.servicehealtherx.apdu.vsdm.EgkVsdmFile;

/**
 * Reads an elementary file from the eGK's DF.HCA application. Generalises the multi-block
 * READ-BINARY loop proven by {@link CardCertificateReader} for arbitrary VSDM EFs, and optionally
 * routes each command/response through an {@link ApduSecureChannel} (used for EF.GVD after C2C).
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

    /** SELECT DF.HCA (by AID). Must be called once before reading any VSDM EF. */
    public void selectHca(CardReaderPort port, int slotNo) throws CardTransportException {
        CommandAPDU select = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_DF_NAME, 0x0C, GematikISO7816.AID_DF_HCA);
        ResponseAPDU resp = port.transmit(slotNo, select);
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new ApduExecutionException("SELECT DF.HCA", resp.getSW());
        }
    }

    /** Read an AlwaysRead VSDM file (plain APDUs, no Secure Messaging). */
    public byte[] read(CardReaderPort port, int slotNo, EgkVsdmFile file) throws CardTransportException {
        return read(port, slotNo, file, ApduSecureChannel.NONE);
    }

    /**
     * Read a VSDM file, routing the SELECT/READ-BINARY APDUs through {@code channel}. For EF.GVD pass
     * the C2C Secure-Messaging session; for AlwaysRead files pass {@link ApduSecureChannel#NONE}.
     *
     * @throws ApduExecutionException on a SELECT/READ failure (mapped by callers to VSDM 3011)
     */
    public byte[] read(CardReaderPort port, int slotNo, EgkVsdmFile file, ApduSecureChannel channel)
            throws CardTransportException {
        short fid = file.fileIdentifier();
        byte[] fidBytes = {(byte) (fid >> 8), (byte) (fid & 0xFF)};
        CommandAPDU select = channel.wrap(new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fidBytes));
        ResponseAPDU selResp = channel.unwrap(port.transmit(slotNo, select));
        if (selResp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new ApduExecutionException("SELECT " + file + " " + String.format("%04X", fid & 0xFFFF), selResp.getSW());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < MAX_OFFSET) {
            CommandAPDU read = channel.wrap(new CommandAPDU(
                    GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY,
                    (offset >> 8) & 0x7F, offset & 0xFF, 256));
            ResponseAPDU resp = channel.unwrap(port.transmit(slotNo, read));
            int sw = resp.getSW();
            if (sw != GematikISO7816.SW_SUCCESS && sw != SW_END_OF_FILE) {
                throw new ApduExecutionException("READ BINARY " + file + " offset=" + offset, sw);
            }
            byte[] chunk = resp.getData();
            out.write(chunk, 0, chunk.length);
            offset += chunk.length;
            if (sw == SW_END_OF_FILE || chunk.length < 256 || chunk.length == 0) {
                break;
            }
        }
        return out.toByteArray();
    }
}
