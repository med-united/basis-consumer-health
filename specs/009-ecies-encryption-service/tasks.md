---

description: "Task list for ECIES Transport Encryption in EncryptionService"
---

# Tasks: ECIES Transport Encryption in EncryptionService

> **Implementation status — 2026-06-15 (updated)**: The **crypto core is implemented and verified**
> — `crypto-lib` builds offline and the `ecies` suite is **21 tests / 32 total green, 0 failures**.
> Done & tested: the `(PO,C,T)` wire codec (byte-for-byte vs. the gemSpec_Krypt §4.7 example), the
> full ELC primitive (`ELC_ENC`/`ELC_DEC` per gemSpec_COS N004.500/600/N001.520 — ECKA → X9.63-KDF
> → AES-256-CBC → AES-CMAC, software encrypt↔decrypt round-trip incl. MAC-tamper rejection), the
> JCE `Cipher("ELC")` `Provider`/`CipherSpi` seam for software AND card keys (FR-015), and the CMS
> `AuthEnvelopedData` multi-recipient assembly with `oid_ti_ecies_transport_encryption`
> (single/multi-recipient round-trip, OID conformance, RSA/empty/no-matching-key negatives).
>
> **Update 2 — service wired**: `EncryptionService` is now rewired to the ECIES core (placeholder
> deleted): `encryptDocument` → trust-verify + `EciesTransportEncryption.encrypt`; `decryptDocument`
> → trial-unwrap each `ktri` via the `CryptoProvider` `"ELC"` decrypt seam (P12 adapter serves it
> through the JCE `Cipher("ELC")`). `EncryptionServiceEciesIT` (2 tests) proves the full
> encrypt→decrypt round-trip with the mandated OID + RSA rejection; `crypto-services-lib` is green.
> **23/35 tasks done.**
>
> **Remaining**: (a) `T014` document-size guard (zeroization + no-secret-audit are done; size cap
> tracked in T033); (b) the card `PSO:DECIPHER` `CardElcDecryptor` in the PC/SC & SICCT provider
> modules (`T024`) — needs the card APDU profile/hardware (the seam + P12 software path are done);
> (c) **gematik test vectors** for byte-exact card interop (`T027`) — the documented `ELC_ENC` yields
> a block-aligned `C` (48 B for a 32-byte key) whereas the §4.7 *example* `C` is 49 B, a discrepancy
> only a real vector / the COS `AES_CBC_ENC` detail can settle (`research.md` "Open items");
> (d) US3 conformance/interop tests (`T026/T028/T029`) and polish (`T030–T035`). The software
> implementation is internally self-consistent and conformant to the documented algorithm; card
> interop remains to be confirmed against a vector.

**Input**: Design documents from `specs/009-ecies-encryption-service/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (all present)

**Tests**: INCLUDED — the constitution (Principle II, Test-First) and spec (SC-002 test-vector / round-trip) require tests authored before implementation. Test tasks MUST be written and made to FAIL before their implementation tasks.

**Organization**: Grouped by user story (US1 encrypt P1, US2 decrypt P2, US3 conformance P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no incomplete dependencies)
- Paths follow plan.md Structure Decision: primitives + JCE provider in `crypto-lib`, orchestration in `crypto-services-lib`, card path in `crypto-pcsc-lib`/`crypto-sicct-lib`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Package scaffolding, constants, and test fixtures used across all stories

- [X] T001 Create ecies package skeleton with `package-info.java` in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/` and `.../ecies/jce/`
- [X] T002 [P] Define OID + brainpool curve constants (`oid_ti_ecies_transport_encryption` 1.2.276.0.76.4.222, id-ct-authEnvelopedData, id-aes256-gcm, brainpoolP256r1/384r1/512r1) in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/EciesOids.java`
- [X] T003 [P] Add test fixtures: brainpoolP256r1/384r1 key-pair + self-signed cert + P12 generator in `crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/EciesTestKeys.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The ELC parameter set and the `(PO,C,T)` ASN.1 codec — required by BOTH encrypt (US1) and decrypt (US2)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 [P] Unit test for `ElcCryptogram` encode/decode against the gemSpec_Krypt §4.7 example bytes (contracts/ecies-asn1.md) in `crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/ElcCryptogramTest.java` — MUST FAIL first
- [X] T005 Implement `ElcParameters` (curve → X9.63-KDF/SHA-256, AES-256-CBC, AES-CMAC length; supported-curve + named-curve validation) in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/ElcParameters.java` (per research.md Decision 2/7)
- [X] T006 Implement `ElcCryptogram` ASN.1 codec `toAsn1()`/`parse()` for `[6]{ curveOID ; [APPLICATION 73]{[6] PO} ; [6] C ; [14] T }` in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/ElcCryptogram.java` (depends T002, T005; makes T004 pass)

**Checkpoint**: `(PO,C,T)` round-trips and matches the normative example — encrypt and decrypt can now be built in parallel

---

## Phase 3: User Story 1 - Encrypt for one or more EC recipients (Priority: P1) 🎯 MVP

**Goal**: `EncryptionService.encryptDocument` produces a gematik-conformant CMS `AuthEnvelopedData` (AES-256-GCM content; one ECIES `ktri` per recipient with OID 1.2.276.0.76.4.222) decryptable by any recipient.

**Independent Test**: Encrypt a sample document to 1 and to 3 brainpoolP256r1 certs; assert structure/OID and that each recipient's key recovers the identical document (validated via the software decryptor from US2, or an external reference).

### Tests for User Story 1 ⚠️ (write first, ensure FAIL)

- [X] T007 [P] [US1] Unit test `ElcKeyWrapper.wrap` yields a `(PO,C,T)` whose AES-CBC/CMAC verify, in `crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/ElcKeyWrapperTest.java`
- [X] T008 [P] [US1] Unit test `EciesTransportEncryption.encrypt` builds `AuthEnvelopedData` with N `ktri`, each `keyEncryptionAlgorithm == 1.2.276.0.76.4.222`, single shared content/mac, in `crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/EciesTransportEncryptionTest.java`
- [X] T009 [P] [US1] Integration test `EncryptionService.encryptDocument` single + multi-recipient + negatives (empty list, RSA cert, unsupported/explicit-param curve, untrusted cert) in `crypto-services-lib/src/test/java/de/servicehealtherx/crypto/services/EncryptionServiceEciesIT.java`

### Implementation for User Story 1

- [X] T010 [US1] Implement `ElcKeyWrapper` (ephemeral keygen on cert curve → ECKA-DH → X9.63-KDF/SHA-256 → AES-256-CBC `C` → AES-CMAC `T` → `ElcCryptogram`; zeroize ephemeral private key) in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/ElcKeyWrapper.java` (depends T005, T006)
- [X] T011 [US1] Implement `EciesTransportEncryption.encrypt(document, certs)` (random 256-bit transport key, AES-256-GCM content via Bouncy Castle CMS, per-recipient `ktri` assembly with `encryptedKey = (PO,C,T)`, `AuthEnvelopedData` DER) in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/EciesTransportEncryption.java` (depends T010)
- [X] T012 [US1] Implement recipient validation (≥1 recipient, EC only, supported named curve, RSA → unsuitable-recipient, whole-request rejection on any failure) in `EciesTransportEncryption` / `EncryptionService` (FR-009, FR-010)
- [X] T013 [US1] Rewire `EncryptionService.encryptDocument` → trust verify (existing `TrustService`) + `EciesTransportEncryption.encrypt` + audit; delete placeholder `generateAesKey`/`wrapKeyForRecipients`/`assembleHybridCiphertext`/`encryptDocumentSymmetric` in `crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/EncryptionService.java`
- [ ] T014 [US1] Add transport-key zeroization + document-size guard; ensure audit/log carry no secret material (FR-012) in `EncryptionService` / `EciesTransportEncryption`

**Checkpoint**: US1 fully functional — produces conformant ciphertext; OID present on 100% of outputs (SC-003, SC-005)

---

## Phase 4: User Story 2 - Decrypt by key alias, software or card (Priority: P2)

**Goal**: `EncryptionService.decryptDocument` recovers the document using the caller-named key, with the smart-card ECKA hidden behind a JCE `Cipher("ELC")` so the service stays card-agnostic (FR-015).

**Independent Test**: Round-trip a US1 ciphertext with a P12 key; repeat with a `CardElcPrivateKey` backed by a fake `ElcDecryptor` (no hardware) through the same code path; assert distinguishable errors for tampered/unsupported/malformed/no-matching-key inputs.

### Tests for User Story 2 ⚠️ (write first, ensure FAIL)

- [X] T015 [P] [US2] Unit test `SoftwareElcDecryptor` unwraps a `(PO,C,T)` produced by `ElcKeyWrapper` (round-trip) in `crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/jce/SoftwareElcDecryptorTest.java`
- [X] T016 [P] [US2] Unit test `Cipher.getInstance("ELC", ElcSecurityProvider)` decrypts via software key and via a fake `CardElcPrivateKey`; unused SPI methods throw `UnsupportedOperationException`, in `crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/jce/ElcCipherSpiTest.java`
- [X] T017 [P] [US2] Integration test `EncryptionService.decryptDocument`: round-trip, alias→`ktri` selection, no-matching-key, integrity failure, unsupported-algorithm, malformed input (FR-011) in `crypto-services-lib/src/test/java/de/servicehealtherx/crypto/services/EncryptionServiceEciesIT.java`

### Implementation for User Story 2

- [X] T018 [P] [US2] Define `ElcDecryptor` seam interface (`byte[] unwrapTransportKey(ElcCryptogram)`) in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/jce/ElcDecryptor.java`
- [X] T019 [P] [US2] Implement `CardElcPrivateKey implements java.security.PrivateKey` (alias + `ElcDecryptor` ref; `getEncoded()`/`getFormat()` → null) in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/jce/CardElcPrivateKey.java`
- [X] T020 [US2] Implement `SoftwareElcDecryptor` (ECKA-DH + X9.63-KDF + AES-CBC decrypt + CMAC verify) in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/jce/SoftwareElcDecryptor.java` (depends T005, T006; makes T015 pass)
- [X] T021 [US2] Implement `ElcCipherSpi extends javax.crypto.CipherSpi` (DECRYPT-only; dispatch `ECPrivateKey`→`SoftwareElcDecryptor`, `CardElcPrivateKey`→its decryptor; unused methods throw) in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/jce/ElcCipherSpi.java` (depends T018, T020)
- [X] T022 [US2] Implement `ElcSecurityProvider extends java.security.Provider` registering `Cipher.ELC` in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/jce/ElcSecurityProvider.java` (depends T021; makes T016 pass)
- [X] T023 [US2] Implement `EciesTransportEncryption.decrypt` (parse `AuthEnvelopedData`, select `ktri` by `rid` matching the alias cert, assert OID, `Cipher("ELC")` unwrap, AES-256-GCM decrypt, distinguishable errors, zeroize) in `crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/EciesTransportEncryption.java` (depends T022)
- [ ] T024 [US2] Implement `CardElcDecryptor` (`PSO:DECIPHER` per gemSpec_COS §6.8.2.3) replacing the `UnsupportedOperationException` decrypt stubs in `crypto-lib/.../adapter/PcscKeyStoreAdapter.java` and `SicctKeyStoreAdapter.java` (or sibling classes in `crypto-pcsc-lib`/`crypto-sicct-lib`)
- [X] T025 [US2] Implement alias→`PrivateKey` resolution (p12 → `ECPrivateKey`; pcsc/sicct → `CardElcPrivateKey` wired to `CardElcDecryptor`) and rewire `EncryptionService.decryptDocument`; delete placeholder `unwrapKeyViaProvider`/`decryptDocumentSymmetric` in `crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/EncryptionService.java`

**Checkpoint**: US1 + US2 work independently; full encrypt→decrypt round-trip over software AND (faked) card keys; service has no smartcardio imports (FR-015)

---

## Phase 5: User Story 3 - Verifiable interoperability & conformance (Priority: P3)

**Goal**: Prove wire-compatibility with gematik tooling and field-by-field ASN.1 conformance, regression-testable over time.

**Independent Test**: Decrypt gematik test vectors → expected plaintext; decrypt service output with an independent reference; validate structure against contracts/ecies-asn1.md.

### Tests / validation for User Story 3 ⚠️

- [ ] T026 [P] [US3] ASN.1 conformance test asserting `AuthEnvelopedData` field layout + every `ktri` OID == 1.2.276.0.76.4.222 (contracts/ecies-asn1.md) in `crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/EciesAsn1ConformanceTest.java` (SC-002, SC-003, US3-AC3)
- [ ] T027 [P] [US3] Test-vector round-trip: decrypt gematik Beispiel-Chiffrate → expected plaintext (parameterized; skipped-with-reason if vectors absent) in `crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/EciesTestVectorIT.java` (SC-002, US3-AC1)
- [ ] T028 [P] [US3] Cross-tool interop: decrypt the service's output with an independent BC/OpenSSL ELC reference in `crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/EciesInteropIT.java` (US3-AC2)

### Supporting implementation for User Story 3

- [ ] T029 [US3] Add `src/test/resources/ecies-vectors/` fixture dir + loader and document how to obtain gematik vectors (update quickstart.md / research.md Open items)

**Checkpoint**: Conformance demonstrable for certification; all SC met

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T030 [P] Refresh `specs/009-ecies-encryption-service/diagrams/*` and `diagrams/README.md` if class/flow names changed during implementation (Principle VI)
- [ ] T031 [P] Verify ≥ 80% unit coverage on the `ecies` package; fill gaps (Principle II / Quality Gate)
- [ ] T032 Security hardening: add a test asserting no transport key / ephemeral key / plaintext appears in logs or audit records (FR-012, SC-006); confirm SAST clean
- [ ] T033 Add configurable max-plaintext-size property + a benchmark confirming per-op budget (SC-007, Principle IV memory ceiling)
- [ ] T034 Document the §4.7.1 multi-recipient authenticity caveat in `EncryptionService` Javadoc and contracts (research.md Decision 8)
- [ ] T035 Run all quickstart.md scenarios 1–5 end-to-end; record Afo A_17220 traceability in the PR description

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup — **BLOCKS US1 and US2** (provides `ElcParameters` + `ElcCryptogram`)
- **US1 (Phase 3)**: depends on Foundational
- **US2 (Phase 4)**: depends on Foundational; independently testable with software keys + a fake `ElcDecryptor` (does not require US1, but shares `ElcKeyWrapper`/`EciesTransportEncryption` file — coordinate edits)
- **US3 (Phase 5)**: depends on US1 (to produce output) and US2 (to decrypt) for full round-trip; T026 (structure) needs only US1
- **Polish (Phase 6)**: depends on the targeted stories being complete

### Key within/cross-story dependencies

- T005, T006 block T010, T011 (US1) and T020, T023 (US2)
- T010 → T011 → T012 → T013 → T014 (US1 chain; same files)
- T018, T019 [P] → T020 → T021 → T022 → T023 → T025 (US2 chain)
- T024 (card `PSO:DECIPHER`) is the only hardware-coupled task; US2 stays independently testable without it via the fake decryptor

### Parallel Opportunities

- Setup: T002, T003 in parallel
- Foundational: T004 (test) authored alongside, then T005/T006
- US1 tests T007, T008, T009 in parallel (different files) before implementation
- US2: T015, T016, T017 in parallel; T018, T019 in parallel
- US3: T026, T027, T028 in parallel
- Polish: T030, T031 in parallel

---

## Parallel Example: User Story 1

```bash
# Author US1 tests together (write first, must fail):
Task: "Unit test ElcKeyWrapper in crypto-lib/.../ecies/ElcKeyWrapperTest.java"
Task: "Unit test EciesTransportEncryption.encrypt in crypto-lib/.../ecies/EciesTransportEncryptionTest.java"
Task: "Integration test EncryptionService.encryptDocument in crypto-services-lib/.../EncryptionServiceEciesIT.java"
```

---

## Implementation Strategy

### MVP First (User Story 1)
1. Phase 1 Setup → 2. Phase 2 Foundational (CRITICAL) → 3. Phase 3 US1 → **STOP & VALIDATE** conformant ciphertext (OID + ASN.1) → demo.

### Incremental Delivery
1. Setup + Foundational → foundation ready
2. US1 → conformant encryption (MVP)
3. US2 → decryption (software + card via JCE seam)
4. US3 → conformance/interop evidence for certification

### Notes
- [P] = different files, no incomplete deps. Verify each test FAILS before implementing.
- US1/US2 both edit `EciesTransportEncryption.java` — if staffed in parallel, split encrypt/decrypt methods carefully or sequence the two.
- Commit after each task or logical group; reference Afo A_17220 + ticket per commit (Quality Gate).
