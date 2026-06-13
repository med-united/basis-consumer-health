package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import java.util.List;
import java.util.Map;

public final class TucKon036ProvideProfessionalRole {

    // C.AUT certificate file identifier
    private static final short FID_C_AUT = (short) 0x2F00;

    private final TucKon216ReadCertificate readCertificate = new TucKon216ReadCertificate();

    public TucGenerationResult generateProvideProfessionalRole(CardSession cardSession) {
        if (cardSession.cardType() == CardType.KVK) {
            return TucGenerationResult.of(List.of(), Map.of("professionalRole", List.of("Versicherter")));
        }
        var certResult = readCertificate.generateReadCertificate(cardSession, FID_C_AUT);
        return TucGenerationResult.of(certResult.steps(), Map.of("parseProfessionOids", true));
    }
}
