package de.servicehealtherx.konnektor.vsdm;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import de.servicehealtherx.apdu.c2c.CardToCardAuthException;
import de.servicehealtherx.apdu.c2c.CardToCardAuthenticator;
import de.servicehealtherx.apdu.card.ApduExecutionException;
import de.servicehealtherx.apdu.card.ApduSecureChannel;
import de.servicehealtherx.apdu.card.CardListAggregator;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CertStatus;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.EgkFileReader;
import de.servicehealtherx.apdu.card.OcspResult;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * Orchestrates the local ReadVSD use case (gemSpec_FM_VSDM §3.2.1) against the
 * inserted cards: no
 * UFS/VSDD/CMS, no Prüfungsnachweis. Resolves the card handles, validates the
 * context, reserves the
 * eGK exclusively, checks eGK usability, reads the AlwaysRead containers
 * (PD/VD/StatusVD), performs
 * the card-to-card authentication for the protected data (GVD), and returns a
 * {@link VsdReadResult}.
 *
 * <p>
 * Stateless w.r.t. insured data (FR-028): nothing is persisted; the result
 * holds only in-memory
 * byte arrays for the duration of the call. The whole operation is bounded by a
 * hard timeout
 * (FR-027) and a second call for an already-reserved eGK fails fast with a
 * card-busy fault (FR-029).
 *
 * <p>
 * Plain (non-CDI) class — {@code apdu-lib} has no Quarkus dependency; the SOAP
 * module produces it
 * as a bean.
 */
public final class ReadVsdService {

    private final Supplier<CardListAggregator> cardListSupplier;
    private final CardReaderPortResolver portResolver;
    private final CardToCardAuthenticator authenticator;
    private final EgkFileReader fileReader = new EgkFileReader();
    private final StatusVdConverter statusConverter = new StatusVdConverter();
    private final EgkAuditWriter auditWriter = new EgkAuditWriter();
    private final long timeoutMillis;

    /**
     * eGK handles currently held by an in-flight read — exclusive reservation
     * (FR-029).
     */
    private final Set<String> reservedEgkHandles = ConcurrentHashMap.newKeySet();
    private final ExecutorService timeoutExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "read-vsd");
        t.setDaemon(true);
        return t;
    });

    /**
     * Production constructor: the card view is resolved per call from the (request-scoped)
     * {@link CardListAggregator}, so a read sees the unified, transport-spanning card list across
     * every crypto provider (FR-064).
     */
    public ReadVsdService(Supplier<CardListAggregator> cardListSupplier, CardReaderPortResolver portResolver,
            CardToCardAuthenticator authenticator, long timeoutMillis) {
        this.cardListSupplier = cardListSupplier;
        this.portResolver = portResolver;
        this.authenticator = authenticator;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Convenience constructor over a single {@link CmCardList} (one transport / tests). The list is
     * wrapped in a {@link CardListAggregator} so the read path is identical to the multi-provider case.
     */
    public ReadVsdService(CmCardList cardList, CardReaderPortResolver portResolver,
            CardToCardAuthenticator authenticator, long timeoutMillis) {
        this(() -> new CardListAggregator().addSource(cardList), portResolver, authenticator, timeoutMillis);
    }

    /**
     * Execute the local ReadVSD.
     *
     * @throws VsdmReadException with the appropriate {@link VsdmErrorCode} on any
     *                           abort
     */
    public VsdReadResult read(ReadVsdRequest request) {
        validate(request);

        if (!reservedEgkHandles.add(request.ehcHandle())) {
            throw new VsdmReadException(VsdmErrorCode.CARD_BUSY, "eGK already in use by another call");
        }
        // Resolve the aggregator on the caller (request) thread: the producer's instance may be a CDI
        // request-scoped proxy, while doRead runs on a context-less executor thread. snapshot() detaches
        // a proxy-free view that shares the providers' thread-safe CM_CARD_LISTs.
        CardListAggregator cards = cardListSupplier.get().snapshot();
        Future<VsdReadResult> future = timeoutExecutor.submit(() -> doRead(request, cards));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new VsdmReadException(VsdmErrorCode.TIMEOUT, "ReadVSD exceeded the configured timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof VsdmReadException v) {
                throw v;
            }
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "ReadVSD failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "ReadVSD interrupted");
        } finally {
            reservedEgkHandles.remove(request.ehcHandle());
        }
    }

    private void validate(ReadVsdRequest request) {
        if (request.performOnlineCheck()) {
            throw new VsdmReadException(VsdmErrorCode.ONLINE_CHECK_NOT_SUPPORTED,
                    "online check not supported by the local VSDService");
        }
        if (request.readOnlineReceipt()) {
            throw new VsdmReadException(VsdmErrorCode.RECEIPT_NOT_SUPPORTED,
                    "Pruefungsnachweis not supported by the local VSDService");
        }
        if (isBlank(request.ehcHandle()) || isBlank(request.hpcHandle())
                || isBlank(request.mandantId()) || isBlank(request.clientSystemId())
                || isBlank(request.workplaceId())) {
            throw new VsdmReadException(VsdmErrorCode.INVALID_REQUEST, "missing mandatory request field");
        }
    }

    private VsdReadResult doRead(ReadVsdRequest request, CardListAggregator cardList) throws CardTransportException {
        CardObject egk = cardList.findByHandle(request.ehcHandle())
                .orElseThrow(() -> new VsdmReadException(VsdmErrorCode.INVALID_REQUEST, "unknown EhcHandle"));
        if (egk.type() != CardType.EGK) {
            throw new VsdmReadException(VsdmErrorCode.INVALID_REQUEST, "EhcHandle does not reference an eGK");
        }
        CardObject hpc = cardList.findByHandle(request.hpcHandle())
                .orElseThrow(() -> new VsdmReadException(VsdmErrorCode.INVALID_REQUEST, "unknown HpcHandle"));
        if (hpc.type() != CardType.HBA && hpc.type() != CardType.HBAX && hpc.type() != CardType.SMC_B) {
            throw new VsdmReadException(VsdmErrorCode.INVALID_REQUEST, "HpcHandle is not an HBA or SMC-B");
        }
        if ((hpc.type() == CardType.HBA || hpc.type() == CardType.HBAX) && isBlank(request.userId())) {
            throw new VsdmReadException(VsdmErrorCode.INVALID_REQUEST, "UserId required for HBA access");
        }

        checkEgkValidity(egk);

        CardReaderPort egkPort = portResolver.portFor(egk.ctid())
                .orElseThrow(() -> new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "eGK reader not available"));
        int slot = egk.slotNo();

        try {
            fileReader.selectHca(egkPort, slot);
        } catch (ApduExecutionException e) {
            throw new VsdmReadException(mapSelectHcaFailure(e.statusWord()), "SELECT DF.HCA failed");
        }

        VsdStatus status;
        try {
            status = statusConverter.convert(fileReader.read(egkPort, slot, EgkVsdmFile.EF_STATUS_VD.fileIdentifier()));
        } catch (ApduExecutionException e) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "reading EF.StatusVD failed");
        }
        if (status.isInconsistent()) {
            throw new VsdmReadException(VsdmErrorCode.VSD_INCONSISTENT, "EF.StatusVD reports open transactions");
        }

        byte[] pd = readContainer(egkPort, slot, EgkVsdmFile.EF_PD);
        byte[] vd = readContainer(egkPort, slot, EgkVsdmFile.EF_VD);

        Optional<byte[]> gvd = Optional.empty();
        Optional<ApduSecureChannel> channel;
        try {
            channel = authenticator.authenticate(portResolver, egk, hpc);
        } catch (CardToCardAuthException e) {
            throw new VsdmReadException(mapC2cFailure(e.reason()), e.detail());
        }
        if (channel.isPresent()) {
            try {
                gvd = Optional.of(fileReader.read(egkPort, slot, EgkVsdmFile.EF_GVD.fileIdentifier(), channel.get()));
            } catch (ApduExecutionException e) {
                throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "reading EF.GVD failed");
            }
            // FR-023 / VSDM-A_2654: write the data-access audit (incl. "read protected
            // VSD") once
            // the eGK is AUT_VSD-unlocked (TUC_KON_006); a write failure aborts and returns
            // no VSD.
            auditWriter.writeReadProtectedVsd(egkPort, slot, hpc, channel.get());
        }

        return new VsdReadResult(pd, vd, gvd, status);
    }

    private byte[] readContainer(CardReaderPort port, int slot, EgkVsdmFile file) throws CardTransportException {
        try {
            return fileReader.read(port, slot, file.fileIdentifier());
        } catch (ApduExecutionException e) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "reading " + file + " failed");
        }
    }

    /** Map a card-to-card authentication failure to the corresponding gematik VSDM error code. */
    private static int mapC2cFailure(CardToCardAuthException.Reason reason) {
        return switch (reason) {
            case SMB_SECURITY_STATE_INSUFFICIENT -> VsdmErrorCode.SMB_NOT_ENABLED;
            case HBA_SECURITY_STATE_INSUFFICIENT -> VsdmErrorCode.HBA_NOT_ENABLED;
            case EGK_READER_UNAVAILABLE, HPC_READER_UNAVAILABLE, CVC_READ_FAILED -> VsdmErrorCode.VSD_READ_FAILED;
        };
    }

    /**
     * eGK usability/validity gate (FR-015, TUC_KON_018). Uses the certificate
     * status that
     * TUC_KON_037 maintains on the {@link CardObject}: an online-revoked AUT
     * certificate maps to OM
     * 106 and an offline-invalid one to OM 107. The blocked-health-application case
     * (OM 114) is
     * detected at SELECT DF.HCA ({@link #mapSelectHcaFailure}).
     */
    private static void checkEgkValidity(CardObject egk) {
        if (egk.certOcspResponse() == OcspResult.REVOKED) {
            throw new VsdmReadException(VsdmErrorCode.EGK_CERT_REVOKED, "eGK AUT certificate revoked");
        }
        if (egk.certStatus() == CertStatus.INVALID) {
            throw new VsdmReadException(VsdmErrorCode.EGK_CERT_INVALID, "eGK AUT certificate invalid");
        }
    }

    /**
     * A failed SELECT DF.HCA usually means the health application is blocked/absent
     * (OM 114).
     */
    private static int mapSelectHcaFailure(int sw) {
        return switch (sw) {
            case GematikISO7816.SW_OBJECT_NOT_FOUND, 0x6285, 0x6999, GematikISO7816.SW_COMMAND_NOT_ALLOWED ->
                VsdmErrorCode.HCA_BLOCKED;
            default -> VsdmErrorCode.VSD_READ_FAILED;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
