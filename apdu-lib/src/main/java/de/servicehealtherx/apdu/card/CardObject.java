package de.servicehealtherx.apdu.card;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import de.servicehealtherx.apdu.model.CardType;

/**
 * The runtime representation of a single inserted card and the entry type held in CM_CARD_LIST
 * (gemSpec_Kon §4.1.5 calls it the <em>CardObject</em>; clients reference it by {@code cardHandle}).
 *
 * <p>Identical in structure whether created by the PC/SC or the SICCT provider — the originating
 * transport is recorded only via {@code ctid} (data-model §CardObject). Reuses the feature-007
 * {@link CardType} enum as the single card-type source of truth.
 *
 * <p>Mutable for the fields updated asynchronously after creation ({@code certStatus},
 * {@code certOcspResponse}) and for the session list; all other fields are set once at creation.
 */
public final class CardObject {

    private final String cardHandle;
    private final UUID ctid;
    private final int slotNo;
    private final String iccsn;            // nullable if unreadable (FR-005)
    private final CardType type;
    private final CardVersionInfo cardVersion;
    private final Instant insertTime;
    private final String cardHolderName;   // nullable
    private final String kvnr;             // non-null for eGK only (FR-010)
    private final LocalDate certExpirationDate; // nullable

    private volatile CertStatus certStatus = CertStatus.NOT_AVAILABLE;
    private volatile OcspResult certOcspResponse = OcspResult.NOT_AVAILABLE;

    private final List<CardSessionContext> cardSessionList = new ArrayList<>();

    private CardObject(Builder b) {
        this.cardHandle = Objects.requireNonNull(b.cardHandle, "cardHandle");
        this.ctid = Objects.requireNonNull(b.ctid, "ctid");
        if (b.slotNo < 1) {
            throw new IllegalArgumentException("slotNo must be >= 1, was " + b.slotNo);
        }
        this.slotNo = b.slotNo;
        this.iccsn = b.iccsn;
        this.type = Objects.requireNonNull(b.type, "type");
        this.cardVersion = b.cardVersion != null ? b.cardVersion : CardVersionInfo.empty();
        this.insertTime = Objects.requireNonNull(b.insertTime, "insertTime");
        this.cardHolderName = b.cardHolderName;
        this.kvnr = b.kvnr;
        this.certExpirationDate = b.certExpirationDate;
    }

    public String cardHandle() {
        return cardHandle;
    }

    public UUID ctid() {
        return ctid;
    }

    public int slotNo() {
        return slotNo;
    }

    public String iccsn() {
        return iccsn;
    }

    public CardType type() {
        return type;
    }

    public CardVersionInfo cardVersion() {
        return cardVersion;
    }

    public Instant insertTime() {
        return insertTime;
    }

    public String cardHolderName() {
        return cardHolderName;
    }

    public String kvnr() {
        return kvnr;
    }

    public LocalDate certExpirationDate() {
        return certExpirationDate;
    }

    public CertStatus certStatus() {
        return certStatus;
    }

    /** Updated asynchronously by TUC_KON_037 validation (FR-012). */
    public void setCertStatus(CertStatus certStatus) {
        this.certStatus = Objects.requireNonNull(certStatus, "certStatus");
    }

    public OcspResult certOcspResponse() {
        return certOcspResponse;
    }

    /** Updated asynchronously by TUC_KON_037 validation (FR-013). */
    public void setCertOcspResponse(OcspResult certOcspResponse) {
        this.certOcspResponse = Objects.requireNonNull(certOcspResponse, "certOcspResponse");
    }

    /** Mutable list of active session contexts; starts empty (FR-014, FR-043). */
    public List<CardSessionContext> cardSessionList() {
        return cardSessionList;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for a CardObject; {@code cardHandle} defaults to a fresh random UUID (FR-003). */
    public static final class Builder {
        private String cardHandle = UUID.randomUUID().toString();
        private UUID ctid;
        private int slotNo;
        private String iccsn;
        private CardType type;
        private CardVersionInfo cardVersion;
        private Instant insertTime = Instant.now();
        private String cardHolderName;
        private String kvnr;
        private LocalDate certExpirationDate;

        public Builder cardHandle(String cardHandle) {
            this.cardHandle = cardHandle;
            return this;
        }

        public Builder ctid(UUID ctid) {
            this.ctid = ctid;
            return this;
        }

        public Builder slotNo(int slotNo) {
            this.slotNo = slotNo;
            return this;
        }

        public Builder iccsn(String iccsn) {
            this.iccsn = iccsn;
            return this;
        }

        public Builder type(CardType type) {
            this.type = type;
            return this;
        }

        public Builder cardVersion(CardVersionInfo cardVersion) {
            this.cardVersion = cardVersion;
            return this;
        }

        public Builder insertTime(Instant insertTime) {
            this.insertTime = insertTime;
            return this;
        }

        public Builder cardHolderName(String cardHolderName) {
            this.cardHolderName = cardHolderName;
            return this;
        }

        public Builder kvnr(String kvnr) {
            this.kvnr = kvnr;
            return this;
        }

        public Builder certExpirationDate(LocalDate certExpirationDate) {
            this.certExpirationDate = certExpirationDate;
            return this;
        }

        public CardObject build() {
            return new CardObject(this);
        }
    }
}
