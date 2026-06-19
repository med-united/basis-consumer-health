---

description: "Task list for VSDService — Local ReadVSD (Card-to-Card + eGK Read)"
---

# Tasks: VSDService — Local ReadVSD (Card-to-Card + eGK Read)

**Input**: Design documents from `/specs/011-read-vsd/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: INCLUDED — Constitution Principle II mandates test-first development and
one named test per Afo (Principle V). Test tasks are written first and MUST fail
before the implementation task in the same group is started. Test names include the
Afo ID (e.g. `test_VSDM_A_2567_*`).

**Organization**: Tasks are grouped by user story (US1–US4 from spec.md) for
independent implementation and testing. Phase order follows priority (P1 → P2 → P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1/US2/US3/US4 (user-story phases only)

## Path Conventions

- `apdu-lib/src/main/java/de/servicehealtherx/apdu/` — card-level VSDM logic (`vsdm/`, `c2c/`, `card/`, `tuc/`, `model/`)
- `apdu-lib/src/test/java/de/servicehealtherx/apdu/` — unit tests
- `konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/` — SOAP endpoint
- `konnektor-soap-server/src/test/java/de/servicehealtherx/konnektor/soap/` — @QuarkusTest (first in module)
- `api-telematik/` — WSDL codegen

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Generate the SOAP contract and wire configuration.

- [x] T001 Add a `cxf-codegen-plugin` `<wsdlOption>` for `conn/vsds/VSDService.wsdl` in `api-telematik/pom.xml`, then build `api-telematik` and confirm `de.gematik.ws.conn.vsds.vsdservice.v5_2.{VSDServicePortType,ReadVSD,ReadVSDResponse,VSDStatusType,FaultMessage}` are generated (D1)
- [x] T002 [P] Confirm `conn/vsds/VSDService.wsdl` + `VSDService.xsd` land on the runtime classpath (maven-resources copy of `conn/**`); add a `vsds` copy entry in `api-telematik/pom.xml` only if the build shows it missing (D1)
- [x] T003 [P] Add MicroProfile config property `vsdm.read.timeout-ms` (default `30000`) to `quarkus-server/src/main/resources/application.properties` (or the konnektor config source) (FR-027, D6)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Card-access primitives and skeletons every user story builds on.

**⚠️ CRITICAL**: No user-story work can begin until this phase is complete.

- [x] T004 Extend `apdu-lib/.../apdu/model/GematikISO7816.java` with the DF.HCA AID (`D2 76 00 00 01 02`) and EF constants: `EF.PD` (FID `D001`, SFID 1), `EF.VD` (FID `D002`, SFID 2), `EF.StatusVD` (FID `D00C`, SFID 12), `EF.GVD` (FID `D003`, SFID 3) (contracts/egk-vsdm-files.md, D5)
- [x] T005 [P] Create `EgkVsdmFile` enum (FID/SFID/access condition: AlwaysRead vs C2C) in `apdu-lib/.../apdu/vsdm/EgkVsdmFile.java` (data-model.md §3)
- [x] T006 [P] Unit test `ApduExecutor` against a fake `CardReaderPort`: step ordering, `ExpectedStatusSet` validation, exception on unexpected SW — write first, must fail — in `apdu-lib/src/test/java/.../apdu/card/ApduExecutorTest.java` (contracts/apdu-executor.md)
- [x] T007 [P] Unit test `EgkFileReader` multi-block read (256-byte blocks, `0x6282` EOF) against a fake port — write first, must fail — in `apdu-lib/src/test/java/.../apdu/card/EgkFileReaderTest.java`
- [x] T008 Implement `ApduExecutor(CardReaderPort)` in `apdu-lib/.../apdu/card/ApduExecutor.java` — execute `TucGenerationResult` steps, validate each SW, return responses; never log response bodies (contracts/apdu-executor.md, D3)
- [x] T009 Implement `EgkFileReader` (SELECT DF.HCA + looped READ BINARY of an EF, optional Secure-Messaging session) in `apdu-lib/.../apdu/card/EgkFileReader.java` (depends on T004, T008)
- [x] T010 [P] Create `VsdmReadException` (carries `errorCode` + non-PII detail) in `apdu-lib/.../apdu/vsdm/VsdmReadException.java` (data-model.md §2)
- [x] T011 [P] Create `VsdReadResult` + `VsdStatus` value objects (raw byte payloads, never logged/persisted) in `apdu-lib/.../apdu/vsdm/` (data-model.md §2, FR-028)
- [x] T012 Create `ReadVsdService` skeleton + `ReadVsdRequest` normalisation (handles + context) in `apdu-lib/.../apdu/vsdm/ReadVsdService.java`
- [x] T013 Scaffold `KonnektorVSDService` with `@CXFEndpoint("/conn/VSDService")` + `@WebService(endpointInterface="…vsdservice.v5_2.VSDServicePortType")` implementing `readVSD`, delegating to `ReadVsdService`, in `konnektor-soap-server/.../soap/KonnektorVSDService.java` (D1, contracts/readvsd-soap.md)
- [x] T014 [P] Create `VsdmFaultFactory` skeleton (`VsdmReadException` → `de.gematik.ws.tel.error.v2.Error` + `FaultMessage`) in `konnektor-soap-server/.../soap/VsdmFaultFactory.java`

**Checkpoint**: Card-read primitives, value objects, and the SOAP endpoint skeleton exist and compile.

---

## Phase 3: User Story 1 - Authenticated read of insured master data (Priority: P1) 🎯 MVP

**Goal**: Return PD + VD + VSD_Status from a valid eGK (the AlwaysRead payload), with context check, reservation and eGK-validity check.

**Independent Test**: Call `ReadVSD` (flags false) against a fake card returning EF.PD/EF.VD/EF.StatusVD → response has `PersoenlicheVersichertendaten`, `AllgemeineVersicherungsdaten`, populated `VSD_Status`, Base64, no `Pruefungsnachweis`; no external TI call.

### Tests for User Story 1 (write first, must fail) ⚠️

- [x] T015 [P] [US1] Unit test `StatusVdConverter` (Tab_FM_VSDM_21): Status `[01]`, BCD Timestamp→`dateTime`, BCD Version→`7.3.1`, unknown storage-structure→abort — `test_VSDM_A_2708_*`, `test_VSDM_A_2979_*` — in `apdu-lib/src/test/java/.../apdu/vsdm/StatusVdConverterTest.java`
- [x] T016 [P] [US1] Unit test `ReadVsdService` mandatory read with mocked `ApduExecutor`/`EgkFileReader`: returns PD+VD+Status; VD offsets honoured (no GVD-from-EF.VD) — `test_VSDM_A_2567_*`…`test_VSDM_A_2571_*`, `test_VSDM_A_2784_*` — in `apdu-lib/src/test/java/.../apdu/vsdm/ReadVsdServiceMandatoryTest.java`
- [x] T017 [P] [US1] `@QuarkusTest` `KonnektorVSDService`: valid request (flags false) → `ReadVSDResponse` with PD/VD/VSD_Status, Base64, no PN — `test_VSDM_A_2634_*`, `test_VSDM_A_2691_*` — in `konnektor-soap-server/src/test/java/.../soap/KonnektorVSDServiceReadTest.java`

### Implementation for User Story 1

- [x] T018 [P] [US1] Implement `StatusVdConverter` (EF.StatusVD bytes → `VsdStatus`) in `apdu-lib/.../apdu/vsdm/StatusVdConverter.java` (FR-018)
- [x] T019 [US1] Implement context check + handle resolution (`CmCardList.findByHandle`) + eGK session (`CardSessionService`, TUC_KON_026) + reserve (`TucKon023ReserveCard`) in `ReadVsdService` (FR-010, FR-014)
- [x] T020 [US1] Implement eGK validity gate (TUC_KON_018 blocking; generation G1-plus/G2/G2.1 only) in `ReadVsdService` (FR-015, FR-016)
- [x] T021 [US1] Implement mandatory container read via `EgkFileReader`: EF.StatusVD first (`Status='1'`→3001), then EF.PD and EF.VD (honour VD start/end offsets) (FR-017, FR-019, FR-020, FR-022)
- [x] T022 [US1] Map `VsdReadResult` → `ReadVSDResponse` in `KonnektorVSDService` (Base64 raw bytes verbatim, build `VSD_Status`, never set `Pruefungsnachweis`) (FR-004, FR-005)
- [x] T023 [US1] Release the eGK reservation on success and on every abort path; assert decoded VSD/PII is never logged (FR-014, FR-028)

**Checkpoint**: MVP — a primary system reads PD/VD/VSD_Status end-to-end. Independently testable and demoable.

---

## Phase 4: User Story 2 - Protected data (GVD) via C2C, and audit logging (Priority: P2)

**Goal**: Card-to-card authentication → AUT_VSD Trusted Channel → SM-wrapped read of EF.GVD (returned when authorised, omitted otherwise with PD+VD still returned); write the data-access audit to the eGK.

**Independent Test**: With an authorised SMC-B → response includes `GeschuetzteVersichertendaten`; under an unauthorised condition → GVD omitted, PD+VD+Status still returned, no fault; a "read protected VSD" audit entry is written.

### Tests for User Story 2 (write first, must fail) ⚠️

- [x] T024 [P] [US2] Unit test `CvcChainParser`: parse CVC TLV (7F21/7F4E/5F37), extract CAR/CHR/CHAT, order chain root→CA→leaf — in `apdu-lib/src/test/java/.../apdu/c2c/CvcChainParserTest.java`
- [x] T025 [P] [US2] Unit test `SecureMessagingSession` wrap/unwrap (AES-CMAC + AES-CBC, send-sequence counter) with known vectors — in `apdu-lib/src/test/java/.../apdu/c2c/SecureMessagingSessionTest.java`
- [x] T026 [P] [US2] Unit test `TucKon005CardToCardAuth` APDU sequence vs fake eGK/SMC-B (READ CVCs → MSE+PSO VERIFY CERTIFICATE → two-step GENERAL AUTHENTICATE) returning an SM session — `test_VSDM_A_2572_*`, `test_VSDM_A_2573_*` — in `apdu-lib/src/test/java/.../apdu/tuc/TucKon005CardToCardAuthTest.java`
- [x] T027 [P] [US2] `@QuarkusTest`: authorised → GVD present; unauthorised → GVD omitted with PD+VD+Status present, no fault — `test_VSDM_A_2574_*`, `test_VSDM_A_2647_*` — in `konnektor-soap-server/src/test/java/.../soap/KonnektorVSDServiceGvdTest.java`
- [x] T028 [P] [US2] Unit test audit write + actor extraction from SMC-B/HBA AUT cert (TUC_KON_006/034) and abort-on-write-failure — `test_VSDM_A_2586_*`, `test_VSDM_A_2587_*`, `test_VSDM_A_2654_*` — in `apdu-lib/src/test/java/.../apdu/vsdm/VsdAuditWriteTest.java`

### Implementation for User Story 2

- [x] T029 [P] [US2] Implement `CvcChainParser` (Bouncy Castle TLV) in `apdu-lib/.../apdu/c2c/CvcChainParser.java` (contracts/c2c-authentication.md)
- [x] T030 [P] [US2] Implement `SecureMessagingSession` (BC AES-CMAC/CBC wrap/unwrap) in `apdu-lib/.../apdu/c2c/SecureMessagingSession.java`
- [x] T031 [US2] Replace the stub with real `TucKon005CardToCardAuth` (READ CVCs, MSE SET + PSO VERIFY CERTIFICATE, MSE SET, two-step GENERAL AUTHENTICATE; return `SecureMessagingSession`) in `apdu-lib/.../apdu/tuc/TucKon005CardToCardAuth.java` (depends on T029, T030)
- [x] T032 [US2] Add PIN security-state check before C2C (TUC_KON_022): PIN.SMC→3041, PIN.CH→3042 in `ReadVsdService` (FR-012)
- [x] T033 [US2] Wire C2C into `ReadVsdService`: on AUT_VSD read EF.GVD via `EgkFileReader` (SM-wrapped); on no authorisation omit GVD and continue (FR-021)
- [x] T034 [US2] Implement data-access audit write (TUC_KON_006 + actor via TUC_KON_034, incl. "read protected VSD" entry); abort and return no VSD if the write fails (FR-013, FR-023)

**Checkpoint**: Full VSD read (PD+VD+GVD+Status) with C2C and on-card audit; US1 still passes.

---

## Phase 5: User Story 3 - Local-only contract enforced (Priority: P2)

**Goal**: Reject `PerformOnlineCheck=true` and `ReadOnlineReceipt=true` with SOAP faults before any card access; never contact an external TI service; never emit a Prüfungsnachweis.

**Independent Test**: `PerformOnlineCheck=true` → fault, no read; `ReadOnlineReceipt=true` → fault; both false → proceed; no `Pruefungsnachweis` in any response; zero external connections.

### Tests for User Story 3 (write first, must fail) ⚠️

- [x] T035 [P] [US3] `@QuarkusTest`: `PerformOnlineCheck=true`→fault & no read (FR-007); `ReadOnlineReceipt=true`→fault (FR-008); both false→proceed; response never has PN (FR-009, SC-006) — in `konnektor-soap-server/src/test/java/.../soap/KonnektorVSDServiceLocalOnlyTest.java`
- [x] T036 [P] [US3] Test asserting zero external TI connections opened during a read (SC-004) — in `konnektor-soap-server/src/test/java/.../soap/NoExternalCallTest.java`

### Implementation for User Story 3

- [x] T037 [US3] Reject the online flags in `KonnektorVSDService`/`ReadVsdService` before any card access, via `VsdmFaultFactory`; guarantee no UFS/VSDD/CMS/Intermediär code path exists (FR-006, FR-007, FR-008, FR-009)

**Checkpoint**: Local-only boundary enforced and verifiable.

---

## Phase 6: User Story 4 - Correct gematik faults for card/data problems (Priority: P3)

**Goal**: Map every card/data failure to the spec-mandated fault code; honour the hard timeout and fail-fast same-eGK concurrency.

**Independent Test**: Drive each failure → correct code (3041/3042/3001/3011/114/107/106), Severity `Fatal`, single Trace, no stack trace; timeout aborts+releases; concurrent same-eGK → card-busy.

### Tests for User Story 4 (write first, must fail) ⚠️

- [x] T038 [P] [US4] `@QuarkusTest` fault mapping: 3041/3042 (PIN), 3001 (inconsistent), 3011 (read fail), 114 (DF.HCA blocked), 107/106 (cert) — assert code, `Fatal`, single Trace, no stack trace — `test_VSDM_A_2682_*`, `test_VSDM_A_2660_*` — in `konnektor-soap-server/src/test/java/.../soap/KonnektorVSDServiceFaultTest.java`
- [x] T039 [P] [US4] Test: hard timeout (default 30 s) aborts the operation and releases the eGK reservation (FR-027, SC-008) — in `apdu-lib/src/test/java/.../apdu/vsdm/ReadVsdTimeoutTest.java`
- [x] T040 [P] [US4] Test: second concurrent call for the same eGK → card-busy fault, no blocking; different cards run concurrently (FR-029, FR-026, SC-010) — in `apdu-lib/src/test/java/.../apdu/vsdm/ReadVsdConcurrencyTest.java`

### Implementation for User Story 4

- [x] T041 [US4] Complete `VsdmFaultFactory` code mapping (3001/3011/3041/3042 + OM 106/107/114), Severity `Fatal`, EventID + LogReference, single Trace, no PII/stack trace in `konnektor-soap-server/.../soap/VsdmFaultFactory.java` (FR-024, FR-025)
- [x] T042 [US4] Wrap `ReadVsdService.read` in the configurable hard timeout (`vsdm.read.timeout-ms`); on expiry abort with a fault and release the reservation (FR-027)
- [x] T043 [US4] Ensure same-eGK reservation conflict (`TucKon023ReserveCard`) fails fast with a card-busy fault; verify different-card calls remain concurrent (FR-029, FR-026)

**Checkpoint**: All user stories independently functional; full fault contract satisfied.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T044 [P] Run `specs/011-read-vsd/quickstart.md` end-to-end against the fake card backend and confirm every expected outcome
- [x] T045 [P] Verify unit-test coverage ≥ 80% for the new `apdu-lib` `vsdm`/`c2c`/`card` and `konnektor-soap-server` `soap` packages (Principle II, SC-007)
- [x] T046 [P] Confirm the byte-exact DF.HCA AID from gemSpec_eGK_ObjSys §5.4 and reconcile `GematikISO7816` if it differs (research.md open item, D4)
- [x] T047 Log-scan check: no insured PII or decoded VSD content in run logs (FR-028, SC-009)
- [x] T048 [P] Update `specs/011-read-vsd/diagrams/*` if the implementation diverged; confirm they render without errors (Principle VI)
- [ ] T049 Afo traceability audit: every Afo commit references its Afo ID + Jira ticket; every referenced Afo has a named test (Principle V)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately. T001 unblocks the generated types the endpoint compiles against.
- **Foundational (Phase 2)**: depends on Setup — BLOCKS all user stories.
- **User Story 1 (Phase 3, P1)**: depends on Foundational. Delivers the MVP — does **not** depend on C2C (PD/VD/StatusVD are AlwaysRead).
- **User Story 2 (Phase 4, P2)**: depends on Foundational; builds on US1's `ReadVsdService`. The heaviest/riskiest work (C2C + Secure Messaging).
- **User Story 3 (Phase 5, P2)**: depends on Foundational + the endpoint skeleton (T013); independent of US1/US2 logic. Can be pulled forward right after the MVP (cheap, no card needed).
- **User Story 4 (Phase 6, P3)**: depends on Foundational + `VsdmFaultFactory`; fault paths interleave with US1/US2 but are independently testable.
- **Polish (Phase 7)**: after the desired stories are complete.

### Within Each User Story

- Tests are written first and MUST fail before the implementation task.
- Value objects/constants before services; services before the endpoint mapping; core read before C2C-gated read.

### Parallel Opportunities

- Setup: T002, T003 in parallel.
- Foundational: T005/T006/T007/T010/T011/T014 in parallel (distinct files); T008 before T009.
- US1: T015/T016/T017 (tests) in parallel; T018 in parallel with them.
- US2: T024/T025 (and impls T029/T030) in parallel; T031 after both.
- US4: T038/T039/T040 in parallel.
- Across stories: once Foundational is done, US1, US3 and the US4 fault tests can proceed in parallel; US2 is the long pole.

---

## Parallel Example: User Story 1

```bash
# Tests first (must fail), in parallel:
Task: "Unit test StatusVdConverter in apdu-lib/src/test/.../vsdm/StatusVdConverterTest.java"
Task: "Unit test ReadVsdService mandatory read in apdu-lib/src/test/.../vsdm/ReadVsdServiceMandatoryTest.java"
Task: "@QuarkusTest KonnektorVSDService read in konnektor-soap-server/src/test/.../soap/KonnektorVSDServiceReadTest.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup → Phase 2 Foundational → Phase 3 US1.
2. **STOP and VALIDATE**: a primary system reads PD/VD/VSD_Status from a valid eGK end-to-end (the AlwaysRead path, no C2C). Demo the MVP.

### Incremental Delivery

1. MVP (US1) → demo.
2. Add US3 (local-only enforcement — cheap guard) → demo.
3. Add US2 (C2C + Secure Messaging + GVD + audit — the long pole) → demo.
4. Add US4 (full fault contract + timeout + concurrency) → demo.
5. Polish.

### Risk Note

US2's real TUC_KON_005 C2C (CVC parsing + Secure-Messaging wrap/unwrap of EF.GVD) is
the highest-risk work and the most likely to need hardware-in-the-loop validation.
Because US1's mandatory payload is AlwaysRead, the MVP lands and delivers value before
this risk is retired.

---

## Notes

- [P] = different files, no dependency on an incomplete task.
- Each Afo is implemented in its own commit (`[JIRA] VSDM-A_xxxx <summary>`) with a matching `test_VSDM_A_xxxx_*` (Principle V).
- Decoded VSD / insured PII is never logged or persisted (FR-028) — applies to every task that touches container bytes.
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.
