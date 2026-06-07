# Feature Specification: Card Handle

**Feature Branch**: `006-card-handle`

**Created**: 2026-06-07

**Status**: Draft

## User Scenarios & Testing *(mandatory)*

### User Story 1 — SICCT Card Insertion Creates a Usable Card Handle (Priority: P1)

When a smart card (SMC-B, HBA, or eGK) is inserted into a slot of a networked SICCT card terminal, the system immediately creates a Card Handle for that card. A client system (Primärsystem) can reference the card by its handle to initiate card-based operations — certificate reading, signing, PIN verification — without knowing the physical terminal or slot details. The handle captures all card metadata at insertion time, including card type, version info, cardholder identity, and certificate validity status.

**Why this priority**: SICCT is the primary card access path in multi-tenant deployments. Without a reliable card handle created at insertion time, no card operations are possible. This is the foundational capability on which all cryptographic workflows depend.

**Independent Test**: Insert a card into a SICCT terminal slot. Verify that the system assigns a unique handle, that the handle includes the card's type, ICCSN, cardholder name, and insert timestamp, and that the handle can be passed to GetCards and used in a subsequent signing or PIN verification request.

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

### User Story 2 — PC/SC Card Insertion Creates a Card Handle (Priority: P2)

When a smart card is inserted into a locally connected PC/SC reader, the system applies the same Card Handle creation logic. The handle is indistinguishable from a SICCT-originated handle to client systems — the terminal ID (`ctid`) identifies the source, but the handle format and fields are identical.

**Why this priority**: PC/SC provides local card access for development and scenarios where physical hardware is co-located. The handle must be uniform so client system code needs no terminal-type awareness.

**Independent Test**: Insert a card into a PC/SC reader. Confirm a Card Handle is created with the same field structure as a SICCT-originated handle and that GetCards returns it under the same response schema.

**Acceptance Scenarios**:

1. **Given** a PC/SC reader is registered and a card is inserted, **When** the insertion event fires, **Then** a Card Handle is created with a `ctid` referencing the PC/SC reader and a `slotNo` identifying the reader position.
2. **Given** a PC/SC-originated Card Handle, **When** a client system queries GetCards, **Then** the handle appears in the response with the same field structure as a SICCT handle.

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

- **FR-001**: When a card is inserted in a SICCT terminal slot, the system MUST create a Card Handle within 2 seconds of the insertion event being received.
- **FR-002**: When a card is inserted in a PC/SC reader, the system MUST create a Card Handle using the same structure as a SICCT-originated handle.
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

- **CardHandle**: The runtime representation of a single inserted card. Carries the card's unique handle, location (terminal + slot), identification (ICCSN, type, cardholder name, KVNR), version metadata (CARDVERSION), certificate status, and a list of active card sessions. Scoped per tenant.
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

## Assumptions

- Card type enumeration follows the values defined in gemSpec_Kon TAB_KON_500; any card type not in the enumeration is stored as `Unknown`.
- The `cardHandle` unique identifier is a system-generated UUID or equivalent opaque token; clients treat it as an opaque string.
- Certificate validation (TUC_KON_037) runs asynchronously after handle creation to avoid blocking card availability; clients may briefly observe `certStatus = NotAvailable`.
- The `kvnr` field is only populated for eGK cards; all other card types leave it empty.
- MRPIN state is excluded from `authState` for eGK G2.0 cards per gematik specification; no other card types have this exclusion.
- Comfort signature mode (`signMode = Comfort`) is only meaningful for HBA cards; for all other card types the field exists but defaults to `PIN` and is not toggled.
- PC/SC reader support shares the same Card Handle structure but is lower priority than SICCT; PC/SC-specific edge cases (multi-reader, hot-plug) are deferred to a follow-up.
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

### Session 2026-06-07

- Q: Are CardHandles ephemeral (lost on restart), rebuilt on startup by querying terminals, or persisted to a database? → A: Rebuilt on startup — after all terminal connections are established, the Konnektor queries every slot on every connected terminal and reconstructs handles for any cards found. Session state is never restored.
- Q: When a SICCT terminal connection drops and reconnects, what happens to handles for cards still physically in the terminal? → A: Invalidate all handles for that terminal immediately on disconnect; rebuild them on reconnect using the same startup reconstruction logic (FR-042). Session state is never preserved across a disconnect.
- Q: What observability/event signals must be emitted for card handle lifecycle? → A: Structured events per gemSpec_Kon event mechanism (TUC_KON_256): `CARD/INSERTED` on handle creation, `CARD/REMOVED` on ejection or terminal disconnect, `CERT/CARD/STATUS` (Warning) on certificate failure, `CARD/SESSION/TIMEOUT` (Info) on eGK session timer expiry. G2.0 SMC-B/HBA insertions additionally require a pseudonym-based admin log entry (A_25801).
- Q: Should the EjectCard operation (TUC_KON_057) be included in this feature specification? → A: Yes — EjectCard is the symmetric counterpart to RequestCard and is included. Covers addressing by cardHandle or by ctId+slotId, lock-check (error 4093), mechanical throwout, always-send-SICCT-even-if-empty behavior, error 4203 (not removed), error 4101 (invalid handle), and default timeout of 20 seconds.
- Q: Is there a maximum number of concurrent Card Handles or CardSessions the system must support? → A: No software cap — the maximum is bounded by hardware only: total occupied slots across all connected terminals. One handle per occupied slot; no additional artificial limit.
