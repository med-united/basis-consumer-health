package de.servicehealtherx.apdu.c2c;

import java.util.List;

import javax.smartcardio.ResponseAPDU;

/**
 * Derives the Secure-Messaging session keys (K.Enc, K.Mac) and the initial send-sequence counter
 * from the C2C ELC handshake responses.
 *
 * <p>In the gemSpec_COS §15.4.4 model the ECKA-DH shared secret and the session keys are computed
 * <strong>on-card</strong> and never leave the cards — the konnektor cannot derive them host-side.
 * The production realisation therefore requires gSMC-K/SMC-B mediation, which is hardware-bound and
 * not implemented here ({@link #ON_CARD}); it throws rather than returning a wrong key (Principle
 * VIII — throw, never stub). Tests inject a deterministic derivation to exercise the surrounding
 * orchestration.
 */
public interface SessionKeyDerivation {

    /** Production placeholder: on-card ECKA is hardware-bound; refuse rather than fabricate keys. */
    SessionKeyDerivation ON_CARD = handshakeResponses -> {
        throw new UnsupportedOperationException(
                "C2C session keys are established on-card (gemSpec_COS §15.4.4) and require gSMC-K/SMC-B "
                        + "mediation; host-side derivation is not supported");
    };

    /** @return {@code {kEnc, kMac, initialSsc}} */
    byte[][] derive(List<ResponseAPDU> handshakeResponses);
}
