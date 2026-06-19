# Quickstart: VSDService — Local ReadVSD

**Feature**: 011-read-vsd | **Date**: 2026-06-18

This is a validation/run guide proving the local `ReadVSD` works end-to-end.
Implementation details live in `plan.md`, `data-model.md`, and `contracts/`.

## Prerequisites

- JDK 21, Maven (reactor builds with the wrapper / `mvn`).
- A reachable card source: either a real PC/SC or SICCT card terminal with an eGK and
  an SMC-B inserted, **or** the in-process fake card backend used by the tests.
- The cards already resolved to handles (card session established by preceding
  operations) — `ReadVSD` does not insert or pair cards.

## 1. Generate the SOAP stubs

Add the `<wsdlOption>` for `conn/vsds/VSDService.wsdl` to `api-telematik/pom.xml`, then:

```bash
mvn -pl api-telematik -am install
# verify the generated port type exists:
find api-telematik/target/generated-sources -path '*vsds*' -name 'VSDServicePortType.java'
```

Expected: `…/de/gematik/ws/conn/vsds/vsdservice/v5_2/VSDServicePortType.java` is
generated, alongside `ReadVSD`, `ReadVSDResponse`, `VSDStatusType`, `FaultMessage`.

## 2. Build the feature modules

```bash
mvn -pl apdu-lib,konnektor-soap-server -am install
```

## 3. Run the unit + integration tests

```bash
# card-level unit tests (APDU executor, StatusVD conversion, C2C chain, orchestration)
mvn -pl apdu-lib test

# first @QuarkusTest in konnektor-soap-server: ReadVSD over the CXF endpoint
mvn -pl konnektor-soap-server test
```

Expected: Afo-named tests pass (`test_VSDM_A_2567_*`, `test_VSDM_A_2572_*`,
`test_VSDM_A_2660_*`, `test_VSDM_A_2647_*`, …); coverage ≥ 80% for the new packages.

## 4. Run the konnektor and call ReadVSD

```bash
mvn -pl quarkus-server quarkus:dev
```

The endpoint is published at `http://localhost:8080/ws/conn/VSDService` (WSDL at
`?wsdl`). Send a `ReadVSD` request with `PerformOnlineCheck=false`,
`ReadOnlineReceipt=false`, the eGK + SMC-B handles, and a full `Context`.

### Expected outcomes

| Scenario | Expected result |
|---|---|
| Valid eGK + authorised SMC-B, both flags `false` | `ReadVSDResponse` with `PersoenlicheVersichertendaten`, `AllgemeineVersicherungsdaten`, populated `VSD_Status`; `GeschuetzteVersichertendaten` present if C2C authorised; **no** `Pruefungsnachweis`. (US1/US2) |
| `PerformOnlineCheck=true` | SOAP fault (online check unsupported); no VSD read. (US3, FR-007) |
| `ReadOnlineReceipt=true` | SOAP fault (Prüfungsnachweis unsupported). (US3, FR-008) |
| SMC-B PIN not enabled | SOAP fault code **3041**. (US4, FR-012) |
| HBA PIN not enabled | SOAP fault code **3042**. (US4) |
| EF.StatusVD `Status='1'` | SOAP fault code **3001**. (US4, FR-017) |
| Container read fails | SOAP fault code **3011**. (US4, FR-022) |
| eGK DF.HCA blocked | SOAP fault code **114**. (US4, FR-015) |
| Second concurrent call for the same eGK | card-busy fault, fails fast. (FR-029) |
| Card unresponsive | aborts within ~30 s timeout, reservation released. (FR-027) |

## 5. Confirm the local-only & privacy guarantees

```bash
# No external TI connections were opened (SC-004): inspect logs / netstat during a call.
# No insured PII in logs (SC-009): grep the run log — only card handles + codes appear,
# never decoded VSD content.
grep -iE 'versicherten|kvnr|geburts|PersoenlicheVersicherten' <run.log>   # expect: no matches
```
