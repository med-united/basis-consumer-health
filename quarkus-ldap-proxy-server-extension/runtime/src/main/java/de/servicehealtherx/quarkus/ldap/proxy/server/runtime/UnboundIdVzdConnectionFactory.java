package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import com.unboundid.ldap.sdk.AsyncRequestID;
import com.unboundid.ldap.sdk.BindRequest;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import de.servicehealtherx.crypto.GSMCKtTrustManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.security.GeneralSecurityException;

/**
 * Default {@link VzdConnectionFactory} backed by the UnboundID LDAP SDK.
 *
 * <p>For LDAPS the server certificate is validated against the TI trust list via the
 * {@link GSMCKtTrustManager}-qualified {@link TrustManager} produced by {@code crypto-lib}
 * (TUC-PKI-018). The {@code ldap.proxy.vzd.trust-all} switch bypasses validation for local
 * development only.</p>
 */
@ApplicationScoped
public class UnboundIdVzdConnectionFactory implements VzdConnectionFactory {

    private static final Logger LOG = Logger.getLogger(UnboundIdVzdConnectionFactory.class);

    @Inject
    LdapProxyConfig config;

    @Inject
    @GSMCKtTrustManager
    Instance<TrustManager> tiTrustManager;

    private volatile SSLSocketFactory sslSocketFactory;

    @Override
    public VzdConnection connect() throws LDAPException {
        final LdapProxyConfig.Vzd vzd = config.vzd();
        final String host = vzd.host().orElseThrow(() -> new LDAPException(ResultCode.PARAM_ERROR,
                "ldap.proxy.vzd.host is not configured"));

        final LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(vzd.connectTimeoutMs());

        final LDAPConnection connection = vzd.useSsl()
                ? new LDAPConnection(sslSocketFactory(vzd), options, host, vzd.port())
                : new LDAPConnection(options, host, vzd.port());

        LOG.debugf("[LDAP-Proxy] opened upstream VZD connection to %s:%d (ssl=%s)",
                host, vzd.port(), vzd.useSsl());
        return new UnboundIdVzdConnection(connection);
    }

    private SSLSocketFactory sslSocketFactory(LdapProxyConfig.Vzd vzd) throws LDAPException {
        SSLSocketFactory factory = sslSocketFactory;
        if (factory != null) {
            return factory;
        }
        synchronized (this) {
            if (sslSocketFactory != null) {
                return sslSocketFactory;
            }
            final TrustManager trustManager;
            if (vzd.trustAll()) {
                LOG.warn("[LDAP-Proxy] vzd.trust-all is enabled — VZD server certificate is NOT validated. "
                        + "Use for local development only.");
                trustManager = new TrustAllTrustManager();
            } else if (tiTrustManager.isResolvable()) {
                trustManager = tiTrustManager.get();
            } else {
                throw new LDAPException(ResultCode.LOCAL_ERROR,
                        "No TI trust manager available for the LDAPS upstream and ldap.proxy.vzd.trust-all is false");
            }
            try {
                sslSocketFactory = new SSLUtil(trustManager).createSSLSocketFactory();
                return sslSocketFactory;
            } catch (GeneralSecurityException e) {
                throw new LDAPException(ResultCode.LOCAL_ERROR,
                        "Failed to create the LDAPS socket factory for the VZD upstream: " + e.getMessage(), e);
            }
        }
    }

    /** Thin adapter wrapping a live {@link LDAPConnection}. */
    static final class UnboundIdVzdConnection implements VzdConnection {

        private final LDAPConnection connection;

        UnboundIdVzdConnection(LDAPConnection connection) {
            this.connection = connection;
        }

        @Override
        public BindResult bind(BindRequest request) throws LDAPException {
            return connection.bind(request);
        }

        @Override
        public AsyncRequestID asyncSearch(SearchRequest request) throws LDAPException {
            return connection.asyncSearch(request);
        }

        @Override
        public void abandon(AsyncRequestID requestID) throws LDAPException {
            connection.abandon(requestID);
        }

        @Override
        public void close() {
            connection.close();
        }
    }
}
