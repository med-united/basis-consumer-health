package de.servicehealtherx.cetp.filter;

import de.servicehealtherx.cetp.KonnektorSystemEvent;
import de.servicehealtherx.cetp.subscription.Subscription;

/**
 * One stage of the TUC_KON_256 subscription-filtering pipeline (gemSpec_Kon §4.1.6.4.1, step 5):
 * topic match, access authorisation, and XPath filter. Implementations are applied in order; the
 * first that returns {@code false} drops the subscription from the delivery set.
 */
public interface EventDeliveryFilter {

    /**
     * @return {@code true} to keep the subscription for delivery of this event, {@code false} to drop it
     */
    boolean keep(KonnektorSystemEvent event, Subscription subscription);
}
