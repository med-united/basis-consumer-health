package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TucKon005CardToCardAuth {

    public TucGenerationResult generateCardToCardAuth(
            CardSession sourceCard, CardSession targetCard,
            KeyRef keyRef, AuthMode authMode) {

        if (sourceCard.lockOwner() != null) {
            throw TucException.cardReservedByOther("TUC_KON_005");
        }

        var steps = new ArrayList<GeneratedApduStep>();

        // GET CHALLENGE on target card
        var getChallenge = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_GET_CHALLENGE, 0x00, 0x00, 8);
        steps.add(new GeneratedApduStep(getChallenge, ExpectedStatusSet.successOnly(), "GET CHALLENGE (target)"));

        // INTERNAL AUTHENTICATE on source card with challenge from target
        var intAuth = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_INTERNAL_AUTHENTICATE,
                0x00, keyRef.reference() & 0xFF);
        steps.add(new GeneratedApduStep(intAuth, ExpectedStatusSet.successOnly(),
                "INTERNAL AUTHENTICATE (source, " + keyRef.cosName() + ")"));

        // EXTERNAL AUTHENTICATE on target card with response from source
        var extAuth = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_EXTERNAL_AUTHENTICATE, 0x00, 0x00);
        steps.add(new GeneratedApduStep(extAuth, ExpectedStatusSet.successOnly(), "EXTERNAL AUTHENTICATE (target)"));

        return TucGenerationResult.of(steps, Map.of("markKeyAuthenticated", keyRef));
    }
}
