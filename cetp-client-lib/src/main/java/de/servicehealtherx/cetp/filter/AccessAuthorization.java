package de.servicehealtherx.cetp.filter;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/**
 * TUC_KON_000 access-authorisation seam used by {@link AccessAuthorizationFilter} (and by Subscribe,
 * FR-011). The default bean is permissive; a konnektor deployment overrides it (e.g. via a CDI
 * {@code @Alternative} or {@code @Priority}) with the real Zugriffsberechtigungsdienst check.
 */
@ApplicationScoped
@DefaultBean
public class AccessAuthorization {

    /**
     * @return {@code true} if the given context (and optional card parameters) may receive the event
     */
    public boolean isAuthorized(String mandantId, String clientSystemId, String workplaceId,
            Map<String, String> parameters) {
        return true;
    }
}
