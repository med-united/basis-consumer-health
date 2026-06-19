package de.servicehealtherx.quarkus.ldap.proxy.server.runtime;

import com.unboundid.ldap.protocol.AbandonRequestProtocolOp;
import com.unboundid.ldap.protocol.AddRequestProtocolOp;
import com.unboundid.ldap.protocol.AddResponseProtocolOp;
import com.unboundid.ldap.protocol.BindRequestProtocolOp;
import com.unboundid.ldap.protocol.BindResponseProtocolOp;
import com.unboundid.ldap.protocol.CompareRequestProtocolOp;
import com.unboundid.ldap.protocol.CompareResponseProtocolOp;
import com.unboundid.ldap.protocol.DeleteRequestProtocolOp;
import com.unboundid.ldap.protocol.DeleteResponseProtocolOp;
import com.unboundid.ldap.protocol.ExtendedRequestProtocolOp;
import com.unboundid.ldap.protocol.ExtendedResponseProtocolOp;
import com.unboundid.ldap.protocol.LDAPMessage;
import com.unboundid.ldap.protocol.ModifyDNRequestProtocolOp;
import com.unboundid.ldap.protocol.ModifyDNResponseProtocolOp;
import com.unboundid.ldap.protocol.ModifyRequestProtocolOp;
import com.unboundid.ldap.protocol.ModifyResponseProtocolOp;
import com.unboundid.ldap.protocol.ProtocolOp;
import com.unboundid.ldap.protocol.SearchRequestProtocolOp;
import com.unboundid.ldap.protocol.SearchResultDoneProtocolOp;
import com.unboundid.ldap.protocol.SearchResultEntryProtocolOp;
import com.unboundid.ldap.protocol.SearchResultReferenceProtocolOp;
import com.unboundid.ldap.sdk.AsyncRequestID;
import com.unboundid.ldap.sdk.AsyncSearchResultListener;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultReference;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-connection LDAP-Proxy handler (gemSpec_Basis_Consumer 6.4).
 *
 * <p>Implements the client-system interface required by A_17341-01: the {@code Bind},
 * {@code Unbind}, {@code Search} and {@code Abandon} operations are forwarded to the VZD;
 * every other LDAPv3 operation is answered with {@code unwillingToPerform (53)}.</p>
 *
 * <p>Blocking upstream work (connect, bind, abandon) runs on a shared {@link Executor}, but is
 * serialised per connection so that, e.g., a bind is submitted before a subsequent search.
 * Search results are streamed back as they arrive on the UnboundID reader thread. Netty channel
 * writes are thread-safe, so responses may be written from any of these threads.</p>
 */
public class LdapProxyFrontendHandler extends SimpleChannelInboundHandler<LDAPMessage> {

    private static final Logger LOG = Logger.getLogger(LdapProxyFrontendHandler.class);

    private static final String UNSUPPORTED_DIAGNOSTIC =
            "Operation not supported by the Basis-Consumer LDAP-Proxy; "
                    + "only Bind, Unbind, Search and Abandon are offered (gemSpec_Basis_Consumer A_17341-01)";

    private final VzdConnectionFactory connectionFactory;
    private final Executor upstreamExecutor;

    /** Maps an in-flight client search messageID to its upstream async request handle (for Abandon). */
    private final ConcurrentHashMap<Integer, AsyncRequestID> pendingSearches = new ConcurrentHashMap<>();

    /** Tail of the per-connection serialised task chain on {@link #upstreamExecutor}. */
    private final AtomicReference<CompletableFuture<Void>> taskChain =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    private volatile VzdConnection upstream;

    public LdapProxyFrontendHandler(VzdConnectionFactory connectionFactory, Executor upstreamExecutor) {
        this.connectionFactory = connectionFactory;
        this.upstreamExecutor = upstreamExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, LDAPMessage msg) {
        final ProtocolOp op = msg.getProtocolOp();
        final int messageID = msg.getMessageID();

        switch (op.getProtocolOpType()) {
            case LDAPMessage.PROTOCOL_OP_TYPE_BIND_REQUEST ->
                    enqueue(() -> doBind(ctx, messageID, msg.getBindRequestProtocolOp()));
            case LDAPMessage.PROTOCOL_OP_TYPE_SEARCH_REQUEST ->
                    enqueue(() -> doSearch(ctx, messageID, msg.getSearchRequestProtocolOp()));
            case LDAPMessage.PROTOCOL_OP_TYPE_ABANDON_REQUEST ->
                    enqueue(() -> doAbandon(msg.getAbandonRequestProtocolOp()));
            case LDAPMessage.PROTOCOL_OP_TYPE_UNBIND_REQUEST -> {
                LOG.debugf("[LDAP-Proxy] unbind from %s", ctx.channel().remoteAddress());
                closeUpstream();
                ctx.close();
            }
            default -> writeUnsupported(ctx, messageID, op);
        }
    }

    // -------------------------------------------------------------------------
    // Operation handlers (run on the serialised upstream executor)
    // -------------------------------------------------------------------------

    private void doBind(ChannelHandlerContext ctx, int messageID, BindRequestProtocolOp requestOp) {
        try {
            final VzdConnection up = ensureUpstream();
            final var result = up.bind(requestOp.toBindRequest());
            write(ctx, messageID, new BindResponseProtocolOp(
                    result.getResultCode().intValue(),
                    result.getMatchedDN(),
                    result.getDiagnosticMessage(),
                    toList(result.getReferralURLs()),
                    result.getServerSASLCredentials()));
        } catch (LDAPException e) {
            // bind() throws on any non-success result (including authentication failures); relay it.
            write(ctx, messageID, new BindResponseProtocolOp(
                    e.getResultCode().intValue(),
                    e.getMatchedDN(),
                    e.getDiagnosticMessage(),
                    toList(e.getReferralURLs()),
                    null));
        }
    }

    private void doSearch(ChannelHandlerContext ctx, int messageID, SearchRequestProtocolOp requestOp) {
        final AsyncSearchResultListener listener = new ProxySearchResultListener(ctx, messageID);
        try {
            final VzdConnection up = ensureUpstream();
            final SearchRequest searchRequest = new SearchRequest(
                    listener,
                    requestOp.getBaseDN(),
                    requestOp.getScope(),
                    requestOp.getDerefPolicy(),
                    requestOp.getSizeLimit(),
                    requestOp.getTimeLimit(),
                    requestOp.typesOnly(),
                    requestOp.getFilter(),
                    requestOp.getAttributes().toArray(new String[0]));
            final AsyncRequestID requestID = up.asyncSearch(searchRequest);
            if (requestID != null) {
                pendingSearches.put(messageID, requestID);
            }
        } catch (LDAPException e) {
            write(ctx, messageID, new SearchResultDoneProtocolOp(
                    e.getResultCode().intValue(),
                    e.getMatchedDN(),
                    e.getDiagnosticMessage(),
                    toList(e.getReferralURLs())));
        }
    }

    private void doAbandon(AbandonRequestProtocolOp requestOp) {
        // Abandon has no response (RFC4511 §4.11). Drop the mapping and forward to the VZD.
        final AsyncRequestID requestID = pendingSearches.remove(requestOp.getIDToAbandon());
        final VzdConnection up = upstream;
        if (requestID != null && up != null) {
            try {
                up.abandon(requestID);
            } catch (LDAPException e) {
                LOG.debugf("[LDAP-Proxy] abandon of messageID=%d failed: %s",
                        requestOp.getIDToAbandon(), e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Search result streaming
    // -------------------------------------------------------------------------

    private final class ProxySearchResultListener implements AsyncSearchResultListener {

        private final ChannelHandlerContext ctx;
        private final int messageID;

        private ProxySearchResultListener(ChannelHandlerContext ctx, int messageID) {
            this.ctx = ctx;
            this.messageID = messageID;
        }

        @Override
        public void searchEntryReturned(SearchResultEntry searchEntry) {
            write(ctx, messageID, new SearchResultEntryProtocolOp(
                    searchEntry.getDN(), new ArrayList<>(searchEntry.getAttributes())));
        }

        @Override
        public void searchReferenceReturned(SearchResultReference searchReference) {
            write(ctx, messageID, new SearchResultReferenceProtocolOp(
                    Arrays.asList(searchReference.getReferralURLs())));
        }

        @Override
        public void searchResultReceived(AsyncRequestID requestID, SearchResult searchResult) {
            pendingSearches.remove(messageID);
            write(ctx, messageID, new SearchResultDoneProtocolOp(
                    searchResult.getResultCode().intValue(),
                    searchResult.getMatchedDN(),
                    searchResult.getDiagnosticMessage(),
                    toList(searchResult.getReferralURLs())));
        }
    }

    // -------------------------------------------------------------------------
    // Unsupported operations → unwillingToPerform (53)
    // -------------------------------------------------------------------------

    private void writeUnsupported(ChannelHandlerContext ctx, int messageID, ProtocolOp op) {
        final int rc = ResultCode.UNWILLING_TO_PERFORM_INT_VALUE;
        final ProtocolOp response;
        if (op instanceof ModifyRequestProtocolOp) {
            response = new ModifyResponseProtocolOp(rc, null, UNSUPPORTED_DIAGNOSTIC, null);
        } else if (op instanceof AddRequestProtocolOp) {
            response = new AddResponseProtocolOp(rc, null, UNSUPPORTED_DIAGNOSTIC, null);
        } else if (op instanceof DeleteRequestProtocolOp) {
            response = new DeleteResponseProtocolOp(rc, null, UNSUPPORTED_DIAGNOSTIC, null);
        } else if (op instanceof ModifyDNRequestProtocolOp) {
            response = new ModifyDNResponseProtocolOp(rc, null, UNSUPPORTED_DIAGNOSTIC, null);
        } else if (op instanceof CompareRequestProtocolOp) {
            response = new CompareResponseProtocolOp(rc, null, UNSUPPORTED_DIAGNOSTIC, null);
        } else if (op instanceof ExtendedRequestProtocolOp) {
            response = new ExtendedResponseProtocolOp(rc, null, UNSUPPORTED_DIAGNOSTIC, null, null, null);
        } else {
            // A request type we cannot map to a response (e.g. a stray response op). Nothing to send.
            LOG.warnf("[LDAP-Proxy] ignoring unexpected protocol op type 0x%02X from %s",
                    op.getProtocolOpType(), ctx.channel().remoteAddress());
            return;
        }
        LOG.debugf("[LDAP-Proxy] rejecting unsupported op type 0x%02X with unwillingToPerform",
                op.getProtocolOpType());
        write(ctx, messageID, response);
    }

    // -------------------------------------------------------------------------
    // Channel lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeUpstream();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.warnf(cause, "[LDAP-Proxy] channel exception from %s — closing", ctx.channel().remoteAddress());
        closeUpstream();
        ctx.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Opens the upstream VZD connection on first use; subsequent calls reuse it. */
    private synchronized VzdConnection ensureUpstream() throws LDAPException {
        if (upstream == null) {
            upstream = connectionFactory.connect();
        }
        return upstream;
    }

    private synchronized void closeUpstream() {
        pendingSearches.clear();
        if (upstream != null) {
            try {
                upstream.close();
            } catch (RuntimeException e) {
                LOG.debugf("[LDAP-Proxy] error closing upstream VZD connection: %s", e.getMessage());
            }
            upstream = null;
        }
    }

    /** Serialises {@code task} after any previously submitted task on the shared executor. */
    private void enqueue(Runnable task) {
        taskChain.updateAndGet(prev -> prev.thenRunAsync(task, upstreamExecutor)
                .exceptionally(t -> {
                    LOG.errorf(t, "[LDAP-Proxy] upstream task failed");
                    return null;
                }));
    }

    private static void write(ChannelHandlerContext ctx, int messageID, ProtocolOp op) {
        ctx.channel().writeAndFlush(new LDAPMessage(messageID, op));
    }

    private static List<String> toList(String[] values) {
        return (values == null || values.length == 0) ? null : Arrays.asList(values);
    }
}
