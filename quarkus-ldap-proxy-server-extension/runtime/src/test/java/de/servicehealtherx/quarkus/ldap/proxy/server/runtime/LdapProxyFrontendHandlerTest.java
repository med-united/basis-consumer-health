package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import com.unboundid.ldap.protocol.BindRequestProtocolOp;
import com.unboundid.ldap.protocol.BindResponseProtocolOp;
import com.unboundid.ldap.protocol.LDAPMessage;
import com.unboundid.ldap.protocol.ModifyRequestProtocolOp;
import com.unboundid.ldap.protocol.SearchRequestProtocolOp;
import com.unboundid.ldap.protocol.SearchResultDoneProtocolOp;
import com.unboundid.ldap.protocol.SearchResultEntryProtocolOp;
import com.unboundid.ldap.sdk.AsyncRequestID;
import com.unboundid.ldap.sdk.AsyncSearchResultListener;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.BindRequest;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.DereferencePolicy;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.LDAPException;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdapProxyFrontendHandlerTest {

    /** Executor that runs tasks synchronously so EmbeddedChannel assertions are deterministic. */
    private static final Executor DIRECT = Runnable::run;

    /** Configurable fake upstream that records what the handler forwarded. */
    private static final class FakeVzd implements VzdConnection, VzdConnectionFactory {
        BindRequest lastBind;
        SearchRequest lastSearch;
        AsyncRequestID lastAbandon;
        boolean closed;
        int connectCount;

        @Override
        public VzdConnection connect() {
            connectCount++;
            return this;
        }

        @Override
        public BindResult bind(BindRequest request) {
            lastBind = request;
            return new BindResult(1, ResultCode.SUCCESS, null, null, null, null);
        }

        @Override
        public AsyncRequestID asyncSearch(SearchRequest request) {
            lastSearch = request;
            AsyncSearchResultListener listener =
                    (AsyncSearchResultListener) request.getSearchResultListener();
            listener.searchEntryReturned(new SearchResultEntry(
                    "cn=Praxis,dc=vzd", List.of(new Attribute("cn", "Praxis"))));
            listener.searchResultReceived(null, new SearchResult(
                    2, ResultCode.SUCCESS, null, null, null, 1, 0, null));
            return null; // handler tolerates a null request id
        }

        @Override
        public void abandon(AsyncRequestID requestID) {
            lastAbandon = requestID;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private EmbeddedChannel channelWith(FakeVzd vzd) {
        return new EmbeddedChannel(new LdapProxyFrontendHandler(vzd, DIRECT));
    }

    @Test
    void unsupportedModifyIsRejectedWithUnwillingToPerform() {
        EmbeddedChannel channel = channelWith(new FakeVzd());

        ModifyRequestProtocolOp modify = new ModifyRequestProtocolOp("cn=x,dc=vzd",
                List.of(new Modification(ModificationType.REPLACE, "cn", "y")));
        channel.writeInbound(new LDAPMessage(7, modify));

        LDAPMessage response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(7, response.getMessageID());
        assertEquals(ResultCode.UNWILLING_TO_PERFORM_INT_VALUE,
                response.getModifyResponseProtocolOp().getResultCode());
    }

    @Test
    void bindIsForwardedToVzd() {
        FakeVzd vzd = new FakeVzd();
        EmbeddedChannel channel = channelWith(vzd);

        channel.writeInbound(new LDAPMessage(1, new BindRequestProtocolOp("cn=admin,dc=vzd", "secret")));

        LDAPMessage response = channel.readOutbound();
        BindResponseProtocolOp bindResponse = response.getBindResponseProtocolOp();
        assertEquals(ResultCode.SUCCESS_INT_VALUE, bindResponse.getResultCode());
        assertNotNull(vzd.lastBind);
        assertEquals(1, vzd.connectCount);
    }

    @Test
    void searchStreamsEntriesThenDone() throws Exception {
        FakeVzd vzd = new FakeVzd();
        EmbeddedChannel channel = channelWith(vzd);

        SearchRequestProtocolOp search = new SearchRequestProtocolOp(
                "dc=vzd,dc=ti,dc=de", com.unboundid.ldap.sdk.SearchScope.SUB,
                DereferencePolicy.NEVER, 0, 0, false, Filter.create("(cn=Praxis*)"), List.of("cn"));
        channel.writeInbound(new LDAPMessage(5, search));

        LDAPMessage entry = channel.readOutbound();
        LDAPMessage done = channel.readOutbound();

        assertInstanceOf(SearchResultEntryProtocolOp.class, entry.getProtocolOp());
        assertEquals("cn=Praxis,dc=vzd", entry.getSearchResultEntryProtocolOp().getDN());
        assertEquals(5, entry.getMessageID());

        SearchResultDoneProtocolOp doneOp = done.getSearchResultDoneProtocolOp();
        assertEquals(ResultCode.SUCCESS_INT_VALUE, doneOp.getResultCode());
        assertEquals(5, done.getMessageID());

        // the forwarded request preserved base DN / scope
        assertEquals("dc=vzd,dc=ti,dc=de", vzd.lastSearch.getBaseDN());
    }

    @Test
    void unbindClosesUpstreamAndChannel() {
        FakeVzd vzd = new FakeVzd();
        EmbeddedChannel channel = channelWith(vzd);
        // first establish the upstream via a bind
        channel.writeInbound(new LDAPMessage(1, new BindRequestProtocolOp("cn=admin", "pw")));
        channel.readOutbound();

        channel.writeInbound(new LDAPMessage(2, new com.unboundid.ldap.protocol.UnbindRequestProtocolOp()));

        assertTrue(vzd.closed);
        assertNull(channel.readOutbound()); // unbind has no response
        assertTrue(!channel.isOpen());
    }

    @Test
    void searchFailureWhenUpstreamUnavailable() throws Exception {
        VzdConnectionFactory failing = () -> {
            throw new LDAPException(ResultCode.UNAVAILABLE, "VZD down");
        };
        EmbeddedChannel channel = new EmbeddedChannel(new LdapProxyFrontendHandler(failing, DIRECT));

        SearchRequestProtocolOp search = new SearchRequestProtocolOp(
                "dc=vzd", com.unboundid.ldap.sdk.SearchScope.BASE, DereferencePolicy.NEVER, 0, 0, false,
                Filter.createPresenceFilter("objectClass"), List.of());
        channel.writeInbound(new LDAPMessage(9, search));

        LDAPMessage done = channel.readOutbound();
        assertEquals(ResultCode.UNAVAILABLE_INT_VALUE,
                done.getSearchResultDoneProtocolOp().getResultCode());
    }
}
