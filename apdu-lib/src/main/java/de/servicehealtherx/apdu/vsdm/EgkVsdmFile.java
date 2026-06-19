package de.servicehealtherx.apdu.vsdm;

import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * The VSDM elementary files inside DF.HCA on the eGK (gemSpec_eGK_ObjSys_G2_1 §5.4).
 *
 * <p>{@code EF.PD}, {@code EF.VD} and {@code EF.StatusVD} have READ access condition ALWAYS and are
 * readable without card-to-card authentication. {@code EF.GVD} requires the {@code AUT_VSD} security
 * state established by a card-to-card authentication (TUC_KON_005), and its READ BINARY must be
 * Secure-Messaging-wrapped — see {@link de.servicehealtherx.apdu.c2c}.
 */
public enum EgkVsdmFile {

    EF_PD(GematikISO7816.FID_EF_PD, false),
    EF_VD(GematikISO7816.FID_EF_VD, false),
    EF_STATUS_VD(GematikISO7816.FID_EF_STATUS_VD, false),
    EF_GVD(GematikISO7816.FID_EF_GVD, true);

    private final short fileIdentifier;
    private final boolean requiresCardToCard;

    EgkVsdmFile(short fileIdentifier, boolean requiresCardToCard) {
        this.fileIdentifier = fileIdentifier;
        this.requiresCardToCard = requiresCardToCard;
    }

    public short fileIdentifier() {
        return fileIdentifier;
    }

    /** Whether reading this file needs the AUT_VSD C2C state (true only for EF.GVD). */
    public boolean requiresCardToCard() {
        return requiresCardToCard;
    }
}
