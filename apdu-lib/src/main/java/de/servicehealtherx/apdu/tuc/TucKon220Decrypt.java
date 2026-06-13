package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon220Decrypt {

    public TucGenerationResult generateDecrypt(CardSession cardSession, KeyRef keyRef, AlgorithmId algorithmId, byte[] encryptedData) {
        if (cardSession.cardType() == CardType.KVK) {
            throw TucException.kvkWriteRejected("TUC_KON_220");
        }

        byte[] keyRefData = {(byte) GematikISO7816.TAG_KEY_REF, 0x01, keyRef.reference()};
        var mseKey = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_MANAGE_SECURITY_ENV,
                GematikISO7816.MSE_SET_COMPUTE, 0xB8, keyRefData);
        var mseStep = new GeneratedApduStep(mseKey, ExpectedStatusSet.successOnly(),
                "MSE SET KEY " + keyRef.cosName());

        var psoDecipher = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_PERFORM_SECURITY_OPERATION,
                0x80, 0x86, encryptedData);
        var psoStep = new GeneratedApduStep(psoDecipher, ExpectedStatusSet.successOnly(), "PSO:DECIPHER");

        return TucGenerationResult.of(List.of(mseStep, psoStep));
    }
}
