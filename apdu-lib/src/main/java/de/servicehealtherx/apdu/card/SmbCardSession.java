package de.servicehealtherx.apdu.card;

/**
 * SM-B card session (data-model §CardSession_SMB). Identity key: {@code cardHandle + mandantId}
 * (Constraint C3). No explicit lock lifecycle and no comfort-signature mode.
 */
public final class SmbCardSession extends CardSessionContext {

    public SmbCardSession(String mandantId, String csid, String userId) {
        super(mandantId, csid, userId);
    }
}
