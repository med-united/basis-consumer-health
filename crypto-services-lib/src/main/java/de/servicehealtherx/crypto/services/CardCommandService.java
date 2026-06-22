package de.servicehealtherx.crypto.services;

import de.servicehealtherx.apdu.card.EgkSessionLock;
import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.TrustService;
import de.servicehealtherx.crypto.services.popp.PoppScenario;
import de.servicehealtherx.crypto.services.popp.PoppScenarioVerifier;
import de.servicehealtherx.crypto.services.popp.ScenarioStep;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Drives the CardService card-session and secured-APDU operations (CardService v8.2: StartCardSession,
 * StopCardSession, SecureSendAPDU) against a real inserted card through whichever {@link CryptoProvider}
 * holds it (PC/SC or SICCT). The provider performs the actual transport via
 * {@link CryptoProvider#transmitApdu(String, byte[])}; this service owns the transport-neutral session
 * state (the eGK exclusive lock and the sessionID → cardHandle binding) and the audit trail.
 */
@ApplicationScoped
public class CardCommandService {

    private static final Logger LOG = Logger.getLogger(CardCommandService.class);

    @Inject
    Instance<CryptoProvider> cryptoProviders;

    @Inject
    AuditLogger auditLogger;

    /**
     * Trust anchor for the PoPP signer certificate (TUC_PKI_018 against the TSL). Optional so the
     * service can run — and be unit-tested — without a configured trust store; when present, an
     * untrusted signer certificate is rejected.
     */
    @Inject
    Instance<TrustService> trustService;

    private final PoppScenarioVerifier scenarioVerifier = new PoppScenarioVerifier();

    /** Per-{@code clientSessionId} replay guard: the last accepted scenario sequence counter. */
    private final ConcurrentMap<String, Integer> lastSequenceByClientSession = new ConcurrentHashMap<>();

    /**
     * Exclusive "at most one active session per eGK" lock (TUC_KON_223/224, Constraint C1). Reused
     * from {@code apdu-lib} and held once at the service layer, keyed by {@code cardHandle}.
     */
    private final EgkSessionLock sessionLock = new EgkSessionLock();

    /** Open sessions: sessionID → cardHandle. Lets StopCardSession resolve a session by its id alone. */
    private final ConcurrentMap<String, String> sessionToCardHandle = new ConcurrentHashMap<>();

    /** The collected response APDUs of a SecureSendAPDU plus how long the card took to answer. */
    public record ApduResponse(List<byte[]> responseApdus, long timeSpanMillis) {
    }

    /**
     * Open a card session for the card addressed by {@code cardHandle} (TUC_KON_223). Acquires the
     * exclusive eGK lock and returns the assigned sessionID; subsequent SecureSendAPDU calls are
     * routed to this card until {@link #stopCardSession(String)} releases it.
     *
     * @throws IllegalArgumentException if no inserted card holds {@code cardHandle}
     * @throws IllegalStateException    if the card already has an active session
     */
    public String startCardSession(String cardHandle, String holder) {
        long start = System.currentTimeMillis();
        try {
            // Resolve early so an unknown handle fails before the lock is taken.
            providerForCard(cardHandle);

            String sessionId = sessionLock.tryAcquire(cardHandle, holder)
                    .orElseThrow(() -> new IllegalStateException(
                            "Card " + cardHandle + " already has an active session"));
            sessionToCardHandle.put(sessionId, cardHandle);

            auditLogger.logSuccess(cardHandle, "START_CARD_SESSION", "-", holder,
                    System.currentTimeMillis() - start);
            return sessionId;
        } catch (Exception e) {
            auditLogger.logFailure(cardHandle, "START_CARD_SESSION", "-", holder,
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    /**
     * Close a card session by its sessionID (TUC_KON_224): release the exclusive lock and forget the
     * card binding.
     *
     * @throws IllegalArgumentException if the sessionID is unknown or already released
     */
    public void stopCardSession(String sessionId) {
        long start = System.currentTimeMillis();
        String cardHandle = sessionToCardHandle.remove(sessionId);
        try {
            boolean released = sessionLock.release(sessionId);
            if (cardHandle == null || !released) {
                throw new IllegalArgumentException("Unknown or already released sessionId");
            }
            auditLogger.logSuccess(cardHandle, "STOP_CARD_SESSION", "-", "konnektor-soap",
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            auditLogger.logFailure(cardHandle != null ? cardHandle : sessionId, "STOP_CARD_SESSION",
                    "-", "konnektor-soap", System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    /**
     * Send a signed APDU scenario to the card and collect its response APDUs (CardService v8.2
     * SecureSendAPDU; gemSpec_Kon SendApdu / TUC_KON_200/208).
     *
     * <p>The {@code signedScenario} is the compact-serialized JWS (PoPP {@code ConnectorScenarioMessage},
     * gematik api-popp 3.0.0). It is processed end-to-end:
     * <ol>
     *   <li>the JWS signature is verified against its embedded {@code x5c} signer certificate, which is
     *       (when a {@link TrustService} is configured) validated up to the TI trust anchors;</li>
     *   <li>the {@code sequenceCounter} is checked against the last one seen for the scenario's
     *       {@code clientSessionId} to reject replays/out-of-order scenarios;</li>
     *   <li>each {@code steps[]} command APDU is transmitted to the card in order and its response
     *       status word matched against the step's {@code expectedStatusWords}.</li>
     * </ol>
     *
     * <p>As the SOAP request carries no CardHandle or SessionID, it is routed to the single open card
     * session (Constraint C1 permits at most one active eGK session); callers must therefore have an
     * open session and no more than one for the routing to be unambiguous.
     *
     * @throws IllegalStateException if zero or more than one session is open
     * @throws SecurityException     if the signature, trust chain, replay counter, or an expected
     *                               status word check fails
     */
    public ApduResponse secureSendApdu(String signedScenario) {
        String cardHandle = singleActiveCardHandle();
        long start = System.currentTimeMillis();
        String clientSessionId = null;
        try {
            PoppScenario scenario = scenarioVerifier.verify(signedScenario);
            clientSessionId = scenario.clientSessionId();
            verifySignerTrust(scenario);
            enforceSequence(scenario);

            CryptoProvider provider = providerForCard(cardHandle);
            List<byte[]> responses = new ArrayList<>(scenario.steps().size());
            for (int i = 0; i < scenario.steps().size(); i++) {
                ScenarioStep step = scenario.steps().get(i);
                byte[] response = provider.transmitApdu(cardHandle, step.commandApdu());
                assertExpectedStatus(response, step, i);
                responses.add(response);
            }
            long elapsed = System.currentTimeMillis() - start;

            auditLogger.logSuccess(cardHandle, "SECURE_SEND_APDU", clientSessionId, "konnektor-soap", elapsed);
            return new ApduResponse(responses, elapsed);
        } catch (Exception e) {
            auditLogger.logFailure(cardHandle, "SECURE_SEND_APDU",
                    clientSessionId != null ? clientSessionId : "-", "konnektor-soap",
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    /** Validate the PoPP signer certificate up to the TI trust anchors, when a TrustService is wired. */
    private void verifySignerTrust(PoppScenario scenario) {
        if (trustService == null || trustService.isUnsatisfied()) {
            LOG.warn("[CardCommandService] No TrustService configured; "
                    + "PoPP signer certificate chain is NOT validated");
            return;
        }
        try {
            TrustService.VerificationResult result =
                    trustService.get().verify(scenario.signerCertificate().getEncoded(), false);
            if (!result.valid()) {
                throw new SecurityException("PoPP signer certificate is not trusted: " + result.detail());
            }
        } catch (CertificateEncodingException e) {
            throw new SecurityException("PoPP signer certificate could not be encoded for trust check", e);
        }
    }

    /**
     * Replay protection (TUC_KON_208): within a {@code clientSessionId} the sequence counter must
     * increase by exactly 1. The first scenario seen for a session is accepted as the baseline; a
     * scenario with {@code timeSpan == 0} ends the sequence and clears the counter.
     */
    private void enforceSequence(PoppScenario scenario) {
        String session = scenario.clientSessionId();
        int counter = scenario.sequenceCounter();
        lastSequenceByClientSession.compute(session, (key, previous) -> {
            if (previous != null && counter != previous + 1) {
                throw new SecurityException("PoPP scenario replay detected for clientSessionId " + session
                        + ": expected sequenceCounter " + (previous + 1) + " but got " + counter);
            }
            return counter;
        });
        if (scenario.timeSpanMillis() == 0) {
            lastSequenceByClientSession.remove(session);
        }
    }

    private static void assertExpectedStatus(byte[] response, ScenarioStep step, int index) {
        if (step.expectedStatusWords().isEmpty()) {
            return;
        }
        if (response.length < 2) {
            throw new SecurityException("Step " + index + ": response APDU too short to carry a status word");
        }
        String statusWord = HexFormat.of().formatHex(response, response.length - 2, response.length);
        if (!step.expectedStatusWords().contains(statusWord)) {
            throw new SecurityException("Step " + index + ": card returned unexpected status word " + statusWord
                    + ", expected one of " + step.expectedStatusWords());
        }
    }

    /** The cardHandle of the one open session, erroring if none or several are open. */
    private String singleActiveCardHandle() {
        var open = sessionToCardHandle.entrySet();
        if (open.isEmpty()) {
            throw new IllegalStateException("No open card session for SecureSendAPDU; call StartCardSession first");
        }
        if (open.size() > 1) {
            throw new IllegalStateException(
                    "SecureSendAPDU is ambiguous: " + open.size() + " card sessions are open");
        }
        return open.iterator().next().getValue();
    }

    /** Whether a session with the given id is currently open (exposed for diagnostics/tests). */
    public boolean isSessionOpen(String sessionId) {
        return sessionToCardHandle.containsKey(sessionId);
    }

    /** Snapshot of the open sessions (sessionID → cardHandle), for diagnostics/tests. */
    public Map<String, String> openSessions() {
        return Map.copyOf(sessionToCardHandle);
    }

    private CryptoProvider providerForCard(String cardHandle) {
        for (CryptoProvider provider : cryptoProviders) {
            if (provider.ownsCard(cardHandle)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("No card found for handle: " + cardHandle);
    }

    /** The holder identity currently owning the lock for a card, if any (diagnostics). */
    Optional<String> lockHolder(String cardHandle) {
        return sessionLock.lockHolder(cardHandle);
    }
}
