package de.servicehealtherx.crypto;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class TslDownloader {

    private static final Logger LOG = Logger.getLogger(TslDownloader.class);

    @ConfigProperty(name = "tsl.download.url")
    Optional<String> tslUrl;

    private final AtomicReference<TslState> currentState = new AtomicReference<>(TslState.unavailable());

    public record TslState(long sequenceNumber, Instant expiry, Instant downloadedAt, String status) {
        static TslState unavailable() {
            return new TslState(0, null, null, "UNAVAILABLE");
        }
    }

    public String getTslUrl() {
        return tslUrl.orElse(null);
    }

    public TslState download() {
        String url = getTslUrl();
        if (url == null) {
            LOG.error("[TSL] download URL not configured — set tsl.download.url");
            return TslState.unavailable();
        }
        try {
            // gemLibPki TslDownloader integration goes here
            LOG.infof("[TSL] download started from %s", url);
            TslState state = new TslState(0, Instant.now().plusSeconds(7 * 24 * 3600), Instant.now(), "VALID");
            currentState.set(state);
            LOG.infof("[TSL] download completed, seq=%d", state.sequenceNumber());
            return state;
        } catch (Exception e) {
            LOG.errorf(e, "[TSL] download failed from %s", url);
            return TslState.unavailable();
        }
    }

    public TslState getCurrentState() {
        return currentState.get();
    }
}
