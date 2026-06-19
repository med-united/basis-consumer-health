package de.servicehealtherx.apdu.c2c;

import java.util.Optional;

import de.servicehealtherx.apdu.card.ApduSecureChannel;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.card.transport.CardTransportException;

/**
 * Performs the card-to-card (C2C) authentication between the eGK and an HBA/SMC-B (TUC_KON_005) to
 * raise the eGK's {@code AUT_VSD} state and establish the Secure-Messaging session used to read
 * EF.GVD.
 *
 * <p>Returns the SM channel when the reading party is authorised for the protected data; returns
 * {@link Optional#empty()} when C2C did not authorise GVD (the caller then returns PD+VD+Status
 * without GVD — FR-021). A PIN security-state problem aborts with VSDM 3041 (SMC-B) / 3042 (HBA).
 */
public interface CardToCardAuthenticator {

    /** An authenticator that never authorises GVD (AlwaysRead-only path / tests). */
    CardToCardAuthenticator NONE = (resolver, egk, hpc) -> Optional.empty();

    Optional<ApduSecureChannel> authenticate(CardReaderPortResolver resolver, CardObject egk, CardObject hpc)
            throws CardTransportException;
}
