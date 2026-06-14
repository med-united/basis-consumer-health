# Data Model: Card Handle

**Feature**: Card Handle (specs/002-card-handle)
**Date**: 2026-06-14

All CardHandle entities are **runtime in-memory only** unless noted otherwise. The sole persisted entity is `G2CardLog`.

**Module placement (transport-agnostic)**: CM_CARD_LIST and the card-management domain objects (`CardObject`/`CardHandle`, `CardVersion`, `CardType`, `CardSession` and subtypes, `AuthState`) live in the **`apdu-lib`** module under `de.servicehealtherx.apdu.card.*`. They MUST NOT depend on `sicct-lib` or any PC/SC-specific type. APDU transmission and card insert/remove detection are performed by the two providers through the `apdu-lib` transport port (`CardReaderPort`): `PcscCryptoProvider` (directly connected PC/SC reader) and `SicctCryptoProvider` (SICCT terminal). **CM_CARD_LIST is per-provider, not shared**: the type is defined once in `apdu-lib`, but each provider instantiates and owns its own list. The unified card view is produced by aggregating across all providers' lists.

> Package-rename note: the prior draft placed these types in `de.servicehealtherx.sicct.models`. They move to `apdu-lib` (`de.servicehealtherx.apdu.card.*`) so both crypto providers can share them without a transport dependency. SICCT-only event/JPA helpers (`G2CardLog`, CDI event records) remain in their transport/runtime modules.

---

## CM_CARD_LIST

**Package**: `de.servicehealtherx.apdu.card`
**Storage**: In-memory; the Kartendienst card-management list (gemSpec_Kon §4.1.5)
**Lifecycle**: per-provider — each CryptoProvider owns its own instance; instances are never shared between providers. The type is defined once in `apdu-lib`.

CM_CARD_LIST is the authoritative registry of every `CardObject` known to *its owning provider*. TUC_KON_001 adds an entry on insertion; the card-removal reaction removes it. The system-wide card view is the aggregation of all providers' CM_CARD_LIST instances; `cardHandle` is unique across that union.

| Aspect | Definition |
|---|---|
| Entry type | `CardObject` (a.k.a. `CardHandle`, see below) |
| Primary index | `cardHandle` → `CardObject` (`CM_CARD_LIST(cardHandle)`) |
| Secondary index | (`ctid`, `slotNo`) → `CardObject` (`CM_CARD_LIST(CtID, SlotNo)`) |
| Ownership | one instance per CryptoProvider; never shared between providers |
| Uniqueness | `cardHandle` unique system-wide, across the union of all providers' instances |
| No-reuse rule | an invalidated `cardHandle` MUST NOT be reissued within 48 h |
| Tenant scoping | queries are filtered per tenant (FR-019) |
| Transport neutrality | no field or method references a concrete transport; origin is recorded only via `ctid` |

**Operations** (each provider invokes these on its own instance):
- `add(CardObject)` — used by TUC_KON_001; rejects duplicate/blacklisted `cardHandle`
- `removeByHandle(cardHandle)` / `removeBySlot(ctid, slotNo)` — card removal / terminal disconnect; adds to 48 h blacklist
- `findByHandle(cardHandle)` — O(1) resolution used by every card-addressing TUC
- `findBySlot(ctid, slotNo)` — slot addressing (RequestCard/EjectCard, startup reconstruction)
- `removeAllForTerminal(ctid)` — bulk invalidation on disconnect of a SICCT terminal or PC/SC reader
- `findAll(GetCardsFilter)` — per-tenant view of this provider's cards; the card service concatenates the result of each provider's `findAll` to form the unified GetCards response

**State transition (per entry)**:
```
[card inserted on PC/SC reader OR SICCT terminal]
        → CardObject ADDED to CM_CARD_LIST (TUC_KON_001)
ADDED → [card removed / reader or terminal disconnect]
        → REMOVED from CM_CARD_LIST (+ 48 h blacklist entry)
```

---

## CardObject (CardHandle)

**Package**: `de.servicehealtherx.apdu.card`
**Storage**: In-memory; the entry type of CM_CARD_LIST

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

**Package**: `de.servicehealtherx.apdu.card`
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

**Package**: `de.servicehealtherx.apdu.card`

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

**Package**: `de.servicehealtherx.apdu.card`
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

**Package**: `de.servicehealtherx.apdu.card`
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

## CM_CARD_LIST implementation (class, not an entity)

**Package**: `de.servicehealtherx.apdu.card`
**Type/Scope**: a plain `CmCardList` class defined in `apdu-lib`; **each provider holds its own instance** (e.g. a `@Dependent`/per-provider bean or a field constructed by the provider). It is **not** an `@ApplicationScoped` singleton shared across providers.

This is the concrete realization of the CM_CARD_LIST entity above. It lives in `apdu-lib` so it carries no transport dependency; both providers depend on `apdu-lib`, but `PcscCryptoProvider` and `SicctCryptoProvider` each own a separate `CmCardList`.

Internal state (per instance):

| Field | Type | Purpose |
|---|---|---|
| `activeCards` | `ConcurrentHashMap<String, CardObject>` | Primary store; keyed by `cardHandle` |
| `cardsByCtid` | `ConcurrentHashMap<UUID, List<CardObject>>` | Inverse index; keyed by `ctid` (reader **or** terminal) for fast invalidation |
| `recentlyInvalidated` | `ConcurrentLinkedDeque<BlacklistedEntry>` | 48-hour reuse blacklist; pruned lazily on each new card add |

Operations (each provider invokes these on its own instance):
- `add(CardObject)` — adds to both maps; rejects duplicate cardHandle or blacklisted UUID (TUC_KON_001)
- `removeByHandle(String cardHandle)` — removes from both maps; adds to blacklist; triggers `CardRemovedEvent`
- `removeAllForTerminal(UUID ctid)` — bulk invalidation on disconnect of a SICCT terminal or PC/SC reader
- `rebuildFromReader(ReaderRef, List<SlotInfo>)` — startup/reconnect; invokes `CardObjectFactory` per slot via the providing transport
- `findByHandle(String)` — O(1) lookup within this provider's cards
- `findBySlot(UUID ctid, int slotNo)` — slot addressing
- `findAll(GetCardsFilter)` — per-tenant view of this provider's cards; the card service concatenates each provider's result for the unified `GetCards` response (system-wide `cardHandle` uniqueness keeps the union duplicate-free)

---

## CardReaderPort (transport port, defined in apdu-lib)

**Package**: `de.servicehealtherx.apdu.card.transport`
**Module**: `apdu-lib` (interface only — no transmission logic, no `sicct-lib` / PC/SC imports)

The transport-neutral boundary between the transport-agnostic Kartendienst logic and the physical card. Implemented once per transport:

| Implementation | Module | Backing transport |
|---|---|---|
| `PcscCardReaderPort` (used by `PcscCryptoProvider`) | `crypto-pcsc-lib` | directly connected PC/SC reader (`javax.smartcardio`) |
| `SicctCardReaderPort` (used by `SicctCryptoProvider`) | `crypto-sicct-lib` | SICCT terminal (`sicct-lib`) |

Responsibilities of the port (per transport): transmit a constructed APDU to a card and return the response; observe/poll card insertion & removal and notify the Kartendienst so CM_CARD_LIST is updated; expose reader/slot identity (`ctid`, `slotNo`) and capability flags (display, mechanical eject, slot selection) so capability-gated steps degrade gracefully (FR-063).

> Dependency note: `crypto-sicct-lib` must add a dependency on `apdu-lib` to implement this port and share CM_CARD_LIST (`crypto-pcsc-lib` already depends on `apdu-lib`). `apdu-lib` continues to **not** depend on `sicct-lib`.

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
