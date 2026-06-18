package de.servicehealtherx.cetp.filter;

import de.servicehealtherx.cetp.KonnektorSystemEvent;
import de.servicehealtherx.cetp.subscription.Subscription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Access-authorisation filter (TUC_KON_256 step 5b): keep the subscription iff the TUC_KON_000
 * access check passes for the subscription's mandant / client-system / workplace context.
 */
@ApplicationScoped
public class AccessAuthorizationFilter implements EventDeliveryFilter {

    @Inject
    AccessAuthorization accessAuthorization;

    @Override
    public boolean keep(KonnektorSystemEvent event, Subscription subscription) {
        return accessAuthorization.isAuthorized(
                subscription.mandantId, subscription.clientSystemId, subscription.workplaceId,
                event.parameters());
    }
}
