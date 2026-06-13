# Implementation Plan: apdu-lib — TUC Card Management

**Branch**: `007-apdu-lib-tuc-card-mgmt` | **Date**: 2026-06-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-apdu-lib-tuc-card-mgmt/spec.md`

## Summary

This plan covers the creation of a new Maven module `apdu-lib` (`de.servicehealtherx:apdu-lib`) that implements all 27 Technical Use Cases (TUCs) from gemSpec_Kon V5.27.0 §4.1.5.4. The module provides PIN management, file and record I/O, cryptographic operations, card session lifecycle, and card-to-card authentication as independently callable Java service classes. Each TUC is a final, plain Java class that generates command APDUs and expected response constraints. APDU execution is delegated to transport modules (`sicct-lib`, `crypto-pcsc-lib`), and `apdu-lib` does not send APDUs itself.

---

## Technical Context

**Language/Version**: Java 21 (parent BOM: `maven.compiler.source=21`, `maven.compiler.target=21`)

**Primary Dependencies**:
- `de.servicehealtherx:crypto-lib` — ECC/RSA operations (TUC_KON_005 Gen2, TUC_KON_219/220) via Bouncy Castle 1.84
- `de.gematik.pki:gemLibPki:4.0.2` — TI PKI validation (accessed via `crypto-lib`)
- `org.bouncycastle:bcprov-jdk18on:1.84` — ECC/RSA (accessed via `crypto-lib`)

**Storage**: N/A — stateless APDU construction library; `AuthState` is in-memory per `CardSession`

**Testing**: JUnit 5 (Jupiter) + Mockito — provided by Quarkus 3.36.1 BOM; JaCoCo for coverage (≥80% line coverage enforced, SC-002)

**Target Platform**: JVM 21 within the Konnektor Quarkus 3.36.1 runtime (Linux server)

**Project Type**: Library (Maven JAR module)

**Performance Goals**:
- TUC_KON_200 / TUC_KON_208 APDU generation < 20ms per request (SC-005)
- PIN entry APDU-sequence generation < 50ms excluding user input delay (SC-004)

**Constraints**:
- No physical card hardware required for unit tests (validate generated APDU bytes and metadata only)
- KVK cards: write, clear, and cryptographic TUCs rejected at precondition check (FR-008)
- Remote-PIN requires configured Remote-PIN-KT; raises `TucException(4092)` otherwise (FR-006)
- `apdu-lib` MUST NOT depend on `sicct-lib`
- `apdu-lib` MUST NOT send APDUs to cards
- One git commit per Afo ID; test method names must include requirement ID (Principle V)

**Scale/Scope**: 27 TUC operations, ~14 model types, 27 service classes, ≥27 test classes

---

## Constitution Check

### Gate Evaluation

| Principle                           | Gate                                  | Status     | Notes                                                                                                                          |
| ----------------------------------- | ------------------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **I — Code Quality**                | No single-implementation interfaces   | ✅ PASS     | No transport interface in `apdu-lib`; TUC classes are plain `final` classes that generate APDU requests.                       |
| **I — Code Quality**                | No `Impl` suffix                      | ✅ PASS     | Classes named after TUC IDs: `TucKon012VerifyPin`, `TucKon005CardToCardAuth`, etc.                                             |
| **II — Testing**                    | ≥80% line coverage                    | ✅ ENFORCED | JaCoCo check goal in `apdu-lib` `pom.xml`; every TUC has ≥1 test method per error code                                         |
| **II — Testing**                    | Test-First                            | ✅ REQUIRED | Each TUC test class to be authored before the implementation class (enforced by PR review)                                     |
| **II — Testing**                    | Descriptive test names                | ✅ REQUIRED | Pattern: `test_<requirementId>_<description>` (e.g., `test_TIP1_A_4566_verify_pin_returns_ok_for_correct_pin`)                 |
| **V — Security (Afo traceability)** | One commit per Afo                    | ✅ REQUIRED | Each TUC commit references its requirement ID + Jira ticket                                                                    |
| **V — Security (Afo traceability)** | One test per Afo                      | ✅ REQUIRED | Afo_Testmatrix to be populated before release (SC-007)                                                                         |
| **V — Security (SBOM)**             | New dependencies in SBOM              | ✅ ENFORCED | Bouncy Castle 1.84 and gemLibPki 4.0.2 documented in `contracts/public-api.md` SBOM table; these are already in the parent BOM |
| **VI — UML Diagrams**               | PlantUML diagrams in `diagrams/`      | ✅ CREATED  | use-case, deployment, component, 2× sequence, state, class diagrams; `diagrams/README.md` present                              |
| **VII — External Library**          | Library evaluation before custom impl | ✅ COMPLETE | Bouncy Castle and gemLibPki evaluated in `research.md` §2–3                                                                    |
| **VIII — Standard Interface**       | JDK/framework interface research      | ✅ COMPLETE | `javax.smartcardio.CommandAPDU` adopted as canonical APDU command type in `research.md` §1                                     |

**Gate result**: All gates PASS or have documented rationale. No violations requiring complexity tracking.

---

## Project Structure

### Documentation (this feature)

```text
specs/007-apdu-lib-tuc-card-mgmt/
├── plan.md              ← this file
├── spec.md              ← feature specification
├── research.md          ← Phase 0 output
├── data-model.md        ← Phase 1 output — entity definitions
├── quickstart.md        ← Phase 1 output — validation guide
├── contracts/
│   └── public-api.md    ← Phase 1 output — TUC method signatures, error codes, SBOM
├── diagrams/
│   ├── README.md        ← diagram index with GitHub proxy image links
│   ├── use-case.puml
│   ├── deployment.puml
│   ├── component.puml
│   ├── sequence-verify-pin.puml
│   ├── sequence-card-to-card-auth.puml
│   ├── state-card-session.puml
│   └── class-model.puml
├── tucs/                ← individual TUC specifications (27 files)
└── tasks.md             ← Phase 2 output (/speckit-tasks command)
```

### Source Code (repository root)

```text
apdu-lib/
├── pom.xml
└── src/
    ├── main/
    │   └── java/
    │       └── de/servicehealtherx/apdu/
    │           ├── model/
    │           │   ├── AuthMode.java
    │           │   ├── AlgorithmId.java
    │           │   ├── AuthState.java
    │           │   ├── CardSession.java
    │           │   ├── CardType.java
    │           │   ├── CardVersion.java
    │           │   ├── GeneratedApduStep.java
    │           │   ├── GematikISO7816.java
    │           │   ├── KeyRef.java
    │           │   ├── PinRef.java
    │           │   ├── PinResult.java
    │           │   ├── PinStatus.java
    │           │   ├── PukResult.java
    │           │   ├── ApduExpectation.java
    │           │   └── TucException.java
    │           └── tuc/
    │               ├── TucKon005CardToCardAuth.java
    │               ├── TucKon006WriteAudit.java
    │               ├── TucKon012VerifyPin.java
    │               ├── TucKon018CheckEgkBlocking.java
    │               ├── TucKon019ChangePin.java
    │               ├── TucKon021UnblockPin.java
    │               ├── TucKon022ProvidePinStatus.java
    │               ├── TucKon023ReserveCard.java
    │               ├── TucKon024ResetCard.java
    │               ├── TucKon026ProvideCardSession.java
    │               ├── TucKon027EnableDisablePin.java
    │               ├── TucKon036ProvideProfessionalRole.java
    │               ├── TucKon200SendApdu.java
    │               ├── TucKon202ReadFile.java
    │               ├── TucKon203WriteFile.java
    │               ├── TucKon204ClearFile.java
    │               ├── TucKon208SendSecuredApdu.java
    │               ├── TucKon209ReadRecord.java
    │               ├── TucKon210WriteRecord.java
    │               ├── TucKon211ClearRecord.java
    │               ├── TucKon214AppendRecord.java
    │               ├── TucKon215SearchRecord.java
    │               ├── TucKon216ReadCertificate.java
    │               ├── TucKon219Sign.java
    │               ├── TucKon220Decrypt.java
    │               ├── TucKon223StartCardSession.java
    │               └── TucKon224StopCardSession.java
    └── test/
        └── java/
            └── de/servicehealtherx/apdu/
                ├── testutil/
                │   └── ApduAssertions.java
                └── tuc/
                    ├── TucKon005CardToCardAuthTest.java
                    ├── TucKon006WriteAuditTest.java
                    ├── TucKon012VerifyPinTest.java
                    ├── TucKon018CheckEgkBlockingTest.java
                    ├── TucKon019ChangePinTest.java
                    ├── TucKon021UnblockPinTest.java
                    ├── TucKon022ProvidePinStatusTest.java
                    ├── TucKon023ReserveCardTest.java
                    ├── TucKon024ResetCardTest.java
                    ├── TucKon026ProvideCardSessionTest.java
                    ├── TucKon027EnableDisablePinTest.java
                    ├── TucKon036ProvideProfessionalRoleTest.java
                    ├── TucKon200SendApduTest.java
                    ├── TucKon202ReadFileTest.java
                    ├── TucKon203WriteFileTest.java
                    ├── TucKon204ClearFileTest.java
                    ├── TucKon208SendSecuredApduTest.java
                    ├── TucKon209ReadRecordTest.java
                    ├── TucKon210WriteRecordTest.java
                    ├── TucKon211ClearRecordTest.java
                    ├── TucKon214AppendRecordTest.java
                    ├── TucKon215SearchRecordTest.java
                    ├── TucKon216ReadCertificateTest.java
                    ├── TucKon219SignTest.java
                    ├── TucKon220DecryptTest.java
                    ├── TucKon223StartCardSessionTest.java
                    └── TucKon224StopCardSessionTest.java
```

**Structure Decision**: Single Maven module (`apdu-lib`) added to the existing reactor, positioned after `sicct-lib` and `crypto-lib` in `<modules>`. No additional source modules are introduced. The `apdu-lib` module is a pure Java library JAR — it does not start a Quarkus application and does not use CDI.

---

## Implementation Sequencing

Implementation follows the priority order from the spec, with each TUC committed individually (one commit = one Afo):

**Priority P1 — Foundation (must be first)**
1. Maven module scaffold: `apdu-lib/pom.xml`, `ApduAssertions`, `GematikISO7816`, model types
2. TUC_KON_026 — Provide Card Session (TIP1-A_4567)
3. TUC_KON_023 — Reserve Card (TIP1-A_4571-03)
4. TUC_KON_022 — Provide PIN Status (TIP1-A_4570)
5. TUC_KON_012 — Verify PIN (TIP1-A_4566) ← depends on TUC_KON_005 for Gen1+ path
6. TUC_KON_005 — Card-to-Card Authentication (TIP1-A_4572)
7. TUC_KON_019 — Change PIN (TIP1-A_4568)
8. TUC_KON_021 — Unblock PIN (TIP1-A_4569-02)
9. TUC_KON_027 — Enable/Disable PIN Protection (TIP1-A_5486)

**Priority P2 — File/Record I/O and Crypto**
10. TUC_KON_200 — Send APDU (TIP1-A_4583-02) ← base APDU request generation, needed by all file TUCs
11. TUC_KON_202 — Read File (TIP1-A_4573)
12. TUC_KON_203 — Write File (TIP1-A_4574)
13. TUC_KON_204 — Clear File (TIP1-A_4576-1)
14. TUC_KON_209 — Read Record (TIP1-A_4575)
15. TUC_KON_210 — Write Record (TIP1-A_4576-2)
16. TUC_KON_211 — Clear Record (TIP1-A_4577-1)
17. TUC_KON_214 — Append Record (TIP1-A_4577-2)
18. TUC_KON_215 — Search Record (TIP1-A_4578)
19. TUC_KON_219 — Sign (TIP1-A_4581)
20. TUC_KON_220 — Decrypt (TIP1-A_4582)
21. TUC_KON_223 — Start Card Session (A_26067)
22. TUC_KON_208 — Send Secured APDU (A_26069-01)
23. TUC_KON_224 — Stop Card Session (A_26068)
24. TUC_KON_024 — Reset Card (TIP1-A_4584-02)

**Priority P3 — Utility and Audit**
25. TUC_KON_018 — Check eGK Blocking Status (TIP1-A_4579-02)
26. TUC_KON_006 — Write Data Access Audit (TIP1-A_4580) ← depends on TUC_KON_214
27. TUC_KON_216 — Read Certificate (TIP1-A_4585)
28. TUC_KON_036 — Provide Professional Role (TIP1-A_5478) ← depends on TUC_KON_216

---

## Complexity Tracking

No constitution violations to justify. All gates pass.
