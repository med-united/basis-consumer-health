package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;
import java.util.Map;

public final class TucKon216ReadCertificate {

    public TucGenerationResult generateReadCertificate(CardSession cardSession, short fileIdentifier) {
        byte[] fid = new byte[]{(byte) (fileIdentifier >> 8), (byte) (fileIdentifier & 0xFF)};
        var select = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fid);
        var selectStep = new GeneratedApduStep(select, ExpectedStatusSet.successOnly(),
                "SELECT EF " + String.format("%04X", fileIdentifier & 0xFFFF));

        var readBinary = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY, 0x00, 0x00, 256);
        var readStep = new GeneratedApduStep(readBinary, ExpectedStatusSet.successOnly(), "READ BINARY CERTIFICATE");

        return TucGenerationResult.of(List.of(selectStep, readStep),
                Map.of("certFileId", fileIdentifier));
    }
}
