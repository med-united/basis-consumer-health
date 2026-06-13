# Tasks: apdu-lib — TUC Card Management

**Branch**: `007-apdu-lib-tuc-card-mgmt`  
**Input**: Design documents from `specs/007-apdu-lib-tuc-card-mgmt/`  
**Spec**: gemSpec_Kon V5.27.0, Section 4.1.5.4 — 27 Technical Use Cases  
**Constitution**: v1.7.0 — Test-First by default (Principle II); one commit per Afo (Principle V)

## Architecture Summary

`apdu-lib` is a **pure APDU generation library** (contracts/public-api.md §Contract Principles):

- TUC classes **generate** `List<GeneratedApduStep>` (what to send); they do **not** execute transport
- Each `GeneratedApduStep` wraps a `javax.smartcardio.CommandAPDU` + `ExpectedStatusSet` + semantic label
- Execution layers (`sicct-lib`, `crypto-pcsc-lib`) consume `TucGenerationResult` and drive the transport
- Precondition errors (reserved card, blocked PIN, KVK write) are thrown as `TucException` during generation
- `apdu-lib` depends only on `crypto-lib` — **no `sicct-lib` dependency**

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story this task belongs to (US1–US4)
- File paths relative to repository root

## Path Conventions

```
apdu-lib/src/main/java/de/servicehealtherx/apdu/model/    ← domain model & result types
apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/      ← 27 TUC generation classes
apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/      ← TUC test classes
```

---

## Phase 1: Setup (Maven Module Scaffold)

**Purpose**: Create the `apdu-lib` Maven module and wire it into the reactor build.

- [ ] T001 Create `apdu-lib/` directory and Maven source tree: `src/main/java/de/servicehealtherx/apdu/model/`, `src/main/java/de/servicehealtherx/apdu/tuc/`, `src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T002 Create `apdu-lib/pom.xml` with parent `de.servicehealtherx:basis-consumer-parent`, `<artifactId>apdu-lib</artifactId>`, and single dependency `de.servicehealtherx:crypto-lib` — **no `sicct-lib` dependency** (contracts/public-api.md §4)
- [ ] T003 Add `<module>apdu-lib</module>` to root `pom.xml` after `<module>sicct-lib</module>` so reactor builds in correct dependency order
- [ ] T004 Configure JaCoCo maven plugin in `apdu-lib/pom.xml` with `jacoco-check` execution enforcing ≥80% LINE COVEREDRATIO (SC-002)

**Checkpoint**: `mvn -pl apdu-lib clean install -DskipTests` completes with `BUILD SUCCESS`

---

## Phase 2: Foundational (Model Types + Result Types)

**Purpose**: All domain model types and the generation result types that form the API contract. Must be complete before ANY TUC class is implemented.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [ ] T005 [P] Create `CardType.java` enum (`EGK`, `HBA`, `HBAX`, `SMC_B`, `KVK`, `UNKNOWN`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T006 [P] Create `CardVersion.java` enum (`GENERATION_1`, `GENERATION_1_PLUS`, `GENERATION_2_0`, `GENERATION_2_1`, `UNKNOWN`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T007 [P] Create `AuthMode.java` enum (`ONE_SIDED`, `MUTUAL`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T008 [P] Create `AlgorithmId.java` enum (`RSA_PKCS1_V1_5`, `ECDSA_SHA256`, `RSA_OAEP_SHA256`, `ECDH_AES256`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T009 [P] Create `PinRef.java` enum (`PIN_CH/0x01`, `PIN_QES/0x02`, `PIN_AMTS_REP/0x03`, `MRPIN_AMTS/0x06`, `PIN_OSD/0x05`) with `cosName` and `reference` fields in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T010 [P] Create `PinStatus.java` enum (`VERIFIED`, `OK`, `REJECTED`, `BLOCKED`, `TRANSPORT_PIN`, `EMPTY_PIN`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T011 [P] Create `PinResult.java` enum (`OK`, `VERIFIED`, `REJECTED`, `BLOCKED`, `ERROR`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T012 [P] Create `PukResult.java` enum (`OK`, `REJECTED`, `BLOCKED`, `ERROR`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T013 [P] Create `KeyRef.java` enum (`C_AUT/0x04`, `C_ENC/0x02`, `C_QES/0x06`) with `reference` and `cosName` fields in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T014 [P] Create `TucException.java` extending `RuntimeException` with `errorCode` (4001–4094), `tucIdentifier`, and factory methods (`pinBlocked`, `cardReservedByOther`, `cardAccessTimeout`, `invalidPinRef`, `remoteKtNotConfigured`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T015 Create `AuthState.java` with `Map<PinRef,PinStatus> verifiedPins`, `Set<KeyRef> authedKeys`, and methods `isPinVerified`, `isKeyAuthenticated`, `markPinVerified`, `markKeyAuthenticated`, `clear` in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T016 Create `CardSession.java` record (`cardHandle`, `cardType`, `cardVersion`, `authState`, `sessionId`, `lockOwner`) with `Objects.requireNonNull` validation in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/` — `cardHandle` is a plain `String` (no sicct-lib type reference)
- [ ] T017 [P] Create `ExpectedStatusSet.java` record (`Set<Integer> acceptedSw`, `boolean allowWarnings`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T018 [P] Create `GeneratedApduStep.java` record (`javax.smartcardio.CommandAPDU command`, `ExpectedStatusSet expectedStatuses`, `String semanticLabel`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T019 Create `TucGenerationResult.java` record (`List<GeneratedApduStep> steps`, `Map<String,Object> semanticOutputHints`) — `semanticOutputHints` carries keys like `"pinRef"`, `"onSuccessStatus"` for execution layer interpretation — in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T020 [P] Create `GematikISO7816.java` non-instantiable constants class with gematik-specific INS bytes (`INS_RESET_RETRY_COUNTER=0x2C`, `INS_DISABLE_VERIFICATION_REQUIREMENT=0x26`, `INS_ENABLE_VERIFICATION_REQUIREMENT=0x28`, `INS_APPEND_RECORD=0xE2`, `INS_SEARCH_RECORD=0xA2`, `INS_ERASE_BINARY=0x0E`, `INS_ERASE_RECORD=0x0C`, `INS_PERFORM_SECURITY_OPERATION=0x2A`, `INS_MANAGE_SECURITY_ENV=0x22`) and SW constants (`SW_OBJECT_NOT_FOUND=0x6A82`, `SW_CONDITION_NOT_SATISFIED=0x6985`, `SW_AUTH_METHOD_BLOCKED=0x6983`) in `apdu-lib/src/main/java/de/servicehealtherx/apdu/model/`
- [ ] T021 [P] Write unit tests for `GeneratedApduStep` and `TucGenerationResult` (field access, `semanticOutputHints` map operations) in `apdu-lib/src/test/java/de/servicehealtherx/apdu/model/GeneratedApduStepTest.java`
- [ ] T022 [P] Write unit tests for `TucException` (all factory methods, error code range 4001–4094 validation) in `apdu-lib/src/test/java/de/servicehealtherx/apdu/model/TucExceptionTest.java`
- [ ] T023 [P] Write unit tests for `AuthState` (`markPinVerified`, `isPinVerified`, `markKeyAuthenticated`, `clear`) in `apdu-lib/src/test/java/de/servicehealtherx/apdu/model/AuthStateTest.java`

**Checkpoint**: `mvn -pl apdu-lib test` passes; all model type tests green; `TucGenerationResult` and `GeneratedApduStep` compile; `javax.smartcardio.CommandAPDU` resolves from JDK

---

## Phase 3: User Story 1 — PIN Management TUCs (Priority: P1) 🎯 MVP

**Goal**: A Fachmodul receives correct `TucGenerationResult` APDU sequences for all PIN management operations. Execution layers transmit those steps and interpret responses.

**Independent Test**: `mvn -pl apdu-lib test -Dtest="TucKon026*,TucKon023*,TucKon022*,TucKon012*,TucKon019*,TucKon021*,TucKon027*"` — all tests green using pure in-process APDU construction (no transport required).

> **WRITE TESTS FIRST — ensure they FAIL before implementing**

### Tests for User Story 1 (Test-First, Principle II)

- [ ] T024 [P] [US1] Write `TucKon026ProvideCardSessionTest.java` covering: `test_TIP1_A_4567_provide_card_session_result_has_select_apdu_step`, `test_TIP1_A_4567_provide_card_session_session_id_in_hints` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T025 [P] [US1] Write `TucKon023ReserveCardTest.java` covering: `test_TIP1_A_4571_03_generate_lock_returns_empty_steps_on_unlocked_card`, `test_TIP1_A_4571_03_generate_lock_throws_4093_when_already_locked`, `test_TIP1_A_4571_03_generate_unlock_clears_lock_owner` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T026 [P] [US1] Write `TucKon022ProvidePinStatusTest.java` covering: `test_TIP1_A_4570_generate_pin_status_query_has_get_data_apdu`, `test_TIP1_A_4570_result_semantic_label_contains_pin_ref` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T027 [P] [US1] Write `TucKon012VerifyPinTest.java` covering: `test_TIP1_A_4566_generate_verify_pin_returns_verify_apdu_step`, `test_TIP1_A_4566_verify_apdu_ins_is_0x20_and_p2_is_pin_ref_byte`, `test_TIP1_A_4566_generate_throws_4063_when_pin_is_blocked`, `test_TIP1_A_4566_generate_throws_4093_when_card_is_reserved`, `test_TIP1_A_4566_hints_contain_pin_ref_for_auth_state_update` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T028 [P] [US1] Write `TucKon019ChangePinTest.java` covering: `test_TIP1_A_4568_generate_change_pin_returns_change_reference_data_apdu`, `test_TIP1_A_4568_change_reference_data_ins_is_0x24` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T029 [P] [US1] Write `TucKon021UnblockPinTest.java` covering: `test_TIP1_A_4569_02_generate_unblock_pin_returns_reset_retry_counter_apdu`, `test_TIP1_A_4569_02_reset_retry_counter_ins_is_0x2C` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T030 [P] [US1] Write `TucKon027EnableDisablePinTest.java` covering: `test_TIP1_A_5486_generate_enable_returns_enable_verification_apdu_0x28`, `test_TIP1_A_5486_generate_disable_returns_disable_verification_apdu_0x26` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`

### Implementation for User Story 1

- [ ] T031 [US1] Implement `TucKon026ProvideCardSession.java`: generate SELECT MF APDU step; put `sessionId` (new UUID) in `semanticOutputHints`; return `TucGenerationResult` — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4567)
- [ ] T032 [US1] Implement `TucKon023ReserveCard.java`: precondition check `lockOwner == null || lockOwner == caller` (throw `TucException(4093)` on conflict); update `cardSession.lockOwner`; return empty steps (no APDU needed) — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4571-03)
- [ ] T033 [US1] Implement `TucKon022ProvidePinStatus.java`: generate GET DATA APDU step for PIN status file; add `pinRef` to `semanticOutputHints` so execution layer knows which PIN to interpret; return `TucGenerationResult` — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4570)
- [ ] T034 [US1] Implement `TucKon012VerifyPin.java`: check `cardSession.lockOwner`, check `PinStatus` in `authState` is not BLOCKED/TRANSPORT_PIN (throw `TucException(4063/4065)` if so), build `CommandAPDU(0x00, 0x20, 0x00, pinRef.reference)` step with `ExpectedStatusSet({0x9000}, allowWarnings=true)`, put `("pinRef", pinRef)` and `("onSuccess", PinStatus.VERIFIED)` in hints — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4566)
- [ ] T035 [US1] Implement `TucKon019ChangePin.java`: build CHANGE REFERENCE DATA `CommandAPDU(0x00, 0x24, 0x00, pinRef.reference)` step — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4568)
- [ ] T036 [US1] Implement `TucKon021UnblockPin.java`: build RESET RETRY COUNTER `CommandAPDU(0x00, 0x2C, 0x03, pinRef.reference)` step — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4569-02)
- [ ] T037 [US1] Implement `TucKon027EnableDisablePin.java`: build ENABLE VERIFICATION REQUIREMENT `CommandAPDU(0x00, 0x28, ...)` or DISABLE `CommandAPDU(0x00, 0x26, ...)` based on `enable` flag — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_5486)

**Checkpoint**: All US1 tests pass; each test asserts `result.steps().get(0).command().getINS()` equals the expected INS byte; precondition errors thrown before any step is added to the list

---

## Phase 4: User Story 2 — Card File and Record I/O TUCs (Priority: P2)

**Goal**: A Fachmodul receives correct `TucGenerationResult` APDU sequences for all file/record operations. KVK write operations throw `TucException` during generation (before any APDU is produced).

**Independent Test**: `mvn -pl apdu-lib test -Dtest="TucKon200*,TucKon202*,TucKon203*,TucKon204*,TucKon209*,TucKon210*,TucKon211*,TucKon214*,TucKon215*"` — all tests green.

> **WRITE TESTS FIRST — ensure they FAIL before implementing**

### Tests for User Story 2 (Test-First, Principle II)

- [ ] T038 [P] [US2] Write `TucKon200SendApduTest.java` covering: `test_TIP1_A_4583_02_generate_wraps_raw_bytes_as_command_apdu_step` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T039 [P] [US2] Write `TucKon202ReadFileTest.java` covering: `test_TIP1_A_4573_generate_read_file_returns_two_steps_select_then_read_binary`, `test_TIP1_A_4573_first_step_is_select_ins_0xA4`, `test_TIP1_A_4573_second_step_is_read_binary_ins_0xB0_with_offset_in_p1_p2` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T040 [P] [US2] Write `TucKon203WriteFileTest.java` covering: `test_TIP1_A_4574_generate_write_file_returns_update_binary_step`, `test_TIP1_A_4574_generate_throws_for_kvk_card` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T041 [P] [US2] Write `TucKon204ClearFileTest.java` covering: `test_TIP1_A_4576_1_generate_clear_file_returns_erase_binary_step_ins_0x0E`, `test_TIP1_A_4576_1_generate_throws_for_kvk_card` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T042 [P] [US2] Write `TucKon209ReadRecordTest.java` covering: `test_TIP1_A_4575_generate_read_record_returns_read_record_step_ins_0xB2`, `test_TIP1_A_4575_p1_is_record_number` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T043 [P] [US2] Write `TucKon210WriteRecordTest.java` covering: `test_TIP1_A_4576_2_generate_write_record_returns_update_record_step`, `test_TIP1_A_4576_2_generate_throws_for_kvk_card` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T044 [P] [US2] Write `TucKon211ClearRecordTest.java` covering: `test_TIP1_A_4577_1_generate_clear_record_returns_erase_record_step_ins_0x0C`, `test_TIP1_A_4577_1_generate_throws_for_kvk_card` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T045 [P] [US2] Write `TucKon214AppendRecordTest.java` covering: `test_TIP1_A_4577_2_generate_append_record_returns_append_record_step_ins_0xE2`, `test_TIP1_A_4577_2_generate_throws_for_kvk_card` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T046 [P] [US2] Write `TucKon215SearchRecordTest.java` covering: `test_TIP1_A_4578_generate_search_record_returns_search_record_step_ins_0xA2`, `test_TIP1_A_4578_search_pattern_in_command_data` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`

### Implementation for User Story 2

- [ ] T047 [US2] Implement `TucKon200SendApdu.java`: wrap raw `commandApduBytes` in `javax.smartcardio.CommandAPDU`, return single-step `TucGenerationResult` — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4583-02)
- [ ] T048 [US2] Implement `TucKon202ReadFile.java`: step 1 = SELECT `CommandAPDU(0x00, 0xA4, 0x02, 0x04, fileIdBytes)`; step 2 = READ BINARY `CommandAPDU(0x00, 0xB0, offsetHigh, offsetLow, length)` — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4573)
- [ ] T049 [US2] Implement `TucKon203WriteFile.java`: KVK precondition check (throw `TucException` if `cardType == KVK`); UPDATE BINARY `CommandAPDU(0x00, 0xD6, offsetHigh, offsetLow, content)` step — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4574)
- [ ] T050 [US2] Implement `TucKon204ClearFile.java`: KVK check; ERASE BINARY `CommandAPDU(0x00, 0x0E, 0x00, 0x00)` step — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4576-1)
- [ ] T051 [US2] Implement `TucKon209ReadRecord.java`: READ RECORD `CommandAPDU(0x00, 0xB2, recordNumber, (fileRef << 3) | 0x04, le)` step — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4575)
- [ ] T052 [US2] Implement `TucKon210WriteRecord.java`: KVK check; UPDATE RECORD `CommandAPDU(0x00, 0xDC, recordNumber, (fileRef << 3) | 0x04, content)` step — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4576-2)
- [ ] T053 [US2] Implement `TucKon211ClearRecord.java`: KVK check; ERASE RECORD `CommandAPDU(0x00, 0x0C, recordNumber, (fileRef << 3) | 0x04)` step — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4577-1)
- [ ] T054 [US2] Implement `TucKon214AppendRecord.java`: KVK check; APPEND RECORD `CommandAPDU(0x00, 0xE2, 0x00, (fileRef << 3) | 0x00, content)` step — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4577-2)
- [ ] T055 [US2] Implement `TucKon215SearchRecord.java`: SEARCH RECORD `CommandAPDU(0x00, 0xA2, 0x00, (fileRef << 3) | 0x04, searchPattern)` step — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4578)

**Checkpoint**: All US2 tests pass; each file/record TUC test asserts correct INS byte; KVK rejection fires before any step is generated

---

## Phase 5: User Story 3 — Cryptographic and Session TUCs (Priority: P2)

**Goal**: A Fachmodul receives correct MSE SET → PSO:CDS / PSO:DECIPHER APDU sequences for signing/decryption, plus session management generation steps.

**Independent Test**: `mvn -pl apdu-lib test -Dtest="TucKon219*,TucKon220*,TucKon223*,TucKon208*,TucKon224*,TucKon024*"` — all tests green.

> **WRITE TESTS FIRST — ensure they FAIL before implementing**

### Tests for User Story 3 (Test-First, Principle II)

- [ ] T056 [P] [US3] Write `TucKon219SignTest.java` covering: `test_TIP1_A_4581_generate_sign_returns_three_steps_mse_key_mse_alg_pso_cds`, `test_TIP1_A_4581_pso_cds_ins_is_0x2A_p1_0x9E_p2_0x9A`, `test_TIP1_A_4581_throws_when_pin_qes_not_in_verified_auth_state` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T057 [P] [US3] Write `TucKon220DecryptTest.java` covering: `test_TIP1_A_4582_generate_decrypt_returns_mse_set_then_pso_decipher`, `test_TIP1_A_4582_pso_decipher_ins_is_0x2A_p1_0x80_p2_0x86`, `test_TIP1_A_4582_throws_for_kvk_card` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T058 [P] [US3] Write `TucKon223StartCardSessionTest.java` covering: `test_A_26067_generate_start_session_result_contains_session_uuid_in_hints`, `test_A_26067_generate_start_session_has_manage_security_env_step` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T059 [P] [US3] Write `TucKon208SendSecuredApduTest.java` covering: `test_A_26069_01_generate_secured_scenario_wraps_signed_scenario_bytes`, `test_A_26069_01_generate_throws_on_replayed_sequence_number`, `test_A_26069_01_next_sequence_number_in_hints` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T060 [P] [US3] Write `TucKon224StopCardSessionTest.java` covering: `test_A_26068_generate_stop_session_includes_card_reset_step`, `test_A_26068_generate_clears_lock_owner_in_hints` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T061 [P] [US3] Write `TucKon024ResetCardTest.java` covering: `test_TIP1_A_4584_02_generate_reset_returns_reset_apdu_step`, `test_TIP1_A_4584_02_hints_signal_auth_state_clear` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`

### Implementation for User Story 3

- [ ] T062 [US3] Implement `TucKon219Sign.java`: step 1 = MSE SET key `CommandAPDU(0x00, 0x22, 0x41, 0xB6, keyRefData)`; step 2 = MSE SET algorithm; step 3 = PSO:CDS `CommandAPDU(0x00, 0x2A, 0x9E, 0x9A, dataToBeSigned)` — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4581)
- [ ] T063 [US3] Implement `TucKon220Decrypt.java`: step 1 = MSE SET key; step 2 = PSO:DECIPHER `CommandAPDU(0x00, 0x2A, 0x80, 0x86, encryptedData)` — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4582)
- [ ] T064 [US3] Implement `TucKon223StartCardSession.java`: generate MSE SET step for session establishment; put new session `UUID` in `semanticOutputHints("sessionId")` — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo A_26067)
- [ ] T065 [US3] Implement `TucKon208SendSecuredApdu.java`: validate `expectedSequenceNumber` monotonicity (throw `TucException` on replay, FR-009); wrap `signedScenario` bytes in `CommandAPDU`; put `("nextSequenceNumber", expectedSequenceNumber + 1)` in hints — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo A_26069-01)
- [ ] T066 [US3] Implement `TucKon224StopCardSession.java`: delegate to `TucKon024` generation for reset step; put `("clearAuthState", true)`, `("releaseLock", true)` in hints — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo A_26068)
- [ ] T067 [US3] Implement `TucKon024ResetCard.java`: generate card RESET APDU step; put `("clearAuthState", true)`, `("releaseLock", true)` in `semanticOutputHints` — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4584-02)

**Checkpoint**: All US3 tests pass; PSO:CDS INS byte verified via `result.steps().get(2).command().getINS()`; sequence number replay check verified; `semanticOutputHints` assertions for `clearAuthState` pass

---

## Phase 6: User Story 4 — Card-to-Card Authentication and Utility TUCs (Priority: P3)

**Goal**: A Fachmodul receives correct APDU sequences for Card-to-Card authentication, eGK blocking status query, audit appending, certificate reading, and professional role derivation.

**Independent Test**: `mvn -pl apdu-lib test -Dtest="TucKon005*,TucKon018*,TucKon006*,TucKon216*,TucKon036*"` — all tests green.

> **WRITE TESTS FIRST — ensure they FAIL before implementing**

### Tests for User Story 4 (Test-First, Principle II)

- [ ] T068 [P] [US4] Write `TucKon005CardToCardAuthTest.java` covering: `test_TIP1_A_4572_generate_one_sided_auth_returns_get_challenge_internal_auth_external_auth_steps`, `test_TIP1_A_4572_generate_throws_when_source_card_foreign_locked`, `test_TIP1_A_4572_get_challenge_ins_is_0x84`, `test_TIP1_A_4572_internal_auth_ins_is_0x88`, `test_TIP1_A_4572_external_auth_ins_is_0x82` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T069 [P] [US4] Write `TucKon018CheckEgkBlockingTest.java` covering: `test_TIP1_A_4579_02_generate_check_blocking_returns_read_binary_step`, `test_TIP1_A_4579_02_hca_only_flag_in_hints` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T070 [P] [US4] Write `TucKon216ReadCertificateTest.java` covering: `test_TIP1_A_4585_generate_read_certificate_returns_select_then_read_binary_steps`, `test_TIP1_A_4585_first_step_select_ins_0xA4` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T071 [P] [US4] Write `TucKon006WriteAuditTest.java` covering: `test_TIP1_A_4580_generate_write_audit_delegates_to_append_record_steps`, `test_TIP1_A_4580_generate_throws_for_non_egk_card` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`
- [ ] T072 [P] [US4] Write `TucKon036ProvideProfessionalRoleTest.java` covering: `test_TIP1_A_5478_generate_returns_versicherter_hint_for_kvk`, `test_TIP1_A_5478_generate_for_smcb_returns_read_c_aut_steps`, `test_TIP1_A_5478_hints_contain_profession_oid_parse_flag` in `apdu-lib/src/test/java/de/servicehealtherx/apdu/tuc/`

### Implementation for User Story 4

- [ ] T073 [US4] Implement `TucKon005CardToCardAuth.java`: check neither card is foreign-locked (throw `TucException`); validate key ref combination (TAB_KON_673); generate steps: SELECT CV cert (source), SELECT CV cert (target), SELECT key ref (source), SELECT key ref (target), GET CHALLENGE (target, `INS=0x84`), INTERNAL AUTHENTICATE (source, `INS=0x88`), EXTERNAL AUTHENTICATE (target, `INS=0x82`); put `("markKeyAuthenticated", C_AUT)` in hints — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4572)
- [ ] T074 [US4] Implement `TucKon018CheckEgkBlocking.java`: generate READ BINARY step for blocking status EF; put `("checkHcaOnly", checkHcaOnly)` in hints so execution layer knows whether to validate AUT certificate via `crypto-lib` — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4579-02)
- [ ] T075 [US4] Implement `TucKon216ReadCertificate.java`: generate SELECT step + READ BINARY step for `fileIdentifier`; put `("certFileId", fileIdentifier)` in hints — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4585)
- [ ] T076 [US4] Implement `TucKon006WriteAudit.java`: precondition reject if `cardType != EGK` (throw `TucException`, FR-010); delegate to `TucKon214AppendRecord.generateAppendRecord(cardSession, EF_LOGGING_FILE_ID, auditEntry)` and return its steps — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_4580)
- [ ] T077 [US4] Implement `TucKon036ProvideProfessionalRole.java`: for KVK — return result with empty steps and `("professionalRole", List.of("Versicherter"))` in hints; for HBAx/SMC-B/eGK — delegate to `TucKon216ReadCertificate.generateReadCertificate(cardSession, C_AUT_FILE_ID)` and put `("parseProfessionOids", true)` in hints (execution layer parses DER and returns OIDs) — `apdu-lib/src/main/java/de/servicehealtherx/apdu/tuc/` (Afo TIP1-A_5478)

**Checkpoint**: All US4 tests pass; GET CHALLENGE + INTERNAL AUTH + EXTERNAL AUTH step sequence verified; TUC_KON_006 eGK-only precondition verified; KVK fixed-role hint verified

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Coverage enforcement, Afo traceability, reactor integration validation.

- [ ] T078 Run full module test suite and verify ≥80% line coverage: `mvn -pl apdu-lib verify` — must pass JaCoCo `jacoco-check`; open `apdu-lib/target/site/jacoco/index.html` and confirm no TUC class below threshold
- [ ] T079 [P] Verify Afo traceability: run `grep -rn "void test_TIP1_A_\|void test_A_26" apdu-lib/src/test/java/` — all 27 requirement IDs from `spec.md` must appear at least once (SC-007)
- [ ] T080 [P] Create `specs/007-apdu-lib-tuc-card-mgmt/Afo_Testmatrix.md` with table mapping all 27 TUC requirement IDs to their implementing test class + test method name (SC-007 pre-release gate)
- [ ] T081 Run full reactor build: `mvn clean install` must complete `BUILD SUCCESS` for all modules including `apdu-lib`; confirm no classpath conflicts via `mvn dependency:tree -pl apdu-lib`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundation)**: Depends on Phase 1 — **BLOCKS** all user story phases
- **Phase 3 (US1 PIN)**: Depends on Phase 2 — first MVP increment
- **Phase 4 (US2 File/Record)**: Depends on Phase 2 — parallel with Phase 3 if staffed
- **Phase 5 (US3 Crypto/Session)**: Depends on Phase 2 — parallel with Phases 3–4 if staffed
- **Phase 6 (US4 Auth/Utility)**: Depends on Phase 2; T076 needs `TucKon214AppendRecord` (Phase 4 T054 complete); T077 needs `TucKon216ReadCertificate` (Phase 6 T075, same phase)
- **Phase 7 (Polish)**: Depends on all desired user story phases complete

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 — no inter-story dependencies
- **US2 (P2)**: Can start after Phase 2 — independent of US1
- **US3 (P2)**: Can start after Phase 2 — independent of US1/US2
- **US4 (P3)**: Can start after Phase 2; T076 needs US2 T054 complete; T077 delegates to T075 within US4

### Afo Commit Rule (Principle V)

Each implementation task (T031–T037, T047–T055, T062–T067, T073–T077) MUST be committed in its own git commit:
```
[JIRA-TICKET] [AFO-ID] <imperative summary>
Example: BCP-1234 TIP1-A_4566 implement Verify PIN APDU generation using VERIFY INS 0x20
```

---

## Parallel Opportunities

### Phase 2 — All model/result types in parallel

```
T005 CardType      T006 CardVersion    T007 AuthMode       T008 AlgorithmId
T009 PinRef        T010 PinStatus      T011 PinResult      T012 PukResult
T013 KeyRef        T014 TucException   T017 ExpectedStatusSet T018 GeneratedApduStep
T020 GematikISO7816
```

### Phase 3 — All US1 tests in parallel, then implementations sequentially

```
Parallel: T024 T025 T026 T027 T028 T029 T030 (write all tests)
Then:     T031 → T032 → T033 → T034 → T035 → T036 → T037 (implement in Afo order)
```

### Cross-Story Parallel (multiple developers)

Once Phase 2 is complete:
- Dev A: US1 (Phase 3)
- Dev B: US2 (Phase 4)
- Dev C: US3 (Phase 5)

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup (T001–T004)
2. Phase 2: Foundation (T005–T023)
3. Phase 3: US1 PIN Management (T024–T037)
4. **VALIDATE**: `mvn -pl apdu-lib test -Dtest="TucKon012*,TucKon019*,TucKon021*,TucKon022*,TucKon023*,TucKon026*,TucKon027*"` — all green
5. Run `mvn clean install` — reactor integration confirmed

### Incremental Delivery

1. Foundation → Phase 3 US1 → MVP (basic PIN management generation)
2. + Phase 4 US2 → file/record I/O generation
3. + Phase 5 US3 → cryptographic and session generation
4. + Phase 6 US4 → full TI TUC coverage
5. Phase 7 → coverage enforced, Afo_Testmatrix complete

---

## Notes

- **[P]** tasks = different files, no inter-task dependency — safe to run in parallel
- **[US1/US2/US3/US4]** maps task to specific user story for Afo traceability
- `apdu-lib` is **generation only** — no transport calls, no `ResponseApdu` types
- Public APDU type: `javax.smartcardio.CommandAPDU` (contracts/public-api.md §2)
- No `Impl` suffix on any class (Principle I): `TucKon012VerifyPin`, not `TucKon012VerifyPinImpl`
- No direct Bouncy Castle or gemLibPki calls from `apdu-lib` — delegate to `crypto-lib` (research.md §2–3)
- `apdu-lib/pom.xml` depends on `crypto-lib` only — no `sicct-lib` compile dependency (research.md §5)
- Tests assert APDU correctness via `result.steps().get(N).command().getINS()` — no mock transport needed
