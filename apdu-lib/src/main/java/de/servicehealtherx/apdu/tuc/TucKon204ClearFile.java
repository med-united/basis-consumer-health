package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon204ClearFile {

    public TucGenerationResult generateClearFile(CardSession cardSession, short fileIdentifier) {
        if (cardSession.cardType() == CardType.KVK) {
            throw TucException.kvkWriteRejected("TUC_KON_204");
        }
        byte[] fid = new byte[]{(byte) (fileIdentifier >> 8), (byte) (fileIdentifier & 0xFF)};
        var select = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fid);
        var selectStep = new GeneratedApduStep(select, ExpectedStatusSet.successOnly(),
                "SELECT EF " + String.format("%04X", fileIdentifier & 0xFFFF));

        var eraseBinary = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_ERASE_BINARY, 0x00, 0x00);
        var eraseStep = new GeneratedApduStep(eraseBinary, ExpectedStatusSet.successOnly(), "ERASE BINARY");

        return TucGenerationResult.of(List.of(selectStep, eraseStep));
    }
}
