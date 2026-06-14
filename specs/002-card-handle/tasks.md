---
description: "Task list for Card Handle (transport-agnostic PC/SC + SICCT, CM_CARD_LIST)"
---

# Tasks: Card Handle (Transport-Agnostic, CM_CARD_LIST)

**Input**: Design documents from `/specs/002-card-handle/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included — testing discipline is mandated (Constitution Principle II; SC-002 ≥ coverage; SC-018 cross-transport parity; SC-019 dependency check).

**Organization**: Grouped by the three P1 user stories so each can be implemented and tested independently. All three transports share the `apdu-lib` foundation built in Phase 2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 / US3 (maps to spec.md user stories)
- Exact file paths included in each task

## Path Conventions

Multi-module Maven reactor. Key roots:
- Core (transport-neutral): `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/`
- PC/SC provider: `crypto-pcsc-lib/src/main/java/de/servicehealtherx/crypto/pcsc/`
- SICCT provider: `crypto-sicct-lib/src/main/java/de/servicehealtherx/crypto/sicct/`
- SICCT runtime/events/JPA: `quarkus-sicct-extension/runtime/...`, `sicct-lib/src/main/java/de/servicehealtherx/sicct/...`
- SOAP: `konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Module wiring so the transport-neutral core and both providers can compile against `apdu-lib`.

- [X] T001 Create the `de.servicehealtherx.apdu.card` and `de.servicehealtherx.apdu.card.transport` package directories under `apdu-lib/src/main/java/` and `apdu-lib/src/test/java/`
- [X] T002 Add `apdu-lib` dependency to `crypto-sicct-lib/pom.xml` (crypto-pcsc-lib already has it) so `SicctCryptoProvider` can use the `CmCardList` type and `CardReaderPort` (FR-061; Module-wiring action item in plan.md) — verified: crypto-sicct-lib compiles against apdu-lib
- [X] T003 [P] Verify `apdu-lib/pom.xml` has NO dependency on `sicct-lib` or any PC/SC-specific artifact (SC-019); add a `maven-enforcer-plugin` banned-dependency rule to keep it transport-neutral

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The transport-neutral domain model, CM_CARD_LIST, transport port, and factory in `apdu-lib`. **No user story can begin until this is complete.**

**⚠️ CRITICAL**: Every user story depends on these types.

- [X] T004 [P] ~~Create `CardType` enum~~ → **Reconciled**: reuse the existing feature-007 `de.servicehealtherx.apdu.model.CardType` (EGK, HBA, HBAX, SMC_B, KVK, UNKNOWN) as the single card-type source of truth; no new enum created (per implement-phase decision "reuse where possible")
- [X] T005 [P] Create the 8-field card-version value object as `CardVersionInfo` (renamed to avoid clash with the existing `apdu.model.CardVersion` generation enum) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CardVersionInfo.java`
- [X] T006 [P] Create the C2C/CHV value object as `AuthEntry` (renamed to avoid clash with the existing `apdu.model.AuthState` class) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/AuthEntry.java`
- [X] T007 Create sealed base `CardSessionContext` + subclasses `EgkCardSession`, `SmbCardSession`, `HbaxCardSession` (renamed to avoid clash with the existing `apdu.model.CardSession` record) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/` (depends on T006)
- [X] T008 Create `CardObject` (cardHandle, ctid, slotNo, iccsn, type, cardVersion, insertTime, cardHolderName, kvnr, certExpirationDate, certStatus, certOcspResponse, cardSessionList; + `CertStatus`/`OcspResult` enums) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CardObject.java` (reuses `apdu.model.CardType`; depends on T005, T007)
- [X] T009 Create `CardReaderPort` interface (transmit APDU, observe insert/remove via `PresenceListener`, expose ctid + `ReaderCapabilities`) + `CardTransportException` in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/transport/` (research D9)
- [X] T010 Create `CmCardList` class (per-provider; `activeCards`, `cardsByCtid`, `recentlyInvalidated` 48 h blacklist; `add`/`removeByHandle`/`removeBySlot`/`removeAllForTerminal`/`findByHandle`/`findBySlot`/`findAll`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CmCardList.java` (data-model §CM_CARD_LIST; depends on T008)
- [X] T011 Create `CardObjectFactory` (`createAndRegister`/`readAndCreate` build `CardObject` via `CardReaderPort` and add to a `CmCardList`; deep attribute read deferred to T016/US1) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CardObjectFactory.java` (depends on T009, T010)
- [X] T012 [P] Create a fake `CardReaderPort` test double (configurable capabilities, scriptable insert/remove, canned APDU responses) in `apdu-lib/src/test/java/de/servicehealtherx/apdu/card/transport/FakeCardReaderPort.java`
- [X] T013 [P] Unit test `CmCardList` add/remove/find, duplicate rejection, and 48 h no-reuse blacklist (FR-003, FR-060, FR-067) in `apdu-lib/src/test/java/de/servicehealtherx/apdu/card/CmCardListTest.java` — 6/6 green (depends on T010, T012)

**Checkpoint**: ✅ Transport-neutral core compiles and unit-tests in `apdu-lib` (103/103 tests pass) with no `sicct-lib`/PC/SC dependency (enforcer rule active, SC-019). **STOPPING HERE per agreed scope (Phase 1+2 only).**

> **Naming reconciliation (feature-007 overlap)**: reused `apdu.model.CardType`; new card-handle types use non-clashing names — `CardVersionInfo` (vs `apdu.model.CardVersion`), `AuthEntry` (vs `apdu.model.AuthState`), `CardSessionContext`/`EgkCardSession`/`SmbCardSession`/`HbaxCardSession` (vs `apdu.model.CardSession`). data-model.md still uses the original names; downstream US1–US3 tasks (T016, T019, T032, etc.) should reference the implemented names.

---

## Phase 3: User Story 1 — Card Insertion Creates a Usable Card Handle on Any Connected Reader (Priority: P1) 🎯 MVP

**Goal**: Through the `CardReaderPort` abstraction, a card insertion on ANY transport produces a `CardObject` in a `CmCardList` with full metadata, within 2 s, and removal invalidates it — all in transport-neutral code.

**Independent Test**: Drive the fake `CardReaderPort` to insert/remove; assert a handle with all fields appears in `CmCardList` within 2 s and is removed on ejection — exercising the core that both real transports reuse.

### Tests for User Story 1

- [X] T014 [P] [US1] Unit test card-attribute reading: ICCSN BCD from EF.GDO, tolerant EF.Version parse, AUT-cert CN/expiry, graceful null on unreadable (FR-005, FR-007, FR-009, FR-011) in `apdu-lib/src/test/java/de/servicehealtherx/apdu/card/CardAttributeReaderTest.java` — 9/9 green (test cert resource `src/test/resources/card/test-aut-cert.der`)
- [X] T015 [P] [US1] Unit test handle invalidation on `CardReaderPort` removal signal (FR-018) — covered in `apdu-lib/src/test/java/de/servicehealtherx/apdu/card/CardPresenceCoordinatorTest.java` (`test_FR_018_removal_invalidates_handle_and_fires_listener`)

### Implementation for User Story 1

- [X] T016 [US1] Implement card-attribute reading in `CardAttributeReader` (EF.GDO→ICCSN; EF.Version2→CardVersionInfo tolerant TLV; AUT cert→cardHolderName/certExpirationDate; unreadable→null per FR-005/FR-007) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CardAttributeReader.java`; wired into `CardObjectFactory.readAndCreate`. **Note**: KVNR (VSD/EF.PD) and object-system-specific AUT-cert-file selection deferred — `read(...)` populates ICCSN+version; cert fields filled when the provider supplies the cert DER.
- [X] T017 [US1] Implement the ≤2 s creation path + removal wiring as `CardPresenceCoordinator` (subscribes a `CardReaderPort`'s insert/remove → `CardObjectFactory.readAndCreate` / `CmCardList.removeBySlot`, forwards lifecycle events) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CardPresenceCoordinator.java` (FR-001, FR-018)
- [X] T018 [P] [US1] Define transport-neutral `CardLifecycleListener` (onCardInserted/onCardRemoved, no-op defaults) so providers/runtime publish CDI events without `apdu-lib` importing CDI in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CardLifecycleListener.java`
- [X] T019 [US1] Implement `EgkSessionLock` (atomic tryAcquire→sessionID, release by sessionID, isLocked, forceRelease) for the at-most-one-eGK rule (FR-022/C1, FR-028 / research D8) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/EgkSessionLock.java`

**Checkpoint**: ✅ Transport-agnostic handle lifecycle functional and tested via the fake port (119/119 module tests pass) — the MVP core both providers build on. **STOPPING per incremental scope (US1 done).**

---

## Phase 4: User Story 2 — Directly PC/SC-Connected Reader Creates a Card Handle (Priority: P1)

**Goal**: A real PC/SC reader (`javax.smartcardio`) drives the core: insertions land in `PcscCryptoProvider`'s own `CmCardList`, with synthesized `ctid`, capability-aware degradation, and logical eject.

**Independent Test**: Insert into a (simulated) PC/SC reader; confirm a handle with `ctid = nameUUIDFromBytes(readerName)` appears in the PCSC provider's list with field structure identical to SICCT; EjectCard returns OK without 4203.

### Tests for User Story 2

- [X] T020 [P] [US2] Unit test PC/SC `ctid` synthesis stable across reconnects + capability defaults + poll insert/remove (FR-068, FR-001) in `crypto-pcsc-lib/src/test/java/de/servicehealtherx/crypto/pcsc/PcscCardReaderPortTest.java` — 4/4 green
- [X] T021 [P] [US2] Test PC/SC insertion parity: CardObject added to PCSC `CmCardList` with synthesized ctid, field-set matches SICCT shape (FR-002, SC-016) in `crypto-pcsc-lib/.../PcscInsertionTest.java` — 4/4 green (implemented as a fake-seam unit test, not `@QuarkusTest` IT — no PC/SC subsystem needed)
- [X] T022 [P] [US2] Test logical eject on no-throwout reader returns OK without 4203 (FR-070); unknown handle → 4101 in `crypto-pcsc-lib/.../PcscEjectTest.java` — 2/2 green

### Implementation for User Story 2

- [X] T023 [US2] Implement `PcscCardReaderPort` over `javax.smartcardio` via a `PcscTerminal` seam (`SmartcardioPcscTerminal` = real wrapper) — transmit, `poll()` insert/remove detection, PC/SC capability flags, ctid synthesis — in `crypto-pcsc-lib/src/main/java/de/servicehealtherx/crypto/pcsc/PcscCardReaderPort.java` (research D11, D12)
- [X] T024 [US2] Wire `PcscCryptoProvider` to own its own `CmCardList` (FR-062), expose `cmCardList()`, and drive `PcscReaderRegistry` from a guarded `@PostConstruct` scheduler (starts even with no PC/SC subsystem) in `crypto-pcsc-lib/.../PcscCryptoProvider.java`
- [X] T025 [US2] Implement PC/SC ctid synthesis (`PcscCardReaderPort.synthesizeCtid`, FR-068) + reader registration & 500 ms poll cadence within the 2 s budget (FR-001) in `crypto-pcsc-lib/.../PcscReaderRegistry.java`. **Note**: ctid is synthesized; registering the reader as a JPA `CardTerminal` row (the entity lives in `sicct-lib`) is deferred to runtime wiring so `crypto-pcsc-lib` stays free of a sicct dependency.
- [X] T026 [US2] Implement PC/SC startup scan + unplug invalidation + replug rebuild via `PcscReaderRegistry.refreshTerminals()` (`CmCardList.removeAllForTerminal` on unplug; fresh handle on replug, FR-069)
- [X] T027 [US2] Implement logical eject (no 4203, 4101 on unknown handle) + prompt degradation for PC/SC in `crypto-pcsc-lib/.../PcscEjectHandler.java` (FR-070, FR-071, FR-063)

**Checkpoint**: ✅ PC/SC is a working first-class transport (crypto-pcsc-lib 10/10 tests green), independently testable via the `PcscTerminal` fake seam. **STOPPING per incremental scope (US2 done).**

> **US2 deviations**: (1) T021/T022 are fake-seam unit tests (not `@QuarkusTest` IT) — no hardware/Mockito needed. (2) `CardType` resolution returns `UNKNOWN` (ATR→type mapping deferred). (3) JPA `CardTerminal` row creation for PC/SC readers deferred to runtime (entity is in `sicct-lib`); ctid synthesis itself is done.

---

## Phase 5: User Story 3 — Unified Card View Aggregated Across Per-Provider Lists (Priority: P1)

**Goal**: SICCT provider implemented symmetrically; the card service aggregates both providers' separate lists for GetCards and routes any `cardHandle` to its owning provider. RequestCard/EjectCard work over both transports.

**Independent Test**: One card via SICCT, one via PC/SC; a single GetCards returns both (distinct ctid, each handle in exactly one list); each cardHandle resolves to the correct provider.

### Tests for User Story 3

- [X] T028 [P] [US3] Test unified GetCards aggregates both providers' lists; each cardHandle in exactly one list (FR-062, FR-064, FR-067) in `apdu-lib/.../card/CardListAggregatorTest.java` — 3/3 green (aggregator-level unit test; SOAP `@QuarkusTest` IT deferred with T030)
- [X] T029 [P] [US3] Test cardHandle routing resolves to the owning provider (FR-065) — `CardListAggregatorTest.test_FR_065_resolve_owner_routes_handle_to_its_provider_list`
- [ ] T030 [P] [US3] Parameterised parity `@QuarkusTest`: handle-creation/GetCards/RequestCard/EjectCard pass over BOTH transports (SC-018) in `konnektor-soap-server/.../TransportParityIT.java` — **DEFERRED** (needs T036/T037 SOAP dispatch)
- [ ] T031 [P] [US3] `@QuarkusTest` EjectCard cross-holder lock check returns 4093 (FR-053) in `konnektor-soap-server/.../EjectCardLockIT.java` — **DEFERRED** (needs T037)

### Implementation for User Story 3

- [X] T032 [US3] Implement `SicctCardReaderPort` over a `SicctChannel` seam (transmit; `onCardInserted`/`onCardRemoved` from the SICCT runtime; hasDisplay=true/hasMechanicalEject=true) in `crypto-sicct-lib/.../SicctCardReaderPort.java` — tested via fake channel. **Note**: the real `sicct-lib`/Netty `SicctChannel` binding is wired in runtime integration (Phase 6).
- [X] T033 [US3] Wire `SicctCryptoProvider` to own its own `CmCardList` (FR-062) and implement `CardListProvider` in `crypto-sicct-lib/.../SicctCryptoProvider.java`
- [X] T034 [US3] Implement `CardListAggregator` (concatenate each provider's `CmCardList.findAll`, de-dup by handle, `resolveOwner` for routing) — placed in **`apdu-lib`** (`de.servicehealtherx.apdu.card.CardListAggregator`, transport-neutral) rather than konnektor-soap-server, plus the `CardListProvider` interface (FR-064, FR-065)
- [X] T035 [US3] Implement `getCards()` over the aggregator (iterates `CardListProvider` beans, maps `CardObject`→`CardInfoType` incl. CardType mapping) in `konnektor-soap-server/.../KonnektorEventService.java` (added `apdu-lib` dep; compiles) (FR-041, FR-064)
- [ ] T036 [US3] Implement `requestCard()` over both transports with capability degradation in `KonnektorCardTerminalService.java` (FR-033–FR-040, FR-063, FR-071) — **DEFERRED** (terminal command dispatch + SICCT channel binding)
- [ ] T037 [US3] Implement `ejectCard()` over both transports — mechanical for SICCT, logical for PC/SC (no 4203), lock check 4093 (FR-051–FR-056, FR-070) — **DEFERRED** (uses `PcscEjectHandler` + a SICCT eject + `EgkSessionLock`; SOAP wiring)

**Checkpoint**: ✅ Unified GetCards spans both transports via per-provider lists + `CardListAggregator` (apdu-lib 122, crypto-sicct-lib 5, crypto-pcsc-lib 10 tests green; konnektor-soap-server compiles). **STOPPING per incremental scope.**

> **US3 deviations**: (1) `CardListAggregator` lives in `apdu-lib` (transport-neutral) not konnektor-soap-server — cleaner + reusable. (2) T028/T029 satisfied by aggregator unit tests; SOAP `@QuarkusTest` ITs (T030/T031) deferred. (3) `requestCard`/`ejectCard` SOAP dispatch (T036/T037) deferred — they need terminal command dispatch and the real SICCT channel binding (Phase 6 runtime).

---

## Phase 6: SICCT Runtime, Events & Persistence (Cross-Cutting)

**Purpose**: SICCT-only eventing, startup reconstruction, and the G2.0 admin log that the runtime layer owns.

- [X] T038 [P] Create CDI event records `CardInsertedEvent`, `CardRemovedEvent`, `CertCardStatusEvent`, `CardSessionTimeoutEvent` in `sicct-lib/src/main/java/de/servicehealtherx/sicct/event/` (carry gemSpec event params as primitives → no apdu-lib coupling; FR-046–FR-049)
- [ ] T039 Implement `SicctEventPublisher` (`@Observes` the `CardLifecycleListener` hook from T018 → TUC_KON_256 log/dispatch: CARD/INSERTED, CARD/REMOVED, CERT/CARD/STATUS, CARD/SESSION/TIMEOUT) in `quarkus-sicct-extension/runtime/.../SicctEventPublisher.java` (FR-046–FR-049) — **DEFERRED** (runtime CDI wiring)
- [ ] T040 Extend `SicctTerminalManager` for SICCT startup reconstruction (→ SICCT provider's `CmCardList`) and `onTerminalDisconnected` invalidation (FR-042, FR-044, FR-045) in `quarkus-sicct-extension/runtime/.../SicctTerminalManager.java` — **DEFERRED** (runtime Netty/SICCT wiring; binds `SicctChannel`)
- [X] T041 [P] Create `G2CardLog` JPA Panache entity + ICCSN-unique `upsert` in `sicct-lib/src/main/java/de/servicehealtherx/sicct/jpa/G2CardLog.java`, and the pure pseudonym computation `G2Pseudonym` (first 10 hex of SHA-256(ICCSN‖expiry)) in `apdu-lib/.../card/G2Pseudonym.java` — tested (A_25801, FR-050). **Note**: pseudonym lives in apdu-lib (transport-neutral, keeps sicct-lib decoupled); the admin **JMX MBean** view is deferred to runtime.

> **Phase 6 progress**: T038 (event records) + T041 (G2 pseudonym + JPA log) done & compiling; pseudonym unit-tested in apdu-lib. T039/T040 (event publisher + terminal-manager wiring) deferred — they need the live `quarkus-sicct-extension` runtime (CDI observers + Netty SICCT channel), best done with the app runnable.

---

## Phase 7: Card Sessions, eGK Lifecycle & Comfort Signature (Cross-Cutting)

**Purpose**: Session subtypes and lifecycle that ride on top of CM_CARD_LIST regardless of transport.

- [X] T042 Implement TUC_KON_026 session create/get with subtype identity keys (eGK / SM-B / HBAx; FR-021–FR-025) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CardSessionService.java` (placed in apdu-lib — transport-neutral — not konnektor-soap-server)
- [X] T043 Implement eGK StartCardSession/StopCardSession (TUC_KON_223/224): lock via `EgkSessionLock`, UUID sessionID, error 4288 on unknown sessionID (FR-026–FR-028) in `CardSessionService.java`. **Note**: CARD_SESSION_TIMEOUT auto-stop timer scheduling deferred to runtime wiring (needs a scheduler); the lock/sessionID lifecycle is complete and tested.
- [X] T044 [P] Implement HBAx comfort-signature activate/deactivate with countRemaining + hardware cap 250 and concurrent-session limit (FR-029–FR-032; 4278 on max via new `CardServiceException`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/ComfortSignatureService.java`

> **Phase 7 deviations**: (1) `CardSessionService`/`ComfortSignatureService` live in `apdu-lib` (transport-neutral, reusable by the SOAP layer) rather than konnektor-soap-server. (2) Added `CardServiceException` for card-service codes outside `TucException`'s validated [4001,4094] range (4278/4288/4101). (3) The `CARD_SESSION_TIMEOUT` and comfort-signature timers are modeled as fields; their scheduled firing is wired in runtime integration.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T045 [P] Verify JaCoCo coverage ≥ 80% threshold (SC-002) — `apdu-lib` passes the `jacoco:check` gate at **89.3%** line coverage (704/788). `crypto-pcsc-lib`/`crypto-sicct-lib` have no jacoco gate configured; their card code is unit-tested
- [X] T046 [P] Confirm cardHandle never logged above DEBUG (Constitution Principle III) — verified: zero info/warn/error/stdout logging in the new `apdu/card/**` + provider classes
- [X] T047 [P] Add transport-agnostic component diagram (per-provider `CmCardList` + `CardReaderPort`, both transports, aggregated GetCards) at `specs/002-card-handle/diagrams/component-transport.puml` (+ README index)
- [ ] T048 Run `quickstart.md` Scenarios 1–12 end-to-end (both transports), including parity Scenarios 10–12; record results — **DEFERRED** (needs the live Quarkus app + SICCT terminal/emulator)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **US1 (Phase 3)**: Depends on Foundational. The transport-neutral MVP core.
- **US2 (Phase 4)**: Depends on Foundational + US1 core (reuses `CardObjectFactory`/`CmCardList`).
- **US3 (Phase 5)**: Depends on Foundational + US1; consumes both providers (US2's PCSC port + the SICCT port built here) for aggregation.
- **Phase 6/7 (Cross-cutting)**: Depend on US1 (lifecycle hook T018) and the relevant provider wiring.
- **Polish (Phase 8)**: After all desired stories complete.

### User Story Dependencies

- **US1 (P1)**: Independent — testable purely via the fake `CardReaderPort`.
- **US2 (P1)**: Builds on the US1 core; independently testable on the PC/SC path.
- **US3 (P1)**: Needs at least the SICCT port (built in its phase) + US2's PCSC port to demonstrate aggregation; GetCards aggregation itself is independently testable with two fake providers.

### Parallel Opportunities

- Setup: T003 ∥ (after T001/T002).
- Foundational: T004, T005, T006 in parallel; then T007 → T008 → T010 → T011; T012/T013 parallel after T010.
- US1 tests T014, T015 in parallel; T018 parallel with T016/T017.
- US2 tests T020–T022 in parallel; impl T023 → T024 → (T025, T026, T027).
- US3 tests T028–T031 in parallel; impl T032 → T033 → T034 → (T035, T036, T037).
- Cross-cutting: T038, T041 parallel; T044 parallel with T042/T043.
- Polish: T045, T046, T047 parallel; T048 last.

---

## Parallel Example: Foundational Phase

```bash
# Independent value/enum types together:
Task: "Create CardType enum in apdu-lib/.../card/CardType.java"          # T004
Task: "Create CardVersion value object in apdu-lib/.../card/CardVersion.java"  # T005
Task: "Create AuthState value object in apdu-lib/.../card/AuthState.java"      # T006
```

## Parallel Example: User Story 3 Tests

```bash
Task: "GetCards aggregation IT"        # T028
Task: "CardHandle routing IT"          # T029
Task: "Transport parity IT"            # T030
Task: "EjectCard lock 4093 IT"         # T031
```

---

## Implementation Strategy

### MVP First (US1 only)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1.
4. **STOP & VALIDATE**: transport-agnostic handle lifecycle via the fake `CardReaderPort` (quickstart Scenario 10 core). This is the MVP — the reusable core both transports build on.

### Incremental Delivery

1. Foundation + US1 → core proven.
2. US2 (PC/SC) → first real transport → demo.
3. US3 (SICCT + aggregation) → unified view across both transports → demo.
4. Phases 6–7 add eventing, sessions, comfort signature.

---

## Notes

- [P] = different files, no incomplete-task dependency.
- `apdu-lib` MUST stay free of `sicct-lib`/PC/SC types (T003 enforces; SC-019).
- Each cardHandle is unique system-wide across the union of per-provider lists (FR-067).
- Verify tests fail before implementing; commit after each task or logical group.
- Carried action item: T002 adds the `apdu-lib` dependency to `crypto-sicct-lib`.
