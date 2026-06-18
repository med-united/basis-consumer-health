package de.servicehealtherx.cetp;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;

/**
 * Injectable time source so subscription {@code terminationTime} and expiry checks are
 * deterministic in tests (Principle II — deterministic tests). Production uses the system clock.
 */
@ApplicationScoped
public class TimeSource {

    private Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /** Test seam: override the clock to make {@code now()} deterministic. */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
