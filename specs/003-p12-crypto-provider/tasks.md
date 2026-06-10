# Tasks: P12 CryptoProvider Implementation

**Branch**: `007-p12-crypto-provider` | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

**User Stories**:
- **US1** (P1): Sign data using an SMC-B authentication key
- **US2** (P2): Verify a signature using a loaded keystore
- **US3** (Deferred): ECIES encrypt/decrypt — own feature, gemSpec_Krypt §4.7
- **US4** (P1): Inspect keystore availability
- **US5** (P2): Upload a new P12 certificate via JMX MBean

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: User story this task belongs to
- Exact file paths are required in every task description

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create all new files and test scaffolding needed before any implementation begins.

- [x] T001 Create `crypto-p12-lib/src/test/resources/application.properties` with `quarkus.crypto.p12.certs-dir=src/test/resources/certs` (points to the existing Zeta SMC-B test keystores)
- [x] T002 [P] Create `crypto-p12-lib/src/main/java/de/servicehealtherx/crypto/p12/P12CertManagementMBean.java` — JMX MBean interface with `void uploadCertificate(String alias, byte[] p12Data, String password)` and `String listCertificates()`
- [x] T003 [P] Create `crypto-p12-lib/src/main/java/de/servicehealtherx/crypto/p12/P12CryptoConfig.java` — `@ConfigMapping(prefix = "quarkus.crypto.p12")` with single method `String certsDir()`

**Checkpoint**: All new files exist; project compiles.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Cross-cutting changes to `crypto-lib` that enable the scanner and routing logic. MUST complete before any user story can be implemented.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T004 Extend `KeyAlias` pattern in `crypto-lib/src/main/java/de/servicehealtherx/crypto/KeyAlias.java` — change regex from `^(p12|pkcs11|pcsc|sicct)/[a-z0-9\-_]+$` to `^(p12|pkcs11|pcsc|sicct)(/[a-z0-9][a-z0-9\-_.]*)+$` to allow multi-segment paths (e.g. `p12/zeta/smcb-aut`)
- [x] T004 [P] Add `resolveAlgorithm(PrivateKey key, String requested)` helper to `crypto-lib/src/main/java/de/servicehealtherx/crypto/adapter/P12KeyStoreAdapter.java` — returns `requested` if non-blank; for EC key returns `"SHA256withECDSA"`; for RSA key returns `"SHA256withRSA/PSS"` (apply `PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)` to the `Signature` instance); otherwise throws `IllegalStateException`. Call it from `sign()` and `verify()` instead of using `request.algorithm` directly. `CryptoOperationResult.algorithm` must echo the resolved (never null) algorithm.
- [x] T004 Create `crypto-p12-lib/src/main/java/de/servicehealtherx/crypto/p12/P12CertScanner.java` — `@ApplicationScoped` bean that: (1) **bootstrap copy**: enumerates classpath resources under `certsDir`, copies each `.p12`/`.pfx` + sibling `password.txt` to the filesystem certs dir (skip-if-present, using `Thread.currentThread().getContextClassLoader().getResources()`); (2) **filesystem scan**: walks `certsDir` with `Files.walk()`, for each `.p12`/`.pfx` reads sibling `password.txt` (marks ERROR if absent), normalizes alias (`lowercase` → replace non-`[a-z0-9\-_./]` with `-` → collapse runs → trim per segment → prefix `p12/`), constructs and calls `engineLoad()` on each `P12KeyStoreAdapter`, catches and records errors without rethrowing; returns `List<P12KeyStoreAdapter>` including error-state adapters
- [x] T004 Create `crypto-p12-lib/src/test/java/de/servicehealtherx/crypto/p12/P12CertScannerTest.java` — unit tests (no CDI): `scan_discovers_three_zeta_p12_files`, `alias_derived_correctly_from_nested_path`, `missing_password_txt_marks_adapter_as_error`, `corrupt_p12_marks_adapter_as_error_others_available`, `empty_certs_dir_returns_empty_list`

**Checkpoint**: Foundation ready — `KeyAlias` accepts nested paths, adapter resolves algorithms, scanner builds adapter list. User story implementation can now begin.

---

## Phase 3: User Story 1 — Sign (Priority: P1) 🎯 MVP

**Goal**: A calling service can sign data bytes using a loaded P12 keystore entry identified by alias, receiving the signature and leaf certificate in return.

**Independent Test**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#sign_*`

- [x] T004 [US1] Implement `crypto-p12-lib/src/main/java/de/servicehealtherx/crypto/p12/P12CryptoProvider.java` — inject `P12CryptoConfig` and `P12CertScanner`; add `CopyOnWriteArrayList<P12KeyStoreAdapter>` + `ConcurrentHashMap<String, P12KeyStoreAdapter>` + `ReentrantLock uploadLock` fields; `@PostConstruct init()` calls `scanner.scan(config.certsDir())` and populates both structures; `sign()` validates `SourceType.P12`, looks up adapter by alias, delegates to `adapter.sign(request)`, throws `IllegalArgumentException` on missing/wrong-type alias; `encrypt()` and `decrypt()` throw `UnsupportedOperationException` referencing ECIES feature (gemSpec_Krypt §4.7)
- [x] T004 [US1] Create `crypto-p12-lib/src/test/java/de/servicehealtherx/crypto/p12/P12CryptoProviderTest.java` — `@QuarkusTest`; tests: `sign_with_aut_key_auto_detects_ecdsa` (null algorithm, result.algorithm == "SHA256withECDSA", signature verifies against returned cert), `sign_with_explicit_algorithm_uses_it` (explicit "SHA256withECDSA", result echoes it), `sign_unknown_alias_throws_illegal_argument`, `sign_pcsc_alias_throws_illegal_argument` (wrong SourceType), `sign_error_state_adapter_throws_illegal_state`, `encrypt_throws_unsupported_with_ecies_message`, `decrypt_throws_unsupported_with_ecies_message`, `load_failure_password_not_in_log` (capture log output, assert password string absent)
- [x] T010 [US1] Add package-info or static Bouncy Castle provider registration to ensure `BouncyCastleProvider` is registered before any test in `crypto-p12-lib/src/test/java/de/servicehealtherx/crypto/p12/` — required for `SHA256withRSA/PSS` to resolve via JCA

**Checkpoint**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#sign*,P12CryptoProviderTest#encrypt*,P12CryptoProviderTest#decrypt*,P12CryptoProviderTest#load*` — all green. Sign is fully functional.

---

## Phase 4: User Story 4 — Inspect Keystore Availability (Priority: P1)

**Goal**: Health checks and management components can query which P12 keystores are loaded and their current availability without triggering a crypto operation.

**Independent Test**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#listKeyStores*,P12CryptoProviderTest#getAvailability*,P12CryptoProviderTest#getAvailabilities*`

- [x] T010 [US4] Implement `listKeyStores()`, `getAvailability(KeyAlias)`, and `getAvailabilities()` in `crypto-p12-lib/src/main/java/de/servicehealtherx/crypto/p12/P12CryptoProvider.java` — `listKeyStores()` returns `Collections.unmodifiableList(adapters)` projected to `KeyStoreDescriptor`; `getAvailability()` looks up in `adapterMap`, returns `UNAVAILABLE` if absent; `getAvailabilities()` streams `adapterMap` to an unmodifiable `Map<String, KeyStoreAvailability>`
- [x] T010 [US4] Add tests to `crypto-p12-lib/src/test/java/de/servicehealtherx/crypto/p12/P12CryptoProviderTest.java` — `listKeyStores_returns_three_descriptors_all_available`, `getAvailability_available_alias_returns_available`, `getAvailability_error_adapter_returns_error`, `getAvailability_unknown_alias_returns_unavailable`, `getAvailabilities_returns_map_with_all_p12_aliases`

**Checkpoint**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest` — all green. All P1 user stories (US1 + US4) complete.

---

## Phase 5: User Story 2 — Verify (Priority: P2)

**Goal**: A calling service can verify a signature against a loaded P12 keystore entry.

**Independent Test**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#verify*`

- [x] T010 [US2] Implement `verify()` in `crypto-p12-lib/src/main/java/de/servicehealtherx/crypto/p12/P12CryptoProvider.java` — same alias validation as `sign()`; delegates to `adapter.verify(request, signature)`
- [x] T010 [US2] Add tests to `crypto-p12-lib/src/test/java/de/servicehealtherx/crypto/p12/P12CryptoProviderTest.java` — `verify_valid_signature_returns_true` (sign then verify, same alias + algorithm), `verify_tampered_signature_returns_false` (mutate one byte, expect false — no exception), `verify_unknown_alias_throws_illegal_argument`

**Checkpoint**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest` — all green.

---

## Phase 6: User Story 5 — MBean Upload (Priority: P2)

**Goal**: An operator can upload a new P12 certificate at runtime via JMX without restarting the application.

**Independent Test**: `mvn -pl crypto-p12-lib test -Dtest=P12CertManagementTest`

- [x] T010 [US5] Add `registerAdapter(P12KeyStoreAdapter adapter)` package-private method to `crypto-p12-lib/src/main/java/de/servicehealtherx/crypto/p12/P12CryptoProvider.java` — acquires `uploadLock`, adds to `adapters` list (replace if alias already exists) and `adapterMap`, releases lock; used by `P12CertManagement.uploadCertificate`
- [x] T010 [US5] Create `crypto-p12-lib/src/main/java/de/servicehealtherx/crypto/p12/P12CertManagement.java` — `@ApplicationScoped` implementing `P12CertManagementMBean`; `@PostConstruct` registers MBean at `de.servicehealtherx:module=crypto-p12-lib,name=P12CertManagement`; `@PreDestroy` deregisters; `uploadCertificate(alias, p12Data, password)`: (1) validate `KeyAlias` format and P12 source type, (2) open `p12Data` as PKCS12 with `password` — throw `IllegalArgumentException` if fails (no file written), (3) compute target paths: strip `p12/` prefix → `<certs-dir>/<relativePath>.p12` and `<certs-dir>/<relativeParent>/password.txt`, (4) create parent dirs, write P12 atomically via temp-file + `Files.move(ATOMIC_MOVE)`, write password file, (5) construct + `engineLoad()` new `P12KeyStoreAdapter`, (6) call `provider.registerAdapter(adapter)`; `listCertificates()` returns JSON array of alias strings from `provider.listKeyStores()`; password MUST NOT appear in any log or exception message
- [x] T010 [US5] Create `crypto-p12-lib/src/test/java/de/servicehealtherx/crypto/p12/P12CertManagementTest.java` — `@QuarkusTest`; tests: `upload_valid_p12_registers_alias_as_available` (upload Zeta AUT p12 bytes under a new alias, assert listKeyStores count +1, AVAILABLE, sign succeeds), `upload_invalid_p12_data_throws_no_file_written` (bad bytes → IAE, no file on disk, count unchanged), `upload_replaces_existing_alias_and_reloads` (upload same alias twice, count unchanged, new adapter is AVAILABLE), `list_certificates_returns_json_array_of_alias_strings`
- [x] T010 [US5] Verify MBean is reachable: add a test in `P12CertManagementTest.java` that retrieves the MBean via `ManagementFactory.getPlatformMBeanServer().getObjectInstance(objectName)` and asserts it is registered during `@PostConstruct` — `mbean_is_registered_on_startup`

**Checkpoint**: `mvn -pl crypto-p12-lib test -Dtest=P12CertManagementTest` — all green. Runtime cert upload fully functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Integration validation and cleanup across all stories.

- [x] T010 Run `mvn verify` from the repository root — all modules must compile and pass with zero test failures; confirms `KeyAlias` pattern extension did not break existing `crypto-lib` usages
- [x] T020 [P] Update `crypto-p12-lib/pom.xml` if `quarkus-smallrye-config` is not already transitively available — verify `@ConfigMapping` resolution works; add explicit dependency only if needed after T019 fails
- [x] T021 [P] Update `specs/003-p12-crypto-provider/diagrams/sequence-sign.puml` to reflect the `resolveAlgorithm()` decision step in the sign flow (call to helper, EC/RSA branch, PSSParameterSpec path)

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1: Setup         → no dependencies, start immediately
Phase 2: Foundational  → depends on Phase 1 completion
                         BLOCKS all user story phases
Phase 3: US1 Sign (P1) → depends on Phase 2 completion
Phase 4: US4 Avail (P1)→ depends on Phase 2; US4 can start in parallel with US1
                         (different methods, but same class — coordinate edits)
Phase 5: US2 Verify(P2)→ depends on Phase 2; can start after Phase 3 checkpoint
Phase 6: US5 MBean (P2)→ depends on Phase 3 (needs P12CryptoProvider.registerAdapter)
Phase 7: Polish         → depends on Phases 3-6 completion
```

### User Story Dependencies

| Story | Priority | Depends On | Independent? |
|-------|----------|-----------|--------------|
| US1: Sign | P1 | Phase 2 | Yes — testable with Zeta AUT keystore alone |
| US4: Availability | P1 | Phase 2 | Yes — testable via listKeyStores() immediately after startup |
| US2: Verify | P2 | Phase 2 + US1 (sign to produce test input) | Yes — sign then verify in one test |
| US5: MBean Upload | P2 | Phase 2 + US1 (needs registerAdapter) | Yes — upload then sign with new alias |

### Within Each User Story

- Implementation task first, then test tasks (or both simultaneously — no strict TDD requirement in spec)
- Phase 3 T008 (P12CryptoProvider.sign) MUST be complete before T009 tests can be run
- Phase 4 T011 (availability methods) can be implemented at the same time as T008 — they are different methods in the same class

### Parallel Opportunities

- T002 and T003 (Phase 1): fully parallel — different files
- T005 and T006 (Phase 2): partially parallel — T005 modifies `crypto-lib`, T006 is in `crypto-p12-lib`
- T005 MUST complete before T006 (scanner calls `resolveAlgorithm` indirectly via adapter load)
- T004 MUST complete before T006 (scanner constructs `KeyAlias`)
- T008 (sign) and T011 (availability) can be implemented in parallel (same file, coordinate merges)
- T013 (verify) and T015 (registerAdapter for MBean) can proceed in parallel after Phase 3

---

## Parallel Example: Phase 2 Foundational

```
Parallel track A: T004 Extend KeyAlias pattern (crypto-lib)
Parallel track B: T005 Add resolveAlgorithm to P12KeyStoreAdapter (crypto-lib)

Sequential after A and B complete:
  T006 Implement P12CertScanner (depends on KeyAlias + P12KeyStoreAdapter)
  T007 Write P12CertScannerTest
```

## Parallel Example: Phase 3 + Phase 4 (both P1)

```
Both depend on Phase 2 completion. Single developer works them sequentially (same class).
Two developers could split: one implements T008 (sign), other implements T011 (availability methods).
Merge after each task to avoid conflicts in P12CryptoProvider.java.
```

---

## Implementation Strategy

### MVP First (US1 + US4 — both P1)

1. Complete Phase 1: Setup (fast — new empty files)
2. Complete Phase 2: Foundational (blocks everything; ~4 tasks)
3. Complete Phase 3: US1 Sign + Phase 4: US4 Availability (both P1; same class)
4. **STOP and VALIDATE**: `mvn -pl crypto-p12-lib test` — all tests green
5. Increment is production-ready: sign + verify availability work with real SMC-B keystores

### Incremental Delivery

1. Setup + Foundational → scanner + algorithm detection ready
2. US1 Sign → test independently → commit
3. US4 Availability → test independently → commit (MVP complete)
4. US2 Verify → test independently → commit
5. US5 MBean Upload → test independently → commit (full feature complete)
6. `mvn verify` from root — all modules green

### Single Developer Order

T001 → T002 → T003 → T004 → T005 → T006 → T007 → T008 → T009 → T010 → T011 → T012 → T013 → T014 → T015 → T016 → T017 → T018 → T019 → T020 → T021

---

## Notes

- [P] tasks operate on different files and can be assigned to different developers or run concurrently
- `crypto-lib` changes (T004, T005) affect the shared library — run `mvn -pl crypto-lib test` after each to confirm no regressions
- Password strings MUST NOT appear in any log output — validate by capturing log in tests (T009 `load_failure_password_not_in_log`)
- `P12CryptoProvider.java` is edited by multiple phases (T008, T011, T013, T015) — commit after each phase to reduce merge conflict risk
- The Zeta SMC-B test keystores use EC keys — all ECDSA/SHA-256 paths are covered by them; RSA/PSS path is tested via algorithm override with an explicit `"SHA256withRSA/PSS"` request (adapter handles PSSParameterSpec internally)
- Bouncy Castle provider registration (T010) must happen before any test that uses `SHA256withRSA/PSS` or `SHA256withECDSA` via the BC provider
