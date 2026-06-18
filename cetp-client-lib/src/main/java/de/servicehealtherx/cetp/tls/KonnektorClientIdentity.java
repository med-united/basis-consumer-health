package de.servicehealtherx.cetp.tls;

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Qualifies the konnektor's TLS client-authentication {@link javax.net.ssl.KeyManagerFactory}
 * (the SmkCSAKAut / C.AK.AUT identity, A_21760-02). A konnektor deployment that owns SmkCSAKAut
 * (e.g. {@code konnektor-soap-server}) produces it; if no producer exists, CETP TLS proceeds with
 * server-authentication only (a valid CETP variant per TIP1-A_5009), keeping {@code cetp-client-lib}
 * decoupled from {@code quarkus-sicct-extension}.
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface KonnektorClientIdentity {
}
