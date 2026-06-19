package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import com.unboundid.ldap.sdk.AsyncRequestID;
import com.unboundid.ldap.sdk.BindRequest;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;

/**
 * A single upstream connection to the Verzeichnisdienst der TI-Plattform (VZD).
 *
 * <p>Models the four system processes the LDAP-Proxy maps onto LDAPv3 operations
 * (gemSpec_Basis_Consumer Tab_Ldap_TUC_Mapping): {@code PL_TUC_VZD_BIND},
 * {@code PL_TUC_VZD_SEARCH}, {@code PL_TUC_VZD_ABANDON} and {@code PL_TUC_VZD_UNBIND}
 * (the latter via {@link #close()}).</p>
 *
 * <p>The interface seam keeps {@link LdapProxyFrontendHandler} testable without a live VZD.</p>
 */
public interface VzdConnection {

    /** Performs a synchronous bind against the VZD (PL_TUC_VZD_BIND). */
    BindResult bind(BindRequest request) throws LDAPException;

    /**
     * Issues an asynchronous search (PL_TUC_VZD_SEARCH). Results are delivered to the
     * {@code AsyncSearchResultListener} carried by the request. The returned handle is used to
     * abandon the operation.
     */
    AsyncRequestID asyncSearch(SearchRequest request) throws LDAPException;

    /** Abandons a previously issued asynchronous operation (PL_TUC_VZD_ABANDON). */
    void abandon(AsyncRequestID requestID) throws LDAPException;

    /** Closes the upstream connection (PL_TUC_VZD_UNBIND). */
    void close();
}
