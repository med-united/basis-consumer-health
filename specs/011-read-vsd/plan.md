# Implementation Plan: VSDService — Local ReadVSD (Card-to-Card + eGK Read)

**Branch**: `011-read-vsd` | **Date**: 2026-06-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/011-read-vsd/spec.md`

## Summary

Implement the gematik VSDM Fachmodul operation **`ReadVSD`** (`I_VSDService`) as a
SOAP endpoint on the konnektor that, given an eGK handle (`EhcHandle`) and an
HBA/SMC-B handle (`HpcHandle`), performs a **card-to-card (C2C) authentication**
between the two cards and reads the Versichertenstammdaten (VSD) — personal data
(PD), general insurance data (VD), the VSD status, and, when C2C authorises it, the
protected data (GVD) — directly from the eGK, returning them in the SOAP response.

The realisation is **strictly local**: no Update Flag Service (UFS), no
Versichertendatendienst / Card Management System (VSDD/CMS), no Intermediär, and no
Prüfungsnachweis. Requests asking for an online check (`PerformOnlineCheck=true`) or
a receipt (`ReadOnlineReceipt=true`) are rejected with a gematik SOAP fault
(clarified 2026-06-18).

The work spans three existing reactor modules — **no new module is introduced**:

1. **`api-telematik`** — add a `wsdl2java` `<wsdlOption>` for the already-present
   `conn/vsds/VSDService.wsdl`, generating
   `de.gematik.ws.conn.vsds.vsdservice.v5_2.*` (port type, `ReadVSD`,
   `ReadVSDResponse`, `VSDStatusType`, `FaultMessage`).
2. **`apdu-lib`** — the card-level VSDM logic: a generic **APDU executor** (the
   foundational missing piece — today only `CardCertificateReader` executes APDUs), a
   **real `TucKon005CardToCardAuth`** (the current class is a 3-APDU stub), the **eGK
   VSDM object-system constants** (DF.HCA AID, EF.PD/EF.VD/EF.StatusVD/EF.GVD), and a
   `ReadVsdService` orchestrator that drives the existing reusable building blocks
   (`CmCardList` handle resolution, `CardSessionService` TUC_KON_026/023, the
   multi-block READ-BINARY mechanics, certificate/ICCSN extraction TUC_KON_034).
3. **`konnektor-soap-server`** — a new `KonnektorVSDService` `@CXFEndpoint` that
   implements the generated `VSDServicePortType`, delegates to `ReadVsdService`, and
   maps results/errors to `ReadVSDResponse` / the VSDM SOAP faults
   (3001/3011/3041/3042 and OM 106/107/114).

Served at `/ws/conn/VSDService` (Quarkiverse CXF, `quarkus.cxf.path=/ws`).

## Technical Context

**Language/Version**: Java 21 LTS

**Primary Dependencies**:

| Dependency | Purpose |
|---|---|
| Quarkiverse CXF (`io.quarkiverse.cxf:quarkus-cxf`, BOM 3.36.0) | Publishes the `@CXFEndpoint`/`@WebService` SOAP endpoint, identical to the seven existing `konnektor-soap-server` services. **WSDL-generated port type — Principle I exempt.** |
| `api-telematik` `cxf-codegen-plugin` 4.0.5 (`wsdl2java`) | Generates `de.gematik.ws.conn.vsds.vsdservice.v5_2.*` from `conn/vsds/VSDService.wsdl` + `VSDService.xsd`; reuses `de.gematik.ws.conn.connectorcontext.v2.ContextType` and `de.gematik.ws.tel.error.v2.Error`. |
| `apdu-lib` (`de.servicehealtherx.apdu.*`) | Reused card building blocks: `CmCardList.findByHandle`, `CardObject`, `CardSessionService` (TUC_KON_026/023/223/224), `TucKon023ReserveCard`, the `readBinaryFull` multi-block READ-BINARY mechanics, `CardCertificateReader`/`CardAttributeReader` (TUC_KON_034 / ICCSN / KVNR), `GematikISO7816` constants, `CardReaderPort` transport SPI (PCSC + SICCT impls). |
| `crypto-lib` Bouncy Castle (`org.bouncycastle`, already adopted) | CV-certificate (CVC) parsing + ECDSA/ELC signature verification for the C2C chain (Principle VII — no new crypto code). |
| JDK JCA (`java.security.Signature`, `CertificateFactory`) + `java.util.zip` | Standard signature verification and (only if ever needed) gzip handling. **Standard interfaces — Principle VIII.** |
| MicroProfile Config (`@ConfigProperty`) | `vsdm.read.timeout-ms` hard-timeout (FR-027), consistent with `SmkCSAKAutProvider`/`cetp-client-lib` config. |
| JUnit 5 + `@QuarkusTest` + Mockito (already present) | Unit tests (APDU executor against a fake `CardReaderPort`, StatusVD conversion, C2C chain, orchestration) + the **first** `@QuarkusTest` in `konnektor-soap-server`. Afo-named tests. |

**Storage**: None. The feature is stateless — no JPA entities, no database. VSD is
read from the card and returned; nothing is persisted (FR-028).

**Testing**: JUnit 5 unit tests for the APDU executor (fake `CardReaderPort` replaying
canned responses), the EF.StatusVD → `VSD_Status` conversion (pure), the C2C
challenge/CVC chain (test vectors / fake card), and `ReadVsdService` orchestration
(mocked executor); a `@QuarkusTest` integration test driving `ReadVSD` over the CXF
endpoint with a fake card backend. Determinism via an injected clock + injected
timeout + an in-process fake card (infrastructure seam only). Afo-named tests
(`test_VSDM_A_2572_*`, `test_VSDM_A_2660_*`, …).

**Target Platform**: Linux server, JVM mode, Kubernetes pod (consistent with features
001/002/010); card access via the konnektor's PC/SC or SICCT card terminals.

**Project Type**: Extension to the existing multi-module Maven reactor. **No new
module.** Three existing modules touched; the dependency edge
`konnektor-soap-server → apdu-lib` already exists.

**Performance Goals**: Card-bound. No fixed p95 latency target applies to the card
read itself (FR-027 / SC-008); a **hard timeout (default 30 s, `MAXTIME_VSDM`)**
bounds the whole operation and releases the eGK reservation on expiry. Konnektor-side
processing overhead excluding card I/O stays within the constitution's API budget.

**Constraints**: Strictly local — **zero** external TI connections (FR-006 / SC-004);
decoded VSD / insured PII **never logged or persisted** (FR-028 / SC-009), held only
in memory for the call; PD/VD/GVD returned **byte-for-byte** as read from the card
(gzip-compressed on the card) and Base64-encoded — no decompression or content
validation (FR-005); secret/card key material never logged (Principle V); 150 MB
memory ceiling (Principle IV) — VSD buffered per call, no accumulation across calls.

**Scale/Scope**: One `ReadVSD` per eGK per call; concurrent calls for *different*
cards supported (FR-026); a second concurrent call for the **same** eGK fails fast
with a card-busy fault (FR-029, exclusive reservation).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Gate | Status | Notes |
|---|---|---|
| Principle I — No premature abstraction / no single-impl interface; no `Impl` suffix | ✅ PASS | New types are concrete single-purpose classes: `ApduExecutor`, `ReadVsdService`, `VsdReadResult`, `VsdStatus`, `EgkVsdmFile` (enum). The C2C work *replaces* the existing `TucKon005CardToCardAuth` (no new interface). `CardReaderPort` is a pre-existing SPI with **two** impls (PCSC/SICCT). `VSDServicePortType` is WSDL-generated (externally mandated — exempt). No `Impl` suffix. |
| Principle II — Test-first, ≥ 80% coverage, deterministic | ✅ PASS | Tests authored before code; card I/O made deterministic via a fake `CardReaderPort` (infrastructure seam); timeout via injected value; no domain mocking. Adds the first `@QuarkusTest` to `konnektor-soap-server`. |
| Principle IV — Performance budgets | ✅ PASS (with documented exception) | The 200 ms p95 API rule cannot apply to physical smartcard I/O; replaced by FR-027's hard 30 s timeout + SC-008. No unbounded buffers; PII not retained (FR-028). |
| Principle V — Security & gematik TI compliance + Afo traceability | ✅ PASS | Per-Afo commits with named tests (VSDM-A_2567/2568/2569/2570/2571/2572/2573/2574/2647/2652/2660/2682/2691/2693/2708/2784/2998, A_23020); C2C raises the eGK security state for protected data; PII never logged (FR-028); card key material never logged. |
| Principle VI — UML diagrams | ✅ PASS | use-case, deployment, component, sequence-readvsd, sequence-c2c, state-readvsd, class-vsdm + `diagrams/README.md` produced in Phase 1. |
| Principle VII — External library evaluation | ✅ PASS | **No new dependency.** C2C CVC/ECDSA verification reuses already-adopted Bouncy Castle; gzip/JCA from the JDK. gemLibPki evaluated and rejected for CVC (it targets X.509 TI-PKI, not card-verifiable certificates). Recorded in research.md. |
| Principle VIII — Standard interface adoption | ✅ PASS | Reuses JCA `Signature`/`CertificateFactory`, `java.util.zip`, the pre-existing `CardReaderPort` transport SPI, JAX-WS `VSDServicePortType`. No parallel custom abstraction introduced. Recorded in research.md. |
| Quality Gate — SBOM / new dependency | ✅ PASS | No new dependency → no SBOM addition. |

**Result: PASS — no violations. Complexity Tracking not required.**

**Primary implementation risk (not a constitution violation):** real **TUC_KON_005
C2C** is green-field and is the largest, most crypto-sensitive work item. The cards do
the ECDSA/ECKA primitives on-card; the host-side risk is concentrated in two helpers —
**`CvcChainParser`** (CVC TLV parsing / chain ordering) and **`SecureMessagingSession`**
(AES-CMAC + AES-CBC wrap/unwrap of the EF.GVD `READ BINARY`, since `AUT_VSD` mandates SM
command+response protection). Both reuse Bouncy Castle (no new dependency). Isolated
behind `TucKon005CardToCardAuth` + `apdu/c2c/*` and the C2C contract; researched in
Phase 0 (research D4). EF.PD/EF.VD/EF.StatusVD are AlwaysRead (plain READ BINARY), so
the mandatory VSD payload (US1) does not depend on the SM channel — only GVD (US2) does.

## Project Structure

### Documentation (this feature)

```text
specs/011-read-vsd/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions (codegen, module placement, APDU executor,
│                        #   C2C protocol, StatusVD conversion, timeout/PII, library eval)
├── data-model.md        # Phase 1 — VSD containers, VSD_Status, VsdReadResult, eGK EF map
├── quickstart.md        # Phase 1 — build + run + validate ReadVSD end-to-end
├── contracts/
│   ├── readvsd-soap.md          # ReadVSD request/response + fault-code mapping (WSDL/XSD)
│   ├── c2c-authentication.md    # TUC_KON_005 APDU sequence + CVC chain + access raise
│   ├── egk-vsdm-files.md        # DF.HCA AID + EF.PD/VD/StatusVD/GVD FIDs + access rules
│   └── apdu-executor.md         # generic TucGenerationResult execution + status validation
├── diagrams/
│   ├── README.md
│   ├── use-case.puml
│   ├── deployment.puml
│   ├── component.puml
│   ├── sequence-readvsd.puml
│   ├── sequence-c2c.puml
│   ├── state-readvsd.puml
│   └── class-vsdm.puml
├── checklists/
│   └── requirements.md  # (created by /speckit-specify)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
api-telematik/
├── pom.xml                              # MODIFIED: add <wsdlOption> for conn/vsds/VSDService.wsdl
└── conn/vsds/VSDService.wsdl|.xsd       # existing contract → generates
                                         #   de.gematik.ws.conn.vsds.vsdservice.v5_2.*

apdu-lib/src/main/java/de/servicehealtherx/apdu/
├── model/GematikISO7816.java            # MODIFIED: DF.HCA AID + EF.PD/VD/StatusVD/GVD FIDs/SFIDs
├── card/
│   ├── ApduExecutor.java                # NEW: run TucGenerationResult steps vs CardReaderPort,
│   │                                    #   validate each ExpectedStatusSet, return responses
│   └── EgkFileReader.java               # NEW: SELECT DF.HCA + multi-block READ BINARY of an EF
│                                        #   (generalises CardCertificateReader.readBinaryFull)
├── tuc/TucKon005CardToCardAuth.java     # REPLACED: real C2C (CVC read/verify, MSE, mutual auth)
├── c2c/
│   ├── CvcChainParser.java              # NEW: parse CVC TLV (7F21/7F4E/5F37, CAR/CHR/CHAT) — BC
│   └── SecureMessagingSession.java      # NEW: AES-CMAC + AES-CBC wrap/unwrap of SM APDUs (AUT_VSD)
└── vsdm/
    ├── ReadVsdService.java              # NEW: orchestrator (000→026→023→018→005→202×→006→convert)
    ├── VsdReadResult.java               # NEW: PD, VD, optional GVD (raw card bytes) + VsdStatus
    ├── VsdStatus.java                   # NEW: Status/Timestamp/Version from EF.StatusVD
    ├── StatusVdConverter.java           # NEW: EF.StatusVD binary → VsdStatus (Tab_FM_VSDM_21)
    └── VsdmReadException.java           # NEW: carries the VSDM error code (3001/3011/3041/3042)

konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/
├── KonnektorVSDService.java             # NEW: @CXFEndpoint("/conn/VSDService"),
│                                        #   implements VSDServicePortType; delegates to ReadVsdService
└── VsdmFaultFactory.java                # NEW: VsdmReadException/OM code → de.gematik...Error + FaultMessage

apdu-lib/src/test/java/.../vsdm/...                 # unit tests (executor, StatusVD, C2C, orchestration)
konnektor-soap-server/src/test/java/.../soap/...    # first @QuarkusTest in the module (ReadVSD e2e)
```

**Structure Decision**: Extend three existing modules rather than create a new one.
The card-level VSDM logic belongs in **`apdu-lib`** beside the other card services and
the `CardCertificateReadService` orchestration template it mirrors; the SOAP edge
belongs in **`konnektor-soap-server`** beside the seven existing endpoints; the
contract is generated in **`api-telematik`** like every other gematik service. The
only "new" structural surface is one `vsdm` package in `apdu-lib` and one endpoint
class — no new reactor edge (`konnektor-soap-server → apdu-lib` already exists), so the
build graph is unchanged and acyclic. This keeps the change minimal and consistent
with Principle I (no premature module/abstraction).

## Complexity Tracking

> No Constitution Check violations — section intentionally empty. (The TUC_KON_005 C2C
> build is implementation effort/risk, not an architectural complexity violation; it
> replaces an existing stub and adds no new abstraction.)
