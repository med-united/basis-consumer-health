package de.servicehealtherx.cetp.subscription;

import de.servicehealtherx.cetp.EventSeverity;
import de.servicehealtherx.cetp.EventType;
import de.servicehealtherx.cetp.KonnektorSystemEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Bootup behaviour for the Systeminformationsdienst (gemSpec_Kon TIP1-A_4613 / TUC_KON_256 BOOTUP
 * variant): start with an empty subscription list, but notify the retained client endpoint URLs
 * with a {@code BOOTUP/BOOTUP_COMPLETE} event (empty SubscriptionID, FR-008/FR-017).
 */
@ApplicationScoped
public class BootupSubscriptionReset {

    private static final Logger LOG = Logger.getLogger(BootupSubscriptionReset.class);

    @Inject
    SubscriptionService subscriptionService;

    @Inject
    Event<KonnektorSystemEvent> bus;

    void onStart(@Observes StartupEvent event) {
        List<String> retainedEndpoints = subscriptionService.clearOnBootup();
        LOG.infof("CETP bootup: subscription list cleared; %d endpoint URL(s) retained for BOOTUP_COMPLETE",
                retainedEndpoints.size());
        if (!retainedEndpoints.isEmpty()) {
            bus.fireAsync(new KonnektorSystemEvent(
                    "BOOTUP/BOOTUP_COMPLETE", EventType.Operation, EventSeverity.Info, Map.of()));
        }
    }
}
