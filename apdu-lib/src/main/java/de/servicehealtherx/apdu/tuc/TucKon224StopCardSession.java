package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import java.util.Map;

public final class TucKon224StopCardSession {

    private final TucKon024ResetCard resetCard = new TucKon024ResetCard();

    public TucGenerationResult generateStopCardSession(CardSession cardSession) {
        var resetResult = resetCard.generateResetCard(cardSession);
        return TucGenerationResult.of(resetResult.steps(),
                Map.of("clearAuthState", true, "releaseLock", true));
    }
}
