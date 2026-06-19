package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import com.unboundid.ldap.sdk.LDAPException;

/**
 * Creates {@link VzdConnection}s to the Verzeichnisdienst der TI-Plattform (VZD).
 * One upstream connection is established per inbound client connection.
 */
public interface VzdConnectionFactory {

    /** Opens a new connection to the configured VZD. */
    VzdConnection connect() throws LDAPException;
}
