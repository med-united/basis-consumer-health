# Phase 0 Research: VSDService — Local ReadVSD

**Feature**: 011-read-vsd | **Date**: 2026-06-18

Resolves the open technical decisions and records the mandatory external-library
evaluation (Principle VII) and standard-interface research (Principle VIII). No item
remains marked NEEDS CLARIFICATION. Decisions are grounded in two codebase surveys
(SOAP wiring; card/TUC building blocks) and three spec extractions (gemSpec_FM_VSDM,
gemSpec_SST_PS_VSDM, gemSpec_COS/eGK_ObjSys/SMC-B_ObjSys).

---

## D1 — SOAP contract generation & endpoint wiring

**Decision**: Add a `cxf-codegen-plugin` `<wsdlOption>` in `api-telematik/pom.xml` for
the existing `conn/vsds/VSDService.wsdl`, generating
`de.gematik.ws.conn.vsds.vsdservice.v5_2.*` (`VSDServicePortType`, `ReadVSD`,
`ReadVSDResponse`, `VSDStatusType`, `FaultMessage`). Implement the endpoint as
`KonnektorVSDService` with `@CXFEndpoint("/conn/VSDService")` +
`@WebService(endpointInterface="…vsdservice.v5_2.VSDServicePortType")`, exactly like
the seven existing services. Served at `/ws/conn/VSDService` (`quarkus.cxf.path=/ws`).

**Rationale**: Quarkiverse CXF is the established stack; the WSDL/XSD already exist on
disk; there is currently **no** `<wsdlOption>` for `vsds`. The WSDL's
`targetNamespace` (`…/VSDService/v5.2`) has no separate `/WSDL/` namespace, so CXF
emits a single package. Reuses `ContextType` and `Error` types already generated for
other services.

**Alternatives considered**: hand-writing JAX-WS types (rejected — codegen is the
project standard, Principle I exempts WSDL-generated interfaces); a new module
(rejected — see D2).

---

## D2 — Module placement (no new module)

**Decision**: Extend three existing modules. Card-level VSDM logic → **`apdu-lib`**
(new `vsdm` + `c2c` packages, extended `GematikISO7816`, new `ApduExecutor` /
`EgkFileReader`, real `TucKon005CardToCardAuth`). SOAP edge → **`konnektor-soap-server`**
(`KonnektorVSDService`, `VsdmFaultFactory`). Contract → **`api-telematik`**.

**Rationale**: `konnektor-soap-server` **already depends on** `apdu-lib`,
`crypto-services-lib`, `api-telematik`, `quarkus-sicct-extension` — no new reactor
edge. `apdu-lib` is the natural home: it holds the TUC step generators, the card
registry/session services, and the `CardCertificateReadService` orchestration template
this feature mirrors. A new module would be premature (Principle I) and add an edge for
no gain. The feature is stateless, so no persistence module is involved.

**Alternatives considered**: a `vsdm-lib` module à la `cetp-client-lib` (rejected — 010
needed JPA entities + a new dependency direction; this feature has neither); putting
the orchestrator in `crypto-services-lib` (rejected — card access lives in `apdu-lib`).

---

## D3 — Generic APDU executor (foundational new piece)

**Decision**: Add `ApduExecutor(CardReaderPort)` that runs a `TucGenerationResult`'s
ordered `GeneratedApduStep`s, validates each `ExpectedStatusSet`, and returns the
responses (see `contracts/apdu-executor.md`).

**Rationale**: The `apdu-lib` TUC classes are **step generators**, not executors; only
`CardCertificateReader` executes APDUs today, bypassing the generator pattern.
`ReadVsdService` must drive several multi-step TUCs (026/023/018/005/202/006), so one
tested executor is the prerequisite. Single responsibility: transmit + verify SW; it
knows nothing about VSDM. Reuses the existing `CardReaderPort` SPI (PCSC + SICCT impls).

**Alternatives considered**: per-TUC bespoke execution (rejected — duplication, harder
to test); driving `javax.smartcardio` directly (rejected — the project standardised on
`CardReaderPort`, which already has two impls and wraps both PCSC and SICCT).

---

## D4 — Card-to-Card authentication (TUC_KON_005) — the crux

**Decision**: Replace the stub `TucKon005CardToCardAuth` with a real **ELC mutual
authentication with session-key agreement** (gemSpec_COS §15.4.4) establishing the
**AUT_VSD Trusted Channel**, per `contracts/c2c-authentication.md`. Split host-side
helpers into `apdu/c2c/CvcChainParser` (CVC TLV parsing / chain ordering) and
`apdu/c2c/SecureMessagingSession` (AES-CMAC + AES-CBC wrap/unwrap), both on Bouncy
Castle. The eGK and SMC-B/HBA cards perform the ECDSA verification, ECKA-DH
shared-secret computation and session-key derivation **on-card**; the konnektor only
sequences APDUs (`READ BINARY` CVCs → `MSE SET` + `PSO VERIFY CERTIFICATE` →
`MSE SET` → two-step `GENERAL AUTHENTICATE`) and then SM-wraps the EF.GVD read.

**Rationale**: This is the variant the eGK's `EF.GVD` access rule
(`{AUT_VSD} = SmMac(...) AND SmCmdEnc AND SmRspEnc`, eGK ObjSys p. 9/73) requires — a
plain raised state is insufficient; the GVD `READ BINARY` must be Secure-Messaging
protected. Trust path `PuK.RCA.CS.E256 → EF.C.CA.CS.E256 → EF.C.*.AUT_CVC.E256`
(brainpoolP256r1). Keeping ECDSA/ECKA on-card minimises host crypto surface; the
residual host risk (SM channel + CVC parsing) is isolated and BC-backed.

**Risk / scope note**: This is the largest, most error-prone work item and the most
likely to need hardware-in-the-loop validation. **EF.PD/EF.VD/EF.StatusVD are
AlwaysRead** (plain READ BINARY), so US1's mandatory payload is independent of the SM
channel; only US2 (GVD) depends on it — allowing the MVP to land before C2C/SM is
fully hardened.

**Open item**: confirm byte-exact DF.HCA AID (`D2 76 00 00 01 02`) from eGK ObjSys
§5.4 before coding the SELECT (all other CVC/EF facts are page-confirmed).

**Alternatives considered**: one-sided role check only (COS §15.1.3/§15.2) — valid for
the post-online-update case (VSDM-A_2572) but insufficient for a cold local read that
must reach AUT_VSD; deferred to a secondary path. Host-side ECDSA/ECKA re-implementation
(rejected — the cards already do it; duplicating it adds risk for no benefit).

---

## D5 — eGK VSDM files, payload handling, StatusVD conversion

**Decision**: Add DF.HCA + EF constants (`EF.PD` `D001`/SFID 1, `EF.VD` `D002`/SFID 2,
`EF.StatusVD` `D00C`/SFID 12, `EF.GVD` `D003`/SFID 3) to `GematikISO7816` and an
`EgkVsdmFile` enum. `EgkFileReader` generalises the existing multi-block `readBinaryFull`
loop. **PD/VD/GVD are returned byte-for-byte** (gzip-compressed on card) — **no
decompression, no XML parsing** (FR-005). Only `EF.StatusVD` is parsed, by
`StatusVdConverter` (Tab_FM_VSDM_21).

**Rationale**: `gemSpec_SST_PS_VSDM` requires the containers delivered gzip+Base64 and
**unmodified** (VSDM-A_2652/2691); since the card already stores them gzipped, the
konnektor reads raw bytes and Base64-encodes them — decompression would be wasted work
and a needless place for PII to live in memory (FR-028). EF.VD read honours the VD
start/end offsets so the transitional GVD copy in EF.VD is never read (VSDM-A_2784).

**Alternatives considered**: decompress + validate VSD XML against `Schema_VSD.xsd`
(rejected — spec says return unmodified and do not validate card content; adds PII
exposure and coupling for no functional gain).

---

## D6 — Local-only enforcement, timeout, privacy

**Decision**:
- **Reject** `PerformOnlineCheck=true` and `ReadOnlineReceipt=true` with VSDM SOAP
  faults *before* any card access (FR-007/FR-008). No UFS/VSDD/CMS/Intermediär code
  path exists at all (FR-006).
- **Hard timeout** `vsdm.read.timeout-ms` (MicroProfile Config, **default 30000**,
  aligned with MAXTIME_VSDM) bounds the whole operation; expiry aborts with a fault and
  releases the eGK reservation (FR-027). No p95 latency target on the card read itself.
- **Privacy**: decoded VSD / insured PII is never logged and never persisted; held only
  in the per-call `VsdReadResult`, GC-eligible after marshalling (FR-028). `ApduExecutor`
  logs only step labels + status words, never response bodies.
- **Concurrency**: a second call for the same eGK fails fast at `TucKon023ReserveCard`
  with a card-busy fault (FR-029); different cards run concurrently (FR-026).

**Rationale**: Directly encodes the four clarifications (2026-06-18). Config default is
conservative and overridable, matching `cetp-client-lib`'s config convention. The
constitution's 200 ms p95 cannot bind physical card I/O — documented exception.

**Alternatives considered**: silently downgrading online requests to local (rejected by
clarification — hides reduced behaviour); serialising same-eGK calls (rejected by
clarification — fail-fast is deterministic and simpler).

---

## Principle VII — External library evaluation (mandatory)

No new third-party dependency is introduced. Capabilities are met by already-adopted,
already-reviewed components:

| Capability | Library / component | Status | Notes |
|---|---|---|---|
| CVC TLV parsing | Bouncy Castle (`org.bouncycastle`, `crypto-lib`) | Already adopted | ASN.1/TLV + EAC structures; active, on Maven Central, no unpatched CVSS ≥ 7. |
| AES-CMAC + AES-CBC (Secure Messaging) | Bouncy Castle | Already adopted | SM wrap/unwrap of the EF.GVD READ. |
| ECDSA / ECKA verification (defence-in-depth only) | JDK JCA / Bouncy Castle | JDK / adopted | Primary verification is on-card; host use optional. |
| SOAP codegen + runtime | Apache CXF / Quarkiverse CXF | Already adopted (mandated) | `wsdl2java`; identical to other services. |
| Card transport | `CardReaderPort` (PCSC/SICCT) | Already adopted | Pre-existing SPI, two impls. |
| Config | MicroProfile Config | Already adopted | timeout property. |

**gemLibPki evaluated and rejected for CVC**: gemLibPki targets **X.509 TI-PKI**
(TUC_PKI_018), not **card-verifiable certificates** (CVC, ISO 7816 / EAC). It does not
cover the C2C CVC chain, so it is not applicable here. Bouncy Castle provides the
needed TLV/AES primitives.

**Outcome**: no new dependency → no SBOM change. Recorded for the Phase-1 Constitution
re-check.

---

## Principle VIII — Standard interface adoption (mandatory)

| Domain | Standard interface adopted | Bespoke alternative rejected |
|---|---|---|
| Signature / cert verification (host-side) | `java.security.Signature`, `java.security.cert.CertificateFactory` (JCA) | custom ECDSA |
| Symmetric crypto (SM) | JCA `Cipher` / `Mac` (via BC provider) | hand-rolled AES |
| Card transport | pre-existing `CardReaderPort` SPI (PCSC + SICCT impls) | new transport abstraction |
| SOAP endpoint | JAX-WS `VSDServicePortType` (generated) | hand-written servlet |
| Config | MicroProfile `@ConfigProperty` | bespoke config holder |

`ApduExecutor`, `EgkFileReader`, `ReadVsdService`, `CvcChainParser`,
`SecureMessagingSession`, `StatusVdConverter` are **concrete single-purpose classes**,
not new interfaces — there is no JDK/framework standard for "VSDM read orchestration",
so Principle VIII mandates no existing interface and Principle I is satisfied (no
single-impl abstraction introduced).

---

## Open items deferred to implementation (non-blocking)

- Byte-exact DF.HCA AID confirmation (D4) — trivial lookup, does not change the design.
- The one-sided post-update C2C variant (VSDM-A_2572) — only needed if the online flow
  is ever added; out of scope here, noted so the C2C class leaves a clean seam.
- Whether to surface the read timeout per-mandant vs. globally — global
  `@ConfigProperty` for now (matches existing modules); revisit if multi-tenant needs differ.
