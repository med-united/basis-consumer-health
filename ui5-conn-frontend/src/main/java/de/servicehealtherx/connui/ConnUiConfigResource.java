package de.servicehealtherx.connui;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;

/**
 * Exposes server-configured defaults for the UI5 SOAP client so the SPA can pre-fill the
 * invocation context (mandant / client system / workplace / user). The user may override
 * these per request; overrides never return to the server.
 *
 * <p>This payload carries only invocation-context defaults — never secrets, credentials, or
 * TLS material (Security & Data Protection principle). Optional is used because MicroProfile
 * Config treats an empty {@code defaultValue} as "no default"; absent config yields "".
 */
@Path("/conn-ui/config.json")
public class ConnUiConfigResource {

    @ConfigProperty(name = "connui.default-context.mandant-id")
    Optional<String> mandantId;

    @ConfigProperty(name = "connui.default-context.client-system-id")
    Optional<String> clientSystemId;

    @ConfigProperty(name = "connui.default-context.workplace-id")
    Optional<String> workplaceId;

    @ConfigProperty(name = "connui.default-context.user-id")
    Optional<String> userId;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getConfig() {
        return Map.of("defaultContext", Map.of(
                "mandantId", mandantId.orElse(""),
                "clientSystemId", clientSystemId.orElse(""),
                "workplaceId", workplaceId.orElse(""),
                "userId", userId.orElse("")));
    }
}
