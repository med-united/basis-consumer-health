package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;
import java.util.Map;

public final class TucKon024ResetCard {

    public TucGenerationResult generateResetCard(CardSession cardSession) {
        // SELECT MF resets the card session context
        var selectMf = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT, 0x00, 0x0C);
        var step = new GeneratedApduStep(selectMf, ExpectedStatusSet.successOnly(), "SELECT MF (RESET)");
        return TucGenerationResult.of(List.of(step),
                Map.of("clearAuthState", true, "releaseLock", true));
    }
}
