package de.servicehealtherx.konnektor.soap;

import de.servicehealtherx.cetp.tls.KonnektorClientIdentity;
import de.servicehealtherx.quarkus.sicct.runtime.tls.SmkCSAKAut;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import javax.net.ssl.KeyManagerFactory;

/**
 * Exposes the konnektor's TLS client-authentication identity (SmkCSAKAut / C.AK.AUT, A_21760-02) to
 * {@code cetp-client-lib} as a {@link KonnektorClientIdentity}-qualified {@link KeyManagerFactory},
 * so the CETP client presents the konnektor certificate when a client system requests client auth.
 * This keeps {@code cetp-client-lib} decoupled from {@code quarkus-sicct-extension}.
 */
@ApplicationScoped
public class CetpClientIdentityProducer {

    @Inject
    SmkCSAKAut smkCSAKAut;

    @Produces
    @KonnektorClientIdentity
    @ApplicationScoped
    public KeyManagerFactory konnektorClientIdentity() {
        return smkCSAKAut.getKeyManagerFactory();
    }
}
