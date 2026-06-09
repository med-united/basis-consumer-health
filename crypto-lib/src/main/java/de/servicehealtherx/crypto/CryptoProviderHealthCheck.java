package de.servicehealtherx.crypto;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.util.ArrayList;
import java.util.List;

@Readiness
@ApplicationScoped
public class CryptoProviderHealthCheck implements HealthCheck {

    @Inject
    Instance<CryptoProvider> cryptoProviders;

    @Override
    public HealthCheckResponse call() {
        List<KeyStoreDescriptor> stores = new ArrayList<>();
        for (CryptoProvider provider : cryptoProviders) {
            stores.addAll(provider.listKeyStores());
        }

        long total = stores.size();
        List<String> unavailable = stores.stream()
            .filter(d -> d.getAvailability() != KeyStoreAvailability.AVAILABLE)
            .map(d -> d.alias.value())
            .toList();
        long available = total - unavailable.size();

        HealthCheckResponseBuilder builder = HealthCheckResponse.named("crypto-provider")
            .withData("totalAliases", total)
            .withData("availableAliases", available);

        if (!unavailable.isEmpty()) {
            builder.withData("unavailable", String.join(",", unavailable));
            builder.down();
        } else {
            builder.up();
        }

        return builder.build();
    }
}
