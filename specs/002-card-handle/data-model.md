# Data Model: Card Handle

**Feature**: Card Handle (specs/002-card-handle)
**Date**: 2026-06-07

All CardHandle entities are **runtime in-memory only** unless noted otherwise. The sole persisted entity is `G2CardLog`.

---

## CardHandle

**Package**: `de.servicehealtherx.sicct.models`
**Storage**: In-memory (`CardHandleRegistry.activeHandles`)

| Field | Type | Constraints | Notes |
|---|---|---|---|
| `cardHandle` | `String` | Not null; unique in CM_CARD_LIST; not reused within 48 h | Random UUID; opaque to clients |
| `ctid` | `UUID` | Not null | FK → `CardTerminal.ctid` |
| `slotNo` | `int` | ≥ 1 | FU Index Value from SICCT slot event |
| `iccsn` | `String` | Nullable | Empty if unreadable from card |
| `type` | `CardType` | Not null | Enum; see `CardType` below |
| `cardVersion` | `CardVersion` | Not null | All sub-fields nullable individually |
| `insertTime` | `Instant` | Not null | System time at TUC_KON_001 execution |
| `cardHolderName` | `String` | Nullable | From X.509 AUT `subject.commonName` |
| `kvnr` | `String` | Nullable; non-null for eGK only | Unveränderbarer Teil der KVNR |
| `certExpirationDate` | `LocalDate` | Nullable | From X.509 AUT `validity`; ECC cert preferred |
| `certStatus` | `CertStatus` | Not null; default `NOT_AVAILABLE` | Updated async by TUC_KON_037 |
| `certOcspResponse` | `OcspResult` | Not null; default `NOT_AVAILABLE` | Updated async by TUC_KON_037 |
| `cardSessionList` | `List<CardSession>` | Not null; mutable; starts empty | One entry per active session context |

**State transitions**:
```
[card inserted] → ACTIVE (handle in CM_CARD_LIST)
ACTIVE → [card ejected / terminal disconnect] → INVALIDATED (removed from CM_CARD_LIST; 48 h blacklist entry added)
```

**Uniqueness rule**: `cardHandle` string is unique within the active list AND must not appear in the 48-hour blacklist.

---

## CardVersion

**Package**: `de.servicehealtherx.sicct.models`
**Storage**: Nested value object inside `CardHandle`

| Field | Type | Notes |
|---|---|---|
| `cosVersion` | `String` | Nullable; from EF.Version |
| `objectSystemVersion` | `String` | Nullable |
| `cardPersonalizationVersion` | `String` | Nullable |
| `dataStructureVersion` | `String` | Nullable; also in CARD/INSERTED event for eGK G1+ |
| `loggingVersion` | `String` | Nullable |
| `atrVersion` | `String` | Nullable |
| `gdoVersion` | `String` | Nullable |
| `keyInfoVersion` | `String` | Nullable |

---

## CardType (enum)

**Package**: `de.servicehealtherx.sicct.models`

| Value | German name | Notes |
|---|---|---|
| `EGK` | eGK | Electronic health card |
| `KVK` | KVK | Legacy insurance card |
| `HBA_X` | HBAx | Health professional card (includes HBA-qSig) |
| `SMB` | SMC-B | Institutional card |
| `SMC_KT` | SMC-KT / gSMC-KT | Card terminal security module |
| `UNKNOWN` | Unbekannt | Unrecognized card |

---

## CardSession (abstract base)

**Package**: `de.servicehealtherx.sicct.models`
**Storage**: In-memory; held in `CardHandle.cardSessionList`

| Field | Type | Notes |
|---|---|---|
| `mandantId` | `String` | Not null |
| `csid` | `String` | clientSystemId; not null |
| `userId` | `String` | Nullable for eGK/SM-B; mandatory for HBAx (FR-025) |
| `authState` | `List<AuthState>` | Not null; mutable; initially empty |

**Subclasses** determine the identity key and add type-specific fields.

---

## CardSession_eGK

**Extends**: `CardSession`
**Identity key**: `cardHandle` (one per eGK card, FR-022 / Constraint C1)

| Field | Type | Notes |
|---|---|---|
| `sessionID` | `String` | UUID per RFC 4122; assigned at `StartCardSession` (FR-027); null until started |
| `cardSessionTimer` | `ScheduledFuture<?>` | `CARD_SESSION_TIMEOUT` timer; null until started |
| `authBy` | `String` | cardHandle of the session that unlocked this eGK (FR-016) |

**Session lifecycle**:
```
[created by TUC_KON_026] → OPEN (no lock)
OPEN → [StartCardSession / TUC_KON_223] → LOCKED (sessionID set, timer running, card reset)
LOCKED → [StopCardSession / TUC_KON_224 or timer expiry] → OPEN (sessionID cleared, lock released, card reset)
OPEN / LOCKED → [card ejected] → GONE
```

---

## CardSession_SMB

**Extends**: `CardSession`
**Identity key**: `cardHandle + mandantId` (Constraint C3)

No additional fields. SM-B sessions have no explicit lock lifecycle and no comfort-signature mode.

---

## CardSession_HBAx

**Extends**: `CardSession`
**Identity key**: `cardHandle + csid + userId` (Constraint C2; userId mandatory per FR-025 / C16)

| Field | Type | Notes |
|---|---|---|
| `signMode` | `String` | `"PIN"` (default) or `"Comfort"` (FR-017) |
| `countRemaining` | `AtomicInteger` | Comfort signatures remaining; initialized from `SAK_COMFORT_SIGNATURE_MAX` |
| `timeRemaining` | `ScheduledFuture<?>` | Comfort signature timer; null when `signMode = PIN` |

**signMode transitions**:
```
PIN → [ActivateComfortSignature / TUC_KON_171, PIN.QES verified] → Comfort
Comfort → [DeactivateComfortSignature / TUC_KON_172, or admin disable, or countRemaining=0, or timer expiry] → PIN
```

---

## AuthState

**Package**: `de.servicehealtherx.sicct.models`
**Storage**: Value object; list element in `CardSession.authState`

| Field | Type | Notes |
|---|---|---|
| `type` | `String` | `"C2C"` or `"CHV"` |
| `keyRef` | `String` | C2C only: key reference |
| `role` | `String` | C2C only: role per gemSpec_PKI_TI#Tab_PKI_918 |
| `pinRef` | `String` | CHV only: PIN reference |

**Rules**:
- eGK G2.0: MRPIN state MUST NOT be stored (FR-020)
- Multiple entries accumulate; none are removed unless comfort signature deactivation removes PIN.QES

---

## G2CardLog (JPA entity, persisted)

**Package**: `de.servicehealtherx.sicct.jpa`
**Table**: `G2_CARD_LOG`
**Storage**: H2 via JPA; survives Konnektor restarts

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | PK; auto-generated | |
| `iccsn` | `String` | Not null; unique | ICCSN of the G2.0 card |
| `pseudonym` | `String(10)` | Not null | First 10 hex chars of SHA-256(ICCSN ‖ expirationDate) |
| `cardType` | `String` | Not null | `SMB` or `HBA_X` |
| `cardHolderName` | `String` | Nullable | |
| `expirationDate` | `LocalDate` | Not null | AUT certificate expiry |
| `lastInsertTime` | `Instant` | Not null | System time of most recent insertion |

**Upsert rule**: On card insertion, if a row with the same `iccsn` already exists, update `lastInsertTime` and `pseudonym` (expiry-date change possible after re-personalisation). Insert if absent.

---

## CardHandleRegistry (CDI bean, not an entity)

**Package**: `de.servicehealtherx.quarkus.sicct.runtime`
**Scope**: `@ApplicationScoped`

Internal state:

| Field | Type | Purpose |
|---|---|---|
| `activeHandles` | `ConcurrentHashMap<String, CardHandle>` | Primary store; keyed by `cardHandle` |
| `handlesByCtid` | `ConcurrentHashMap<UUID, List<CardHandle>>` | Inverse index; keyed by `ctid` for fast terminal invalidation |
| `recentlyInvalidated` | `ConcurrentLinkedDeque<BlacklistedEntry>` | 48-hour reuse blacklist; pruned lazily on each new handle creation |

Operations exposed to other beans:
- `register(CardHandle)` — adds to both maps; rejects duplicate cardHandle or blacklisted UUID
- `invalidate(String cardHandle)` — removes from both maps; adds to blacklist
- `invalidateAllForTerminal(UUID ctid)` — bulk invalidation on disconnect; fires `CardRemovedEvent` per handle
- `rebuildFromTerminal(CardTerminal, List<SlotInfo>)` — called on startup/reconnect; invokes `CardHandleFactory` per slot
- `findByHandle(String)` — O(1) lookup
- `findBySlot(UUID ctid, int slotNo)` — scans `handlesByCtid.get(ctid)`
- `findAll(GetCardsFilter)` — filtered view for `GetCards` SOAP operation

---

## Event Records (CDI events, not entities)

**Package**: `de.servicehealtherx.sicct.event`

| Class | Fields | gemSpec_Kon topic |
|---|---|---|
| `CardInsertedEvent` | `CardHandle handle` | `CARD/INSERTED` |
| `CardRemovedEvent` | `CardHandle handle` | `CARD/REMOVED` |
| `CertCardStatusEvent` | `CardHandle handle`, `String certStatus`, `boolean display` | `CERT/CARD/STATUS` |
| `CardSessionTimeoutEvent` | `String cardHandle`, `String sessionId`, `long timerMs` | `CARD/SESSION/TIMEOUT` |

All are immutable records fired synchronously via CDI `Event<T>.fire()`.
