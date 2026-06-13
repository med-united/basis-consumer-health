package de.servicehealtherx.apdu.tuc;

import de.servicehealtherx.apdu.model.*;

import javax.smartcardio.CommandAPDU;
import java.util.List;

public final class TucKon219Sign {

    public TucGenerationResult generateSign(CardSession cardSession, KeyRef keyRef, AlgorithmId algorithmId, byte[] dataToBeSigned) {
        if (keyRef == KeyRef.C_QES && !cardSession.authState().isPinVerified(PinRef.PIN_QES)) {
            throw TucException.pinBlocked("TUC_KON_219");
        }

        byte[] keyRefData = {(byte) GematikISO7816.TAG_KEY_REF, 0x01, keyRef.reference()};
        var mseKey = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_MANAGE_SECURITY_ENV,
                GematikISO7816.MSE_SET_COMPUTE, 0xB6, keyRefData);
        var mseKeyStep = new GeneratedApduStep(mseKey, ExpectedStatusSet.successOnly(),
                "MSE SET KEY " + keyRef.cosName());

        byte[] algData = {(byte) GematikISO7816.TAG_ALGORITHM_ID, 0x01, algorithmByte(algorithmId)};
        var mseAlg = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_MANAGE_SECURITY_ENV,
                GematikISO7816.MSE_SET_COMPUTE, 0xB6, algData);
        var mseAlgStep = new GeneratedApduStep(mseAlg, ExpectedStatusSet.successOnly(),
                "MSE SET ALG " + algorithmId.name());

        var psoCds = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_PERFORM_SECURITY_OPERATION,
                0x9E, 0x9A, dataToBeSigned);
        var psoCdsStep = new GeneratedApduStep(psoCds, ExpectedStatusSet.successOnly(), "PSO:CDS");

        return TucGenerationResult.of(List.of(mseKeyStep, mseAlgStep, psoCdsStep));
    }

    private static byte algorithmByte(AlgorithmId algorithmId) {
        return switch (algorithmId) {
            case RSA_PKCS1_V1_5 -> (byte) 0x01;
            case ECDSA_SHA256 -> (byte) 0x56;
            case RSA_OAEP_SHA256 -> (byte) 0x10;
            case ECDH_AES256 -> (byte) 0x20;
        };
    }
}
