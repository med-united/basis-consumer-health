package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon202ReadFile {

    public TucGenerationResult generateReadFile(CardSession cardSession, short fileIdentifier, int offset, int length) {
        byte[] fid = new byte[]{(byte) (fileIdentifier >> 8), (byte) (fileIdentifier & 0xFF)};
        var select = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fid);
        var selectStep = new GeneratedApduStep(select, ExpectedStatusSet.successOnly(),
                "SELECT EF " + String.format("%04X", fileIdentifier & 0xFFFF));

        var readBinary = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY,
                (offset >> 8) & 0x7F, offset & 0xFF, length);
        var readStep = new GeneratedApduStep(readBinary, ExpectedStatusSet.successOnly(),
                "READ BINARY offset=" + offset + " length=" + length);

        return TucGenerationResult.of(List.of(selectStep, readStep));
    }
}
