package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;
import java.util.Map;

public final class TucKon018CheckEgkBlocking {

    // EF.SPERRLISTE short file identifier
    private static final byte SFI_EF_SPERRLISTE = 0x1C;

    public TucGenerationResult generateCheckEgkBlocking(CardSession cardSession, boolean checkHcaOnly) {
        byte[] fid = new byte[]{0x2F, (byte) 0xEC};
        var select = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fid);
        var selectStep = new GeneratedApduStep(select, ExpectedStatusSet.successOnly(), "SELECT EF.SPERRLISTE");

        var readBinary = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY, 0x00, 0x00, 256);
        var readStep = new GeneratedApduStep(readBinary, ExpectedStatusSet.successOnly(), "READ BINARY EF.SPERRLISTE");

        return TucGenerationResult.of(List.of(selectStep, readStep), Map.of("checkHcaOnly", checkHcaOnly));
    }
}
