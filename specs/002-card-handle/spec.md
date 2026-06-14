# Feature Specification: Card Handle

**Feature Branch**: `006-card-handle`

**Created**: 2026-06-07

**Status**: Draft

## Overview

This feature implements the Konnektor **Kartendienst** (card service) card-handle lifecycle defined in gemSpec_Kon V5.27.0 §4.1.5. The central runtime structure is **CM_CARD_LIST** — the card-management list of CardObjects that the Kartendienst maintains: TUC_KON_001 ("Karte öffnen") adds a CardObject on insertion, "Reaktion auf Karte entfernt" removes it, and all card-addressing TUCs resolve a card via `CM_CARD_LIST(cardHandle)` or `CM_CARD_LIST(CtID, SlotNo)`.

**Transport-agnostic by design**: every card-handle use case works identically whether a card is read through a **directly PC/SC-connected card reader** or through a **SICCT-connected card terminal**. The two transports are equal, first-class card-access paths — neither is deferred. A client system cannot tell from the Card Handle which transport produced it; only the terminal identifier (`ctid`) distinguishes the source.

**Module placement**: the card-management domain objects (CM_CARD_LIST, CardObject/CardHandle, CardVersion, CardType, CardSession and its subtypes, AuthState) and the transport-neutral lifecycle logic live in the **`apdu-lib`** module so they carry no transport dependency (`apdu-lib` MUST NOT depend on `sicct-lib`). **CM_CARD_LIST is per-provider, not shared**: each CryptoProvider owns its own CM_CARD_LIST instance — the **PCSC Crypto Provider** (`PcscCryptoProvider`, backed by directly connected PC/SC readers) maintains one, and the **Sicct Crypto Provider** (`SicctCryptoProvider`, backed by SICCT terminals) maintains a separate one. The CM_CARD_LIST *type* is defined once in `apdu-lib` and reused by both providers, but the two instances are independent. A unified, transport-spanning card view (e.g. GetCards) is produced by the card service **aggregating across every provider's CM_CARD_LIST**, not by reading a single shared list.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Card Insertion Creates a Usable Card Handle on Any Connected Reader (Priority: P1)

When a smart card (SMC-B, HBA, or eGK) is inserted — into a slot of a SICCT card terminal **or** into a directly connected PC/SC reader — the system immediately creates a Card Handle for that card and adds the corresponding CardObject to CM_CARD_LIST. A client system (Primärsystem) can reference the card by its handle to initiate card-based operations — certificate reading, signing, PIN verification — without knowing the physical transport, terminal, or slot details. The handle captures all card metadata at insertion time, including card type, version info, cardholder identity, and certificate validity status, identically for both transports.

**Why this priority**: card access is the foundational capability on which all cryptographic workflows depend. Both SICCT (multi-tenant networked terminals) and directly PC/SC-connected readers (local/co-located hardware) are primary access paths in this product, so the handle and its CM_CARD_LIST entry MUST be created uniformly regardless of transport.

**Independent Test**: Insert a card into a SICCT terminal slot, then repeat with the same card type in a directly PC/SC-connected reader. Verify that in both cases the system assigns a unique handle, that the handle includes the card's type, ICCSN, cardholder name, and insert timestamp, that the resulting CardObject appears in its provider's CM_CARD_LIST and in the aggregated GetCards view, and that the handle can be passed to GetCards and used in a subsequent signing or PIN verification request — with identical field structure for both transports.

**Acceptance Scenarios**:

1. **Given** a SICCT terminal is connected and a card is inserted into slot N, **When** the insertion event is received, **Then** the system assigns a unique, opaque Card Handle string and makes it available via GetCards within 2 seconds.
2. **Given** a card is inserted, **When** the Card Handle is created, **Then** it contains: ICCSN (if readable), card type, cardholder name, insert timestamp, and all CARDVERSION sub-fields populated from the card.
3. **Given** a card is inserted, **When** the Card Handle is created, **Then** `certStatus` defaults to `NotAvailable` and `certOcspResponse` defaults to `NotAvailable`; asynchronous certificate validation updates these fields after the handle is already available.
4. **Given** a Card Handle exists, **When** the card is ejected from the terminal, **Then** the handle is invalidated and removed from the active handle list; subsequent operations referencing it return an appropriate error.
5. **Given** a card is inserted and OCSP validation completes, **When** the certificate is checked against TUC_KON_037, **Then** `certStatus` and `certOcspResponse` are updated on the existing handle without replacing it.
6. **Given** a slot is empty and a client system calls RequestCard with a `timeOut` of 30 seconds, **When** the user inserts the correct card type within that period, **Then** the system returns a Card Handle for the inserted card, `AlreadyInserted = false`, and no error.
7. **Given** a slot already contains a card when RequestCard is called, **When** the operation executes, **Then** no display prompt is sent to the terminal, `AlreadyInserted = true` is returned, and the existing Card Handle is returned immediately.
8. **Given** a client system calls RequestCard specifying `CardType = SMC-B` but the user inserts an eGK, **When** the card type is verified, **Then** the operation returns error 4051 (wrong card type) and no Card Handle is created for that call.
9. **Given** a card is in slot N and no other session holds it exclusively, **When** a client system calls EjectCard (by card handle or by slot), **Then** the card is physically ejected, the Card Handle is invalidated, and a `CARD/REMOVED` event is emitted.
10. **Given** a card is in slot N and is exclusively locked by an active session, **When** a different client system calls EjectCard for that card, **Then** the operation returns error 4093 and the card is not ejected.

---

### User Story 2 — Directly PC/SC-Connected Reader Creates a Card Handle (Priority: P1)

When a smart card is inserted into a directly connected PC/SC reader, the system applies the same Card Handle creation logic as for SICCT and registers the CardObject into the PCSC Crypto Provider's own CM_CARD_LIST (which the aggregated GetCards view includes). The handle is indistinguishable from a SICCT-originated handle to client systems — the terminal ID (`ctid`) identifies the source reader, but the handle format and fields are identical.

**Why this priority**: directly PC/SC-connected readers are a first-class card-access path in this product (not a deferred development convenience). Every use case in this feature — handle creation, GetCards, RequestCard, EjectCard, sessions, signing, PIN verification — MUST be available over PC/SC with the same behaviour as over SICCT, so client system code needs no transport awareness.

**Independent Test**: Insert a card into a directly PC/SC-connected reader. Confirm a Card Handle is created with the same field structure as a SICCT-originated handle, that the CardObject is added to the PCSC provider's own CM_CARD_LIST, and that the aggregated GetCards returns it under the same response schema. Exercise RequestCard and EjectCard against the PC/SC reader and confirm equivalent behaviour to the SICCT path.

**Acceptance Scenarios**:

1. **Given** a directly PC/SC-connected reader is registered and a card is inserted, **When** the insertion is detected, **Then** a Card Handle is created with a `ctid` referencing the PC/SC reader and a `slotNo` identifying the reader position, and the CardObject is added to CM_CARD_LIST.
2. **Given** a PC/SC-originated Card Handle, **When** a client system queries GetCards, **Then** the handle appears in the response with the same field structure as a SICCT handle.
3. **Given** a directly PC/SC-connected reader, **When** RequestCard or EjectCard is called for that reader, **Then** the operation behaves equivalently to the SICCT path (display prompt where the reader supports it, `AlreadyInserted` semantics, lock checks, mechanical/logical eject), differing only where the PC/SC reader lacks a capability (e.g. no terminal display or no slot selection).

---

### User Story 3 — Unified Card View Aggregated Across Per-Provider Lists (Priority: P1)

The PCSC Crypto Provider and the Sicct Crypto Provider each maintain their own CM_CARD_LIST. Cards inserted via PC/SC readers live in the PCSC provider's list; cards inserted via SICCT terminals live in the SICCT provider's list. The card service produces a unified, transport-spanning view (GetCards) by aggregating across every provider's CM_CARD_LIST, and any `cardHandle` resolves to exactly one provider — the one whose list contains it.

**Why this priority**: keeping each provider's card state self-contained avoids cross-provider coupling and lets each transport own its own lifecycle, while aggregation still gives higher layers a single logical view and the ability to address any card by handle without knowing its transport.

**Independent Test**: With one card in a SICCT terminal and one card in a directly PC/SC-connected reader simultaneously, call GetCards once and confirm both CardObjects are returned — one drawn from each provider's CM_CARD_LIST. Resolve each `cardHandle` and confirm it maps to exactly one provider, which handles subsequent APDU operations.

**Acceptance Scenarios**:

1. **Given** one card present in a SICCT terminal and one card present in a directly PC/SC-connected reader, **When** GetCards is queried, **Then** both CardObjects are returned — aggregated from the two providers' separate CM_CARD_LIST instances — with identical field structure and distinct `ctid` values.
2. **Given** a `cardHandle`, **When** it is resolved, **Then** it is found in exactly one provider's CM_CARD_LIST and the APDU-level operation is routed to that provider, transparently to the caller.
3. **Given** a card is removed from either transport, **When** the removal is detected, **Then** the corresponding CardObject is removed from that provider's own CM_CARD_LIST and no longer appears in the aggregated GetCards view.

---

### Edge Cases

- What happens when ICCSN cannot be read from the card (e.g., card is damaged or uses a non-standard format)?
- How does the system handle simultaneous insertion events for the same physical card?
- What happens when the OCSP service is unreachable at insertion time?
- How does the system behave if CARDVERSION fields cannot be fully read from EF.Version?
- What happens when the same card is re-inserted after ejection — is the previous handle reused or a new one issued?
- What happens when EjectCard is called by `cardHandle` for a card whose terminal is currently disconnected?
- What happens when EjectCard's mechanical throwout succeeds but the user does not remove the card within the `timeOut` period (error 4203 path)?
- If a card is inserted into a slot while startup reconstruction is scanning that terminal — is it captured by the scan or by the insertion event?
- What happens when two concurrent `RequestCard` calls target the same slot simultaneously — which one receives the card?
- What happens when the terminal display is in use (4039) and the caller retries immediately?
- What happens when `RequestCard` is called for a slot on a terminal that is reachable but whose slot ID does not exist?
- What happens when `CARD_SESSION_TIMEOUT` fires while a card APDU operation is still in progress?
- What happens when a client attempts to start a second eGK session while one is already active (error 4093 path)?
- What happens when the comfort signature count reaches the maximum (250 or `SAK_COMFORT_SIGNATURE_MAX`) in the middle of a signing batch?
- What happens to an active eGK session's `sessionID` lock if the card is physically ejected before TUC_KON_224 is called?
- What happens when a comfort signature session timer expires while a signature operation is already in progress?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: When a card is inserted, the system MUST create a Card Handle within 2 seconds of the insertion being detected and add the CardObject to CM_CARD_LIST. This ≤2 s target applies to BOTH transports: a SICCT insertion event and a PC/SC insertion detection. For PC/SC, the reader polling interval MUST be chosen so detection-to-handle latency stays within the 2 s budget.
- **FR-002**: When a card is inserted in a directly PC/SC-connected reader, the system MUST create a Card Handle and CM_CARD_LIST entry using the same structure, fields, and lifecycle as a SICCT-originated handle; PC/SC is a first-class transport, not a deferred path.

#### CM_CARD_LIST & Transport-Agnostic Card Access

- **FR-060**: The system MUST maintain a single card-management list, **CM_CARD_LIST**, holding one CardObject per card currently known to the Kartendienst, per gemSpec_Kon §4.1.5. CM_CARD_LIST MUST be addressable both by `cardHandle` (`CM_CARD_LIST(cardHandle)`) and by the (`ctid`, `slotNo`) pair (`CM_CARD_LIST(CtID, SlotNo)`).
- **FR-061**: CM_CARD_LIST and the card-management domain objects (CardObject/CardHandle, CardVersion, CardType, CardSession and subtypes, AuthState) MUST reside in the `apdu-lib` module so they carry no transport dependency. CM_CARD_LIST MUST NOT depend on `sicct-lib` or on any PC/SC-specific type.
- **FR-062**: Each CryptoProvider MUST own its own CM_CARD_LIST instance; CM_CARD_LIST MUST NOT be shared between providers. The PCSC Crypto Provider (`PcscCryptoProvider`) maintains its own list of PC/SC-originated CardObjects and the Sicct Crypto Provider (`SicctCryptoProvider`) maintains a separate list of SICCT-originated CardObjects. Each provider registers and removes CardObjects only in its own instance. The CM_CARD_LIST type is defined once in `apdu-lib` and instantiated per provider.
- **FR-063**: Every card-handle use case in this specification (TUC_KON_001 handle creation, GetCards, RequestCard/TUC_KON_056, EjectCard/TUC_KON_057, card-session create/start/stop, PIN verification, signing, decryption, certificate read) MUST function identically whether the addressed card is reached through a directly PC/SC-connected reader or through a SICCT terminal, except where a specific reader physically lacks a capability (e.g. no display, no mechanical eject, no slot selection), in which case the system MUST degrade gracefully rather than fail.
- **FR-064**: GetCards MUST return a unified view spanning both transports by aggregating across every provider's CM_CARD_LIST; a card reached via PC/SC and a card reached via SICCT MUST be indistinguishable in structure, differing only in `ctid` (and capability-dependent fields). The aggregation MUST de-duplicate by `cardHandle` even though, in normal operation, a given handle appears in only one provider's list.
- **FR-065**: Resolving a `cardHandle` MUST locate the single provider whose CM_CARD_LIST contains it and route subsequent APDU execution to that provider, transparently to the caller. The provider boundary is the only place transport-specific APDU transmission occurs; `apdu-lib` defines the CM_CARD_LIST type and constructs APDUs but MUST NOT transmit APDUs itself.
- **FR-066**: The transport-execution boundary MUST be expressed as a transport-neutral port (abstraction) defined in `apdu-lib`; `PcscCryptoProvider` MUST implement it over a directly connected PC/SC reader and `SicctCryptoProvider` MUST implement it over a SICCT terminal. No single-transport assumption may leak into CM_CARD_LIST or the domain objects.
- **FR-067**: `cardHandle` uniqueness, the 48-hour no-reuse rule, and per-tenant scoping (FR-003, FR-019) MUST hold across the **union** of all providers' CM_CARD_LIST instances — i.e. a `cardHandle` MUST be unique system-wide, never appearing in more than one provider's list at the same time, regardless of which transport created each entry.
- **FR-068**: For a directly PC/SC-connected reader, the system MUST derive a stable `ctid` as a UUID from the PC/SC reader name and register the reader as a `CardTerminal` entry, so `ctid` has the same UUID/FK shape as a SICCT terminal. The same physical reader MUST map to the same `ctid` across reconnects.
- **FR-069**: Startup reconstruction (FR-042), disconnect invalidation (FR-044), and reconnect rebuild (FR-045) MUST apply to PC/SC readers with full parity: cards present in a connected reader at startup are scanned and given handles; unplugging a reader invalidates all its handles immediately; re-plugging applies the same reconstruction logic with fresh `cardHandle` identifiers. No PC/SC session state survives an unplug.
- **FR-070**: When EjectCard (FR-051) addresses a card in a reader that lacks mechanical throwout (typical PC/SC reader), the system MUST perform a logical eject — invalidate the Card Handle and remove the CardObject from CM_CARD_LIST — and return success WITHOUT raising error 4203. Error 4203 applies only to readers/terminals that physically eject but whose card is not removed by the user in time.
- **FR-071**: Per FR-034 and FR-052, when the addressed reader provides no display, RequestCard/EjectCard MUST skip the display prompt and proceed (graceful degradation), rather than failing with a display-related error.
- **FR-003**: The Card Handle MUST carry a globally unique, opaque identifier (`cardHandle`) assigned by the system at creation time; it MUST NOT be derivable from card attributes. An invalidated `cardHandle` MUST NOT be reused for any new card within 48 hours of its invalidation.
- **FR-004**: The Card Handle MUST record the terminal ID (`ctid`) referencing the `CardTerminal` entity and the slot number (`slotNo`) where the card is physically located.
- **FR-005**: The Card Handle MUST record the card's ICCSN if the value is readable from the card; if unreadable, `iccsn` MUST be left empty rather than failing handle creation.
- **FR-006**: The Card Handle MUST record the card type from the standard card-type enumeration (SMC-B-ORG, SMC-B-KTR, HBA, eGK, gSMC-KT, and others as defined in TAB_KON_500).
- **FR-007**: The Card Handle MUST record all CARDVERSION sub-fields: `cosVersion`, `objectSystemVersion`, `cardPersonalizationVersion`, `dataStructureVersion` (from EF.Version), `loggingVersion`, `atrVersion`, `gdoVersion`, and `keyInfoVersion`. Sub-fields that cannot be read MUST be stored as empty rather than causing failure.
- **FR-008**: The Card Handle MUST record the insertion timestamp (`insertTime`) with at least second-level precision.
- **FR-009**: The Card Handle MUST record the cardholder name (`cardHolderName`) extracted from the AUT certificate's `subject.commonName`. For institutional cards (SMC-B), this is the organization name.
- **FR-010**: The Card Handle MUST record the health insurance ID (`kvnr`) for eGK cards; for all other card types this field MUST be empty.
- **FR-011**: The Card Handle MUST record the AUT certificate expiry date (`certExpirationDate`).
- **FR-012**: The `certStatus` field MUST default to `NotAvailable` at handle creation time. After asynchronous TUC_KON_037 validation completes, it MUST be updated to one of: `Valid`, `Invalid`, or `Inconclusive`. The field applies to `C.HCI.OSIG` for SMC-B cards and `C.HP.QES` for HBA cards.
- **FR-013**: The `certOcspResponse` field MUST default to `NotAvailable` at handle creation time. After OCSP validation (TUC_KON_037) completes, it MUST be updated to one of: `Good`, `Revoked`, or `Unknown`. If the OCSP service is unreachable, the field MUST remain `NotAvailable`.
- **FR-014**: The Card Handle MUST maintain a list of Card Sessions (`cardSessionList`). A session is created when a client system initiates card access in a specific tenant/client/user context. The session is identified by the triple (`mandantId`, `csid`, `userId`).
- **FR-015**: Each Card Session MUST track its authentication state (`authState`) as a list of achieved security states. Each state entry is either a C2C authentication (referencing a key reference and role) or a Card Holder Verification (referencing a PIN).
- **FR-016**: For eGK cards, the `authBy` field of a Card Session MUST reference the session via which the card was unlocked; for all other card types this field MUST be empty.
- **FR-017**: For HBA card sessions, `signMode` MUST default to `PIN` and MUST be switchable to `Comfort` per session without affecting other sessions on the same card.
- **FR-018**: When a card is ejected from its slot, all Card Handles and their associated Card Sessions for that slot MUST be invalidated immediately. References to invalidated handles MUST return an appropriate error.
- **FR-019**: Card Handles MUST be scoped per tenant: a handle created in response to an event for tenant A MUST NOT be visible to tenant B via GetCards or any other query.
- **FR-020**: The `authState` of an eGK G2.0 card session MUST NOT include the MRPIN state.

#### Active Card Request (RequestCard / TUC_KON_056)

- **FR-033**: The system MUST provide a `RequestCard` operation (backed by TUC_KON_056) that actively prompts a card terminal to accept a card in a specific slot and waits for insertion. The caller supplies: `ctId` (terminal), `slotId` (slot), optional `cardType` filter, optional `displayMessage`, and `timeOut` in seconds.
- **FR-034**: When `RequestCard` is called without an explicit `displayMessage`, the system MUST display a default prompt on the card terminal according to the requested card type:
  - eGK → "Bitte eGK in SLOT X stecken"
  - HBA / HBAx / HBA-qSig → "Bitte HBA in SLOT X stecken"
  - SMC-B → "Bitte SMC-B in SLOT X stecken"
  - No type or unknown → "Bitte Karte in SLOT X stecken"
  The slot reference SHOULD be omitted from the message if the terminal provides no slot-selection capability.
- **FR-035**: If the addressed slot already contains a card when `RequestCard` is called, the system MUST skip sending the display prompt to the terminal and return the existing card immediately.
- **FR-036**: When `RequestCard` completes successfully, the system MUST include a boolean `AlreadyInserted` flag in the response: `true` if a card was already present in the slot before the call, `false` if the card was inserted in response to the request.
- **FR-037**: When `RequestCard` completes successfully, the system MUST return the `CARD:Card` object (Card Handle information) for the card now present in the slot. This is the same data returned by `GetCards` for that slot.
- **FR-038**: If a `cardType` filter is specified and the inserted card does not match the requested type, the system MUST reject the card and return error 4051. The Card Handle MUST NOT be returned in this case.
- **FR-039**: If no card is inserted within the `timeOut` period, the system MUST return error 4202. When `timeOut` is not supplied by the caller, the system MUST default to 20 seconds.
- **FR-040**: `RequestCard` MUST fail with the following errors for the corresponding terminal conditions: 4039 (terminal display currently in use by another operation), 4044 (terminal access error), 4007 (invalid terminal ID), 4097 (invalid slot ID), 4221 (terminal not active), 4222 (terminal not connected).
- **FR-041**: `GetCards` MUST return only the internally cached, currently known card state and MUST NOT send any commands to card terminals. Fetching fresh card information from a terminal requires an explicit call to `RequestCard`.

#### Card Ejection (EjectCard / TUC_KON_057)

- **FR-051**: The system MUST provide an `EjectCard` operation (backed by TUC_KON_057) that sends a SICCT EJECT ICC command with the "Delivery: Mechanical Throwout" option to physically eject a card. The caller MUST supply either a `cardHandle` OR a `ctId + slotId` pair as the card address, along with the mandatory access context (mandantId, csId, workplaceId).
- **FR-052**: When `EjectCard` is called without an explicit `displayMessage`, the system MUST display a default prompt on the card terminal (per TIP1-A_5408, ejection context). The slot reference SHOULD be omitted if the terminal provides no slot-selection capability.
- **FR-053**: `EjectCard` MUST verify that the addressed card is not exclusively reserved by another session before ejecting. If the card is reserved by a different holder, the operation MUST fail with error 4093 without sending the SICCT command.
- **FR-054**: `EjectCard` MUST send the SICCT EJECT ICC command even if the Konnektor's internal state shows no card in the addressed slot. If the terminal reports no error in this case, the operation MUST also report no error and MAY assume the slot is now empty.
- **FR-055**: `EjectCard` MUST fail with the following errors for the corresponding conditions: 4203 (card deactivated but not physically removed by user), 4101 (supplied `cardHandle` is invalid or already invalidated), 4039 (terminal display currently in use), 4044 (terminal access error), 4007 (invalid terminal ID), 4097 (invalid slot ID), 4221 (terminal not active), 4222 (terminal not connected).
- **FR-056**: The `EjectCard` `timeOut` parameter specifies the maximum wait time in milliseconds for the user to remove the card after mechanical ejection. When omitted, the system MUST default to 20 seconds (20,000 ms). This MAY be configurable in the Konnektor.

#### Startup State Reconstruction

- **FR-042**: After establishing TLS connections to all card terminals on startup, the system MUST query each connected terminal for cards currently present in all slots and reconstruct Card Handles for any cards found — as if each card had just been inserted.
- **FR-043**: Card Handles reconstructed on startup MUST be assigned new `cardHandle` identifiers. All CardSession state from before the restart MUST NOT be restored; clients must re-establish sessions after reconnection. This means `cardSessionList` starts empty for every reconstructed handle.
- **FR-044**: When the SICCT connection to a terminal drops, the system MUST immediately invalidate all Card Handles associated with that terminal (including any active CardSessions). References to these handles MUST return an appropriate error.
- **FR-045**: When a SICCT terminal reconnects, the system MUST apply the same startup reconstruction logic (FR-042) — querying all slots on that terminal and creating fresh Card Handles with new `cardHandle` identifiers for any cards found. No session state from before the disconnect is restored.

#### Event Notifications (TUC_KON_256 / Event-Mechanismus)

- **FR-046**: After successfully creating a Card Handle (TUC_KON_001), the system MUST emit a `CARD/INSERTED` event (via TUC_KON_256, severity: Info). The event payload MUST include: `CardHandle`, `CardType`, `CardVersion` (at minimum `COSVERSION` and `OBJECTSYSTEMVERSION`; also `DATASTRUCTUREVERSION` for eGK G1+), `ICCSN`, `CtID`, `SlotID`, `InsertTime`, `CardHolderName`, `KVNR`, `CertExpirationDate`.
- **FR-047**: When a Card Handle is invalidated — whether by card ejection (`CT/SLOT_FREE`) or by terminal disconnect — the system MUST emit a `CARD/REMOVED` event (via TUC_KON_256, severity: Info). The event payload MUST include: `CardHandle`, `Type`, `CardVersion`, `ICCSN`, `CtID`, `SlotID`, `InsertTime`, `CardHolderName`, `KVNR`.
- **FR-048**: If certificate validation (TUC_KON_037) for an SMC-B or HBAx card at insertion time yields a non-"good" result or the certificate is invalid, the system MUST emit a `CERT/CARD/STATUS` event (via TUC_KON_256, severity: Warning) with parameters: `CARD_TYPE`, `ICCSN`, `CARD_HANDLE`, `CardHolderName`, `CertName`, `ExpirationDate`, and `CARD_CERTSTATUS` (values per TAB_KON_285: `Invalid`, `Inconclusive`, `Unknown`, `Revoked`). This event MUST NOT block card availability (certificate checks run asynchronously per A_23702).
- **FR-049**: When the `CARD_SESSION_TIMEOUT` timer fires for an eGK card session, the system MUST stop the session (TUC_KON_224) and then emit a `CARD/SESSION/TIMEOUT` event (via TUC_KON_256, severity: Info) with parameters: `CardType=EGK`, `SessionID`, `Timer`.
- **FR-050**: When a G2.0 SMC-B or G2.0 HBA card is inserted, the system MUST compute a pseudonym as the first 10 characters of SHA-256(ICCSN ‖ expirationDate) and maintain an admin-visible log entry containing: insertion timestamp, card type, cardholder name, ICCSN, expiration date, and pseudonym. This entry persists as long as the card is known to the system (A_25801).

#### Card Session Subtypes

- **FR-021**: The system MUST support three distinct CardSession subtypes, each with its own identification key:
  - `CardSession_eGK`: identified by `cardHandle` alone — at most one session per physical eGK card at any time.
  - `CardSession_SM-B`: identified by `cardHandle` + `mandantId` — one session per card per tenant.
  - `CardSession_HBAx`: identified by `cardHandle` + `clientSystemId` + `userId` — one session per card per client system per user.
- **FR-022**: An eGK card MUST have at most one active CardSession at any time (gemSpec_Kon Constraint C1). Any attempt to create a second concurrent session for the same eGK MUST be rejected with error 4093 (card already reserved exclusively in another session).
- **FR-023**: For HBAx cards, if a CardSession already exists for the same `cardHandle`, `clientSystemId`, and `userId`, the system MUST return the existing session rather than creating a new one (Constraint C2).
- **FR-024**: For SM-B cards, if a CardSession already exists for the same `cardHandle` and `mandantId`, the system MUST return the existing session rather than creating a new one (Constraint C3).
- **FR-025**: An HBAx CardSession MUST always have a `userId` populated (Constraint C16). Attempting to create an HBAx session without a `userId` MUST be rejected.

#### eGK Session Lifecycle (Start / Stop)

- **FR-026**: For eGK card sessions, the system MUST support an explicit start/stop lifecycle (corresponding to TUC_KON_223 / TUC_KON_224):
  - **Start**: locks the card for exclusive use and performs a card reset before the APDU scenario begins.
  - **Stop**: performs a card reset and releases the exclusive lock so the card becomes available again.
- **FR-027**: When an eGK card session is started, the system MUST assign a unique `sessionID` formatted as a UUID per RFC 4122 and MUST start a `CARD_SESSION_TIMEOUT` timer. When the timer expires, the session MUST be stopped automatically, releasing the lock.
- **FR-028**: When an eGK card session is stopped, the system MUST accept the `sessionID` as the identifying input. If the `sessionID` is not recognized, the system MUST return error 4288. A successful stop removes the `sessionID` from the active session context.

#### Comfort Signature (HBAx only)

- **FR-029**: The system MUST support activating comfort signature mode per HBAx CardSession. Activation requires successful PIN.QES verification and MUST fail if:
  - The system-level comfort signature feature is disabled (`SAK_COMFORT_SIGNATURE = Disabled`); or
  - The maximum number of concurrent comfort signature sessions has already been reached (error 4278).
- **FR-030**: The system MUST support deactivating comfort signature mode per HBAx CardSession. Deactivation MUST:
  - Remove the PIN.QES entry from the session's `authState`.
  - Set `signMode` back to `PIN`.
  - Stop the comfort signature timer for that session.
  - Also be triggered automatically if an administrator disables the system-level comfort signature feature.
- **FR-031**: For each HBAx CardSession in comfort signature mode, the system MUST track `countRemaining` (signatures remaining within the configured maximum) and `timeRemaining` (seconds remaining on the comfort signature timer). These values MUST be queryable per session.
- **FR-032**: The maximum allowed comfort signature count per session MUST be bounded by two limits, whichever is lower: the configurable system parameter `SAK_COMFORT_SIGNATURE_MAX` and the absolute hardware maximum of 250 comfort signatures per HBA logical channel (per gemSpec_HBA_ObjSys).

### Key Entities

- **CM_CARD_LIST**: The card-management list of the Kartendienst (gemSpec_Kon §4.1.5) — a transport-agnostic registry of CardObjects, indexed by `cardHandle` and by (`ctid`, `slotNo`), maintained by TUC_KON_001 on insertion (add CardObject) and by the card-removal reaction (remove CardObject). The type is defined once in `apdu-lib`, but **each CryptoProvider owns its own instance** (`PcscCryptoProvider` and `SicctCryptoProvider` each have a separate CM_CARD_LIST); instances are never shared between providers. Each instance enforces the 48-hour no-reuse blacklist and per-tenant scoping for its own entries, while system-wide `cardHandle` uniqueness holds across the union of all instances. The unified card view is the aggregation of all providers' lists. Independent of the underlying transport (PC/SC reader or SICCT terminal).
- **CardObject / CardHandle**: The runtime representation of a single inserted card and the entry type held in CM_CARD_LIST (gemSpec_Kon calls it the *CardObject*; clients reference it by its `cardHandle`). Carries the card's unique handle, location (terminal/reader + slot), identification (ICCSN, type, cardholder name, KVNR), version metadata (CARDVERSION), certificate status, the originating transport's terminal id (`ctid`), and a list of active card sessions. Scoped per tenant. Identical in structure whether created by the PC/SC or the SICCT provider.
- **CardReaderPort (transport port)**: The transport-neutral abstraction, defined in `apdu-lib`, through which constructed APDUs are transmitted to a physical card and through which insertion/removal is observed. `PcscCryptoProvider` implements it over a directly connected PC/SC reader; `SicctCryptoProvider` implements it over a SICCT terminal. It is the only place transport-specific transmission occurs; CM_CARD_LIST and the domain objects never reference a concrete transport.
- **CardVersion**: Nested within CardHandle. Contains eight version fields read from the card's EF.Version and related structures (COS version, object system version, personalization version, data structure version, logging version, ATR version, GDO version, key info version).
- **CardSession**: Represents a single active context on a card. Three concrete subtypes exist — `CardSession_eGK` (key: `cardHandle`), `CardSession_SM-B` (key: `cardHandle + mandantId`), `CardSession_HBAx` (key: `cardHandle + clientSystemId + userId`). Every session tracks achieved authentication states (`authState`), the unlocking session (`authBy`, eGK only), and comfort signature mode (`signMode`, HBA only). eGK sessions additionally carry a `sessionID` (UUID, RFC 4122) assigned at session start, and a reference to the active `CARD_SESSION_TIMEOUT` timer.
- **AuthState**: A single achieved security state within a CardSession. Either a C2C entry (key reference `KeyRef` + `Role` per gemSpec_PKI_TI#Tab_PKI_918) or a CHV entry (PIN reference `PINRef`). Multiple entries accumulate as the session progresses through authentication steps.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Card Handle is available for query via GetCards within 2 seconds of a SICCT card insertion event.
- **SC-002**: 100% of card insertion events result in a Card Handle with a unique, non-colliding `cardHandle` identifier; zero duplicate handles across concurrent insertions.
- **SC-003**: When OCSP is reachable, `certStatus` and `certOcspResponse` are updated on the existing handle within 10 seconds of handle creation without requiring client re-query of the handle identifier.
- **SC-004**: A tenant's card handles are never visible to another tenant in any query response; zero cross-tenant handle leakage in test scenarios with 10 concurrent tenants.
- **SC-005**: All eight CARDVERSION sub-fields are populated for any card that exposes EF.Version; partial population (some fields missing) does not prevent handle creation.
- **SC-006**: Card ejection results in handle invalidation within 1 second; subsequent operations on the ejected handle return an error without causing system instability.
- **SC-007**: PC/SC-originated handles pass the same validation checks as SICCT-originated handles; no field is absent or differently typed.
- **SC-008**: Starting an eGK card session returns a UUID-formatted `sessionID` within 1 second; while the session is active, a second start attempt for the same card returns error 4093.
- **SC-009**: Stopping an eGK session with the correct `sessionID` succeeds and the card lock is released; stopping with an unknown `sessionID` returns error 4288 without affecting the active session.
- **SC-010**: For an HBAx session in comfort signature mode, `countRemaining` decrements by exactly 1 per signature operation and `timeRemaining` decrements monotonically; when either reaches zero the session is automatically deactivated.
- **SC-011**: When `RequestCard` is called for an empty slot and a matching card is inserted within the timeout, the operation returns a Card Handle and `AlreadyInserted = false` within that timeout period. When called for a slot that already holds a card, it returns `AlreadyInserted = true` without sending any display message to the terminal.
- **SC-012**: `GetCards` never sends commands to card terminals; its response latency reflects only an in-memory lookup. `RequestCard` is the only path that actively contacts a terminal to solicit card insertion.
- **SC-013**: After Konnektor startup, all cards present in connected terminals are discoverable via `GetCards` within 10 seconds of the last terminal's TLS connection being established; no manual card re-insertion is required.
- **SC-014**: When a SICCT terminal disconnects, all its Card Handles are invalidated within 1 second; after reconnection, handles for all physically present cards are reconstructed within 10 seconds without any client action.
- **SC-015**: Every Card Handle creation emits a `CARD/INSERTED` event and every invalidation emits a `CARD/REMOVED` event; a subscriber observing both event topics sees a perfectly balanced insert/remove sequence with no missing or duplicate events in a test scenario of 50 sequential card insertions and ejections.
- **SC-016**: A card inserted via a directly PC/SC-connected reader and a card inserted via a SICCT terminal both produce Card Handles whose field set and types are identical (verified field-by-field); no field is present or differently typed in one transport versus the other.
- **SC-017**: With one card in a SICCT terminal and one in a directly PC/SC-connected reader simultaneously, a single GetCards call returns both CardObjects aggregated from the two providers' separate CM_CARD_LIST instances; each `cardHandle` appears in exactly one provider's list, with zero duplicate or divergent entries in a test of 20 concurrent mixed-transport insertions.
- **SC-018**: 100% of card-handle use cases (handle creation, GetCards, RequestCard, EjectCard, session create/start/stop, PIN verify, sign, decrypt, read certificate) pass their acceptance tests when executed over the PC/SC transport and again when executed over the SICCT transport, except capability-gated steps that degrade gracefully (and are asserted to degrade, not fail).
- **SC-019**: CM_CARD_LIST and all card-management domain objects compile and unit-test within the `apdu-lib` module with no compile-time dependency on `sicct-lib` or any PC/SC-specific type (verified by the module's dependency set).

## Assumptions

- Card type enumeration follows the values defined in gemSpec_Kon TAB_KON_500; any card type not in the enumeration is stored as `Unknown`.
- The `cardHandle` unique identifier is a system-generated UUID or equivalent opaque token; clients treat it as an opaque string.
- Certificate validation (TUC_KON_037) runs asynchronously after handle creation to avoid blocking card availability; clients may briefly observe `certStatus = NotAvailable`.
- The `kvnr` field is only populated for eGK cards; all other card types leave it empty.
- MRPIN state is excluded from `authState` for eGK G2.0 cards per gematik specification; no other card types have this exclusion.
- Comfort signature mode (`signMode = Comfort`) is only meaningful for HBA cards; for all other card types the field exists but defaults to `PIN` and is not toggled.
- PC/SC reader support is a first-class, equal-priority transport sharing the same Card Handle structure and CM_CARD_LIST as SICCT; it is NOT deferred. PC/SC-specific capability gaps (no terminal display, no slot selection, no mechanical eject) are handled by graceful degradation, not by excluding the use case.
- CM_CARD_LIST and the card-management domain objects live in `apdu-lib` (transport-neutral); `apdu-lib` does not depend on `sicct-lib` and does not transmit APDUs. APDU transmission and insertion/removal detection happen in the provider that implements the `apdu-lib` transport port (`PcscCryptoProvider` over a PC/SC reader, `SicctCryptoProvider` over a SICCT terminal).
- CM_CARD_LIST is per-provider: each CryptoProvider instantiates and owns its own list; the lists are never shared between providers. The unified card view is produced by the card service aggregating across all providers' lists (de-duplicated by `cardHandle`).
- A re-inserted card (same physical card after ejection) receives a new `cardHandle` identifier; the previous handle is not reused.
- Card Handles are runtime in-memory objects and are not persisted. On Konnektor restart, handles are reconstructed by querying all connected terminals (FR-042); CardSession state is never restored after a restart (FR-043).
- The eGK Start/Stop session lifecycle (TUC_KON_223/TUC_KON_224) applies only to eGK cards; SM-B and HBAx sessions do not use an explicit lock/unlock mechanism with a `sessionID`.
- The `CARD_SESSION_TIMEOUT` duration is a system-level configuration parameter; its value is not defined within this feature specification.
- `SAK_COMFORT_SIGNATURE_MAX` and `SAK_COMFORT_SIGNATURE_Timer` are system-level configuration parameters; their default values are not defined within this feature specification. The hardware cap of 250 per HBA logical channel is a fixed limit from the HBA object system specification.
- C2C authentication roles referenced in `authState` entries follow the role definitions in gemSpec_PKI_TI#Tab_PKI_918.
- WorkplaceId is NOT an input to session creation (TUC_KON_026); eGK single-workplace access is enforced by an earlier prerequisite step (TUC_KON_000) and does not need to be repeated at the session level.
- The system imposes no software-defined maximum on concurrent Card Handles or CardSessions. The practical upper bound is determined by the total number of slots across all connected terminals (one handle per occupied slot); no artificial cap is applied.
- The `RequestCard` default timeout of 20 seconds applies when the caller does not supply a `timeOut` parameter; this value MAY be configurable in the Konnektor but MUST default to 20 seconds per the specification.
- The display message content and encoding (SICCT 0x0B separators) follow the values defined in TAB_KON_727; translation or localization of these messages is out of scope for this feature.
- The `CARD:Card` response element from `RequestCard` is structurally identical to a card entry returned by `GetCards`; no additional fields are present in one that are absent from the other.
- Access authorization for `RequestCard` is enforced via TUC_KON_000 before TUC_KON_056 is invoked; authorization failures are surfaced as errors from TUC_KON_000, not TUC_KON_056.

## Clarifications

### Session 2026-06-14

- Q: Should directly PC/SC-connected readers be a first-class transport on equal footing with SICCT, or remain a deferred secondary path? → A: First-class and equal. Every use case (handle creation, GetCards, RequestCard, EjectCard, sessions, sign/decrypt/verify, certificate read) MUST work identically over both transports, degrading gracefully only where a specific reader lacks a capability.
- Q: Where do CM_CARD_LIST and the card-management domain objects live, given `apdu-lib` MUST NOT depend on `sicct-lib`? → A: In `apdu-lib`, transport-neutral. The transport boundary is a port interface defined in `apdu-lib`; PC/SC and SICCT providers implement it. `apdu-lib` constructs APDUs and owns CM_CARD_LIST but does not transmit APDUs.
- Q: Is CM_CARD_LIST per-provider or shared? → A: Per-provider. Each CryptoProvider (`PcscCryptoProvider`, `SicctCryptoProvider`) owns its own CM_CARD_LIST instance; the lists are never shared. The unified GetCards view is produced by the card service aggregating across all providers' lists, with system-wide `cardHandle` uniqueness across their union. (Supersedes an earlier draft that used a single shared instance.)
- Q: How is the terminal id (`ctid`) determined for a directly PC/SC-connected reader? → A: Synthesize a stable UUID from the PC/SC reader name and register it as a `CardTerminal` entry, so `ctid` handling is uniform with SICCT (same UUID/FK shape).
- Q: Do startup reconstruction and disconnect invalidation/rebuild apply to PC/SC readers? → A: Full parity — cards present at startup are scanned, reader unplug invalidates its handles, and replug rebuilds them, exactly as for SICCT terminal connect/disconnect.
- Q: EjectCard on a PC/SC reader with no mechanical throwout? → A: Logical eject — invalidate the handle and return success without error 4203 (graceful capability degradation per FR-063).
- Q: Does the ≤2 s handle-creation target apply to PC/SC? → A: Yes, the same ≤2 s from insertion detection applies to both transports; the PC/SC polling interval must be chosen to fit within that budget.

### Session 2026-06-07

- Q: Are CardHandles ephemeral (lost on restart), rebuilt on startup by querying terminals, or persisted to a database? → A: Rebuilt on startup — after all terminal connections are established, the Konnektor queries every slot on every connected terminal and reconstructs handles for any cards found. Session state is never restored.
- Q: When a SICCT terminal connection drops and reconnects, what happens to handles for cards still physically in the terminal? → A: Invalidate all handles for that terminal immediately on disconnect; rebuild them on reconnect using the same startup reconstruction logic (FR-042). Session state is never preserved across a disconnect.
- Q: What observability/event signals must be emitted for card handle lifecycle? → A: Structured events per gemSpec_Kon event mechanism (TUC_KON_256): `CARD/INSERTED` on handle creation, `CARD/REMOVED` on ejection or terminal disconnect, `CERT/CARD/STATUS` (Warning) on certificate failure, `CARD/SESSION/TIMEOUT` (Info) on eGK session timer expiry. G2.0 SMC-B/HBA insertions additionally require a pseudonym-based admin log entry (A_25801).
- Q: Should the EjectCard operation (TUC_KON_057) be included in this feature specification? → A: Yes — EjectCard is the symmetric counterpart to RequestCard and is included. Covers addressing by cardHandle or by ctId+slotId, lock-check (error 4093), mechanical throwout, always-send-SICCT-even-if-empty behavior, error 4203 (not removed), error 4101 (invalid handle), and default timeout of 20 seconds.
- Q: Is there a maximum number of concurrent Card Handles or CardSessions the system must support? → A: No software cap — the maximum is bounded by hardware only: total occupied slots across all connected terminals. One handle per occupied slot; no additional artificial limit.
