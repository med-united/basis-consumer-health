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

- [ ] T014 [P] [US1] Unit test `CardObjectFactory` populates cardHandle (UUID), type, ICCSN, CardVersion, insertTime, cardHolderName, kvnr (eGK only), certExpirationDate; certStatus/certOcspResponse default `NOT_AVAILABLE` (FR-003–FR-013) in `apdu-lib/src/test/java/de/servicehealtherx/apdu/card/CardObjectFactoryTest.java`
- [ ] T015 [P] [US1] Unit test handle invalidation on `CardReaderPort` removal signal (FR-018) in `apdu-lib/src/test/java/de/servicehealtherx/apdu/card/CardRemovalTest.java`

### Implementation for User Story 1

- [ ] T016 [US1] Implement card-attribute reading in `CardObjectFactory` (EF.Version → CardVersion; AUT cert → cardHolderName/kvnr/certExpirationDate; tolerate unreadable sub-fields per FR-007) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CardObjectFactory.java`
- [ ] T017 [US1] Implement the ≤2 s creation path and removal handling wiring in `CmCardList`/`CardObjectFactory` (FR-001, FR-018) — insertion detection callback → `add`; removal → `removeBySlot`
- [ ] T018 [P] [US1] Define a transport-neutral card-lifecycle notification hook (insert/remove) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/CardLifecycleListener.java` so providers/runtime can publish CDI events without `apdu-lib` importing CDI
- [ ] T019 [US1] Implement eGK session lock holder (`sessionID` UUID + timeout future) keyed in `CmCardList` for the at-most-one-eGK-session rule (FR-022 / research D8) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/card/EgkSessionLock.java`

**Checkpoint**: Transport-agnostic handle lifecycle fully functional and testable via the fake port — the MVP core both providers build on.

---

## Phase 4: User Story 2 — Directly PC/SC-Connected Reader Creates a Card Handle (Priority: P1)

**Goal**: A real PC/SC reader (`javax.smartcardio`) drives the core: insertions land in `PcscCryptoProvider`'s own `CmCardList`, with synthesized `ctid`, capability-aware degradation, and logical eject.

**Independent Test**: Insert into a (simulated) PC/SC reader; confirm a handle with `ctid = nameUUIDFromBytes(readerName)` appears in the PCSC provider's list with field structure identical to SICCT; EjectCard returns OK without 4203.

### Tests for User Story 2

- [ ] T020 [P] [US2] Unit test PC/SC `ctid` synthesis is stable across reconnects (`UUID.nameUUIDFromBytes(readerName)`, FR-068) in `crypto-pcsc-lib/src/test/java/de/servicehealtherx/crypto/pcsc/PcscCtidTest.java`
- [ ] T021 [P] [US2] Integration test PC/SC insertion parity: handle field-set identical to SICCT (SC-016), CardObject added to PCSC `CmCardList` (FR-002) in `crypto-pcsc-lib/src/test/java/de/servicehealtherx/crypto/pcsc/PcscInsertionIT.java`
- [ ] T022 [P] [US2] Integration test logical eject on no-throwout reader returns OK without 4203 (FR-070) and skips prompt when no display (FR-071) in `crypto-pcsc-lib/src/test/java/de/servicehealtherx/crypto/pcsc/PcscEjectIT.java`

### Implementation for User Story 2

- [ ] T023 [US2] Implement `PcscCardReaderPort` over `javax.smartcardio` (transmit; `waitForChange`/`isCardPresent` insert/remove detection; capability flags hasDisplay=false/hasMechanicalEject=false/hasSlotSelection=false; ctid synthesis) in `crypto-pcsc-lib/src/main/java/de/servicehealtherx/crypto/pcsc/PcscCardReaderPort.java` (research D11, D12)
- [ ] T024 [US2] Wire `PcscCryptoProvider` to own a `CmCardList` instance and drive `PcscCardReaderPort` + `CardObjectFactory` on insert/remove (FR-062) in `crypto-pcsc-lib/src/main/java/de/servicehealtherx/crypto/pcsc/PcscCryptoProvider.java`
- [ ] T025 [US2] Implement PC/SC reader registration as a `CardTerminal` entry with synthesized ctid (FR-068) and reader poll cadence within the 2 s budget (FR-001) in `crypto-pcsc-lib/src/main/java/de/servicehealtherx/crypto/pcsc/PcscReaderRegistry.java`
- [ ] T026 [US2] Implement PC/SC startup scan + unplug invalidation + replug rebuild parity (FR-069) via `CmCardList.removeAllForTerminal` / `CardObjectFactory` in `PcscCardReaderPort`/`PcscReaderRegistry`
- [ ] T027 [US2] Implement logical eject (no 4203) and prompt/slot degradation for PC/SC in `crypto-pcsc-lib/src/main/java/de/servicehealtherx/crypto/pcsc/PcscEjectHandler.java` (FR-070, FR-071, FR-063)

**Checkpoint**: PC/SC is a fully working first-class transport, independently testable.

---

## Phase 5: User Story 3 — Unified Card View Aggregated Across Per-Provider Lists (Priority: P1)

**Goal**: SICCT provider implemented symmetrically; the card service aggregates both providers' separate lists for GetCards and routes any `cardHandle` to its owning provider. RequestCard/EjectCard work over both transports.

**Independent Test**: One card via SICCT, one via PC/SC; a single GetCards returns both (distinct ctid, each handle in exactly one list); each cardHandle resolves to the correct provider.

### Tests for User Story 3

- [ ] T028 [P] [US3] Integration test unified GetCards aggregates both providers' lists; each cardHandle in exactly one list (FR-062, FR-064, FR-067, SC-017) in `konnektor-soap-server/src/test/java/de/servicehealtherx/konnektor/soap/GetCardsAggregationIT.java`
- [ ] T029 [P] [US3] Integration test cardHandle routing resolves to the owning provider (FR-065) in `konnektor-soap-server/src/test/java/de/servicehealtherx/konnektor/soap/CardHandleRoutingIT.java`
- [ ] T030 [P] [US3] Parameterised parity test: handle-creation/GetCards/RequestCard/EjectCard pass over BOTH transports (SC-018) in `konnektor-soap-server/src/test/java/de/servicehealtherx/konnektor/soap/TransportParityIT.java`
- [ ] T031 [P] [US3] Integration test EjectCard cross-holder lock check returns 4093 (FR-053) in `konnektor-soap-server/src/test/java/de/servicehealtherx/konnektor/soap/EjectCardLockIT.java`

### Implementation for User Story 3

- [ ] T032 [US3] Implement `SicctCardReaderPort` over `sicct-lib` (transmit; insert/remove from `SicctChannelHandler`; capability flags hasDisplay=true/hasMechanicalEject=true) in `crypto-sicct-lib/src/main/java/de/servicehealtherx/crypto/sicct/SicctCardReaderPort.java` (research D9)
- [ ] T033 [US3] Wire `SicctCryptoProvider` to own a `CmCardList` instance and drive `SicctCardReaderPort` + `CardObjectFactory` (FR-062) in `crypto-sicct-lib/src/main/java/de/servicehealtherx/crypto/sicct/SicctCryptoProvider.java`
- [ ] T034 [US3] Implement a `CardServiceAggregator` that concatenates each provider's `CmCardList.findAll` and resolves a cardHandle across providers (FR-064, FR-065) in `konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/CardServiceAggregator.java`
- [ ] T035 [US3] Implement `getCards()` over the aggregator (read-only, per-tenant filter, ≤100 ms) in `konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorEventService.java` (contracts/GetCards.md; FR-041, FR-064)
- [ ] T036 [US3] Implement `requestCard()` over both transports with capability degradation in `konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorCardTerminalService.java` (contracts/RequestCard.md; FR-033–FR-040, FR-063, FR-071)
- [ ] T037 [US3] Implement `ejectCard()` over both transports — mechanical for SICCT, logical for PC/SC (no 4203), lock check 4093 (contracts/EjectCard.md; FR-051–FR-056, FR-070) in `KonnektorCardTerminalService.java`

**Checkpoint**: All three P1 stories independently functional; unified view spans both transports.

---

## Phase 6: SICCT Runtime, Events & Persistence (Cross-Cutting)

**Purpose**: SICCT-only eventing, startup reconstruction, and the G2.0 admin log that the runtime layer owns.

- [ ] T038 [P] Create CDI event records `CardInsertedEvent`, `CardRemovedEvent`, `CertCardStatusEvent`, `CardSessionTimeoutEvent` in `sicct-lib/src/main/java/de/servicehealtherx/sicct/event/`
- [ ] T039 Implement `SicctEventPublisher` (`@Observes` the `CardLifecycleListener` hook from T018 → TUC_KON_256 log/dispatch: CARD/INSERTED, CARD/REMOVED, CERT/CARD/STATUS, CARD/SESSION/TIMEOUT) in `quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/SicctEventPublisher.java` (FR-046–FR-049)
- [ ] T040 Extend `SicctTerminalManager` for SICCT startup reconstruction (→ SICCT provider's `CmCardList`) and `onTerminalDisconnected` invalidation (FR-042, FR-044, FR-045) in `quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/SicctTerminalManager.java`
- [ ] T041 [P] Create `G2CardLog` JPA entity + ICCSN-unique upsert and JMX MBean for the G2.0 pseudonym admin log (A_25801, FR-050) in `sicct-lib/src/main/java/de/servicehealtherx/sicct/jpa/G2CardLog.java`

---

## Phase 7: Card Sessions, eGK Lifecycle & Comfort Signature (Cross-Cutting)

**Purpose**: Session subtypes and lifecycle that ride on top of CM_CARD_LIST regardless of transport.

- [ ] T042 Implement TUC_KON_026 session create/get with subtype identity keys (eGK / SM-B / HBAx; FR-021–FR-025) in `konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/CardSessionService.java`
- [ ] T043 Implement eGK StartCardSession/StopCardSession (TUC_KON_223/224): lock via `EgkSessionLock`, UUID sessionID, CARD_SESSION_TIMEOUT timer, error 4288 on unknown sessionID (FR-026–FR-028) in `CardSessionService.java`
- [ ] T044 [P] Implement HBAx comfort-signature activate/deactivate with countRemaining/timeRemaining and limits (FR-029–FR-032; 4278 on max) in `konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/ComfortSignatureService.java`

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T045 [P] Verify JaCoCo coverage ≥ threshold across `apdu-lib`, `crypto-pcsc-lib`, `crypto-sicct-lib` (SC-002); add tests for any gaps
- [ ] T046 [P] Confirm cardHandle never logged above DEBUG (Constitution Principle III) across new classes
- [ ] T047 [P] Update `specs/002-card-handle/diagrams/` PlantUML to show per-provider `CmCardList` + `CardReaderPort` (both transports)
- [ ] T048 Run `quickstart.md` Scenarios 1–12 end-to-end (both transports), including parity Scenarios 10–12; record results

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
