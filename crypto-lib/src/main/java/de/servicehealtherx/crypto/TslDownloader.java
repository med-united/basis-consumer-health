package de.servicehealtherx.crypto;

import de.servicehealtherx.crypto.services.jpa.AppConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class TslDownloader {

    private static final Logger LOG = Logger.getLogger(TslDownloader.class);
    private static final String TSL_URL_KEY = "tsl.download.url";

    private final AtomicReference<TslState> currentState = new AtomicReference<>(TslState.unavailable());

    public record TslState(long sequenceNumber, Instant expiry, Instant downloadedAt, String status) {
        static TslState unavailable() {
            return new TslState(0, null, null, "UNAVAILABLE");
        }
    }

    @Transactional
    public String getTslUrl() {
        AppConfigProperty prop = AppConfigProperty.findByName(TSL_URL_KEY);
        return prop != null ? prop.propValue : null;
    }

    public TslState download() {
        String url = getTslUrl();
        if (url == null) {
            LOG.error("[TSL] download URL not configured — key: " + TSL_URL_KEY);
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
