package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Configuration for the Basis-Consumer LDAP-Proxy (gemSpec_Basis_Consumer 4.2 / 6.4).
 *
 * <p>The proxy exposes an LDAPv3 client-system interface ({@code Bind}, {@code Unbind},
 * {@code Search}, {@code Abandon} per A_17341-01) and forwards the operations to the
 * Verzeichnisdienst der TI-Plattform (VZD) over LDAPS.</p>
 */
@ConfigMapping(prefix = "ldap.proxy")
public interface LdapProxyConfig {

    /**
     * Whether the LDAP-Proxy server socket is started. Disabled by default so that the
     * extension is inert until a VZD upstream has been configured.
     */
    @WithDefault("false")
    boolean enabled();

    /** Local address the proxy server socket binds to. */
    @WithDefault("0.0.0.0")
    String bindAddress();

    /**
     * Local TCP port the proxy listens on for client-system / internal-module LDAP traffic.
     * Defaults to the unprivileged 1389 (the privileged 389 typically requires root).
     */
    @WithDefault("1389")
    int port();

    /** Size of the thread pool used for blocking upstream operations (bind / connect / abandon). */
    @WithDefault("8")
    int upstreamThreads();

    /** Maximum size, in bytes, of a single inbound LDAP message (BER frame). */
    @WithDefault("5242880")
    int maxMessageSize();

    /** Upstream Verzeichnisdienst (VZD) connection settings. */
    Vzd vzd();

    interface Vzd {

        /** VZD host name. Required when {@link LdapProxyConfig#enabled()} is {@code true}. */
        Optional<String> host();

        /** VZD port. Defaults to the LDAPS port 636. */
        @WithDefault("636")
        int port();

        /** Whether the upstream connection uses TLS (LDAPS). */
        @WithDefault("true")
        boolean useSsl();

        /** Connect timeout for the upstream VZD connection, in milliseconds. */
        @WithDefault("5000")
        int connectTimeoutMs();

        /**
         * INSECURE — accept any VZD server certificate without TI trust validation.
         * Intended for local development only; never enable in production.
         */
        @WithDefault("false")
        boolean trustAll();
    }
}
