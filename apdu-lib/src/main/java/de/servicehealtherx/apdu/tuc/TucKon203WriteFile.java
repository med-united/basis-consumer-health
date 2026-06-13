package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon203WriteFile {

    public TucGenerationResult generateWriteFile(CardSession cardSession, short fileIdentifier, int offset, byte[] content) {
        if (cardSession.cardType() == CardType.KVK) {
            throw TucException.kvkWriteRejected("TUC_KON_203");
        }
        byte[] fid = new byte[]{(byte) (fileIdentifier >> 8), (byte) (fileIdentifier & 0xFF)};
        var select = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fid);
        var selectStep = new GeneratedApduStep(select, ExpectedStatusSet.successOnly(),
                "SELECT EF " + String.format("%04X", fileIdentifier & 0xFFFF));

        var updateBinary = new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_UPDATE_BINARY,
                (offset >> 8) & 0xFF, offset & 0xFF, content);
        var updateStep = new GeneratedApduStep(updateBinary, ExpectedStatusSet.successOnly(),
                "UPDATE BINARY offset=" + offset);

        return TucGenerationResult.of(List.of(selectStep, updateStep));
    }
}
