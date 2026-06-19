# Feature Specification: VSDService — Local ReadVSD (Card-to-Card + eGK Read)

**Feature Branch**: `011-read-vsd`

**Created**: 2026-06-18

**Status**: Draft

**Input**: User description: "Implement the VSDService according to gemSpec_FM_VSDM_V2.9.0.pdf and gemSpec_SST_PS_VSDM_V1.5.0.pdf. Use the wsdl file in api-telematik/conn/vsds/VSDService.wsdl . Do not implement the communication with external systems like the UpdateFlagService (UFS) or the Card Management System (CMS). The VSDService should just do a card-to-card authentification and load the correct data from the egk and return it in the web service call."

## Overview

The konnektor's VSDM Fachmodul (Versichertenstammdatenmanagement) exposes the
SOAP interface `I_VSDService` with the operation **`ReadVSD`** to primary systems
(PVS / KIS / AVS). When a primary system calls `ReadVSD`, the konnektor reads the
insured person's master data (Versichertenstammdaten — VSD) directly from the
inserted electronic health card (eGK), after proving — by a card-to-card (C2C)
authentication between the eGK and the practitioner's HBA or institution's SMC-B —
that the reading party is authorised. The data is returned in the SOAP response.

This feature implements **only the local read path**: card-to-card authentication
and reading the VSD containers from the eGK. The online update path — talking to
the Update Flag Service (UFS), the Versichertendatendienst (VSDD) / Card Management
System (CMS), or any other external TI back-end — is explicitly **out of scope**.
Accordingly the service is a strictly local, read-only realisation of `ReadVSD`:
requests that ask for an online check (`PerformOnlineCheck=true`) or for a
Prüfungsnachweis (`ReadOnlineReceipt=true`) are rejected with a SOAP fault.

This realises the local-read parts of `gemSpec_FM_VSDM_V2.9.0` §3.2.1 ("VSD von
eGK lesen") and the PS interface of `gemSpec_SST_PS_VSDM_V1.5.0` (operation
`ReadVSD`, `VSDService.wsdl` / `VSDService.xsd`).

## Clarifications

### Session 2026-06-18

- Q: How should the service behave when `PerformOnlineCheck=true`, given UFS/CMS communication is out of scope? → A: Reject with a gematik SOAP fault — the service is strictly local-read-only and cannot perform an online check.
- Q: Is the Prüfungsnachweis (requested via `ReadOnlineReceipt=true`) in scope, given it is tied to the online check/update and needs per-Mandant VSDM-PNW-Key management? → A: Out of scope — reject `ReadOnlineReceipt=true` with a SOAP fault; the response never carries a Prüfungsnachweis.
- Q: What performance/timeout contract applies, given ReadVSD is dominated by physical smartcard I/O? → A: No fixed p95 latency target on the card read itself; a hard upper-bound timeout (default 30 s, aligned with MAXTIME_VSDM) aborts the operation with a fault. Konnektor-side processing overhead excluding card I/O stays within the constitution's API budget.
- Q: How must the konnektor treat the decoded VSD (sensitive insured-person PII) in memory and logs? → A: Decoded VSD / PII MUST NOT be logged or persisted; it is held only transiently in memory for the duration of the call. Card handles and error codes may be logged, never insured data.
- Q: What happens if a second ReadVSD for the same eGK arrives while the first still holds the exclusive card reservation? → A: The second call fails fast with a card-busy / reservation fault rather than blocking; the caller may retry.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Authenticated read of insured master data from the eGK (Priority: P1)

A primary system has an eGK and an SMC-B (or HBA) inserted, both already resolved
to card handles by preceding card operations. It calls `ReadVSD` with the eGK
handle (`EhcHandle`), the SMC-B/HBA handle (`HpcHandle`) and the call context. The
konnektor verifies that both cards may be used in this context, performs a
card-to-card authentication between the eGK and the SMC-B/HBA, reads the personal
insured data (PD), the general insurance data (VD) and the VSD status from the eGK,
and returns them in the SOAP response.

**Why this priority**: This is the core value of the feature and the minimum viable
slice. Without it, a primary system cannot obtain insured master data from the card
at all. PD, VD and the VSD status are the always-present payload of every successful
`ReadVSD`.

**Independent Test**: With a valid eGK and an authorised SMC-B resolved to handles,
call `ReadVSD` with `PerformOnlineCheck=false` and `ReadOnlineReceipt=false`, and
assert the response contains `PersoenlicheVersichertendaten`,
`AllgemeineVersicherungsdaten` and a populated `VSD_Status` (Status, Timestamp,
Version), with the container payloads gzip-compressed and Base64-encoded, and that
no call is made to any external TI service.

**Acceptance Scenarios**:

1. **Given** a valid, unblocked eGK and an authorised SMC-B (PIN.SMC enabled), both
   resolved to card handles, **When** `ReadVSD` is called with valid context,
   `PerformOnlineCheck=false` and `ReadOnlineReceipt=false`, **Then** the response
   carries `PersoenlicheVersichertendaten`, `AllgemeineVersicherungsdaten` and a
   populated `VSD_Status`, each container Base64-encoded and gzip-compressed, and no
   external TI service is contacted.
2. **Given** the same valid request, **When** the eGK's `EF.StatusVD` reports
   `Status='0'` (no open transactions / consistent VSD), **Then** the `VSD_Status`
   in the response reflects Status, the last-update Timestamp (converted to
   `dateTime`) and the Version (e.g. `7.3.1`).
3. **Given** an HBA is used instead of an SMC-B, **When** `ReadVSD` is called with a
   `UserId` present in the context and PIN.CH enabled, **Then** the read succeeds and
   the same mandatory containers are returned.
4. **Given** the VD container on the eGK still carries the transitional GVD copy in
   `EF.VD`, **When** VD is read, **Then** only the VD portion is returned (the
   protected-data copy embedded in `EF.VD` is not read or returned).

---

### User Story 2 - Protected insured data returned only when authorised (Priority: P2)

The protected insured data (GVD — GeschuetzteVersichertendaten) may only be read
once the eGK has been unlocked by a card-to-card authentication with an authorised
HBA/SMC-B. When the reading party is authorised, the GVD is included in the
response. When it is not, the protected data is omitted but the personal and general
data are still returned, so the caller always gets the unprotected VSD.

**Why this priority**: Protected data is part of a complete `ReadVSD`, but the
service must remain useful (returning PD + VD) even when GVD access is not granted.
This story makes the authorisation-dependent behaviour explicit and correct.

**Independent Test**: Call `ReadVSD` once with an authorised card that unlocks GVD
and assert `GeschuetzteVersichertendaten` is present; call again under a condition
where GVD cannot be read for lack of authorisation and assert
`GeschuetzteVersichertendaten` is absent while `PersoenlicheVersichertendaten` and
`AllgemeineVersicherungsdaten` are still present.

**Acceptance Scenarios**:

1. **Given** a successful card-to-card authentication that authorises protected-data
   access, **When** `ReadVSD` runs, **Then** the response additionally contains
   `GeschuetzteVersichertendaten` (Base64-encoded, gzip-compressed).
2. **Given** the protected data cannot be read because the reading party is not
   authorised, **When** `ReadVSD` runs, **Then** `GeschuetzteVersichertendaten` is
   omitted from the response and the operation still returns PD, VD and VSD_Status
   without raising a fault.
3. **Given** the protected data was read, **When** the operation completes, **Then**
   a data-access audit entry for "reading the protected VSD" is recorded on the eGK
   identifying the actor from the SMC-B/HBA authentication certificate.

---

### User Story 3 - Local-only contract is enforced (Priority: P2)

Because online communication with UFS/CMS is not implemented, the service must make
its local-only nature explicit. Requests that ask for an online check or for a
Prüfungsnachweis are rejected with a clear gematik SOAP fault rather than silently
doing less than asked, so the calling primary system can react deterministically.

**Why this priority**: Without enforced boundaries a primary system could believe an
online check happened when it did not — a correctness and patient-safety risk. The
rejection contract must be in place alongside the core read.

**Independent Test**: Call `ReadVSD` with `PerformOnlineCheck=true` and assert a
SOAP fault is returned and no VSD is read; call `ReadVSD` with
`ReadOnlineReceipt=true` and assert a SOAP fault is returned; call with both flags
`false` and assert the local read proceeds normally.

**Acceptance Scenarios**:

1. **Given** any otherwise valid request, **When** `PerformOnlineCheck=true`,
   **Then** the service returns a gematik SOAP fault indicating the online check is
   not supported and reads no VSD.
2. **Given** any otherwise valid request, **When** `ReadOnlineReceipt=true`, **Then**
   the service returns a gematik SOAP fault indicating the Prüfungsnachweis is not
   supported, and the response never contains a `Pruefungsnachweis`.
3. **Given** both `PerformOnlineCheck=false` and `ReadOnlineReceipt=false`, **When**
   the request is otherwise valid, **Then** the local read proceeds and no
   `Pruefungsnachweis` element is present in the response.

---

### User Story 4 - Card and data problems produce the correct gematik fault (Priority: P3)

When the cards or the card data prevent a correct read, the service aborts and
returns a standard gematik SOAP fault with the specification-mandated error code, so
the primary system can guide the user (e.g. prompt for a PIN, replace a card, or
re-read). No security-relevant detail (such as a stack trace) is exposed.

**Why this priority**: Robust, specification-conformant error reporting is required
for certification and for safe operation, but the system already delivers value
(P1–P3) before every error path is exhaustively handled.

**Independent Test**: Drive each failure condition (PIN not enabled, inconsistent
VSD, container read failure, blocked health application, invalid eGK certificate,
unsupported card generation) and assert the corresponding fault code is returned as
a gematik SOAP fault with Severity `Fatal`, a single Trace, an EventID and a
LogReference, and no stack trace.

**Acceptance Scenarios**:

1. **Given** the SMC-B security state is insufficient (PIN.SMC not enabled), **When**
   `ReadVSD` reaches card-to-card authentication, **Then** it aborts with error code
   **3041** and the fault detail identifies the affected SMC-B card handle.
2. **Given** the HBA security state is insufficient (PIN.CH not enabled), **When**
   `ReadVSD` reaches card-to-card authentication, **Then** it aborts with error code
   **3042**.
3. **Given** `EF.StatusVD` reports `Status='1'` (VSD inconsistent / transactions
   open), **When** the status is read, **Then** the operation aborts with error code
   **3001** (VSD not consistent).
4. **Given** a VSD container (PD, VD or GVD) cannot be read from the eGK, **When**
   the read fails, **Then** the operation aborts with error code **3011** and the
   detail text identifies the affected container.
5. **Given** the eGK health application (DF.HCA) is blocked, **When** `ReadVSD` runs,
   **Then** it returns the gematik fault for a blocked health application (code
   **114**) and reads no VSD.
6. **Given** the eGK authentication certificate is invalid (offline-invalid or
   online-revoked), **When** validity is checked, **Then** the operation returns the
   corresponding gematik fault (code **107** offline-invalid, code **106**
   online-revoked) and reads no VSD.
7. **Given** the inserted eGK is of an unsupported generation (older than G1-plus),
   **When** the card type is determined, **Then** the operation aborts with a fault.
8. **Given** the data-access audit entry cannot be written to the eGK, **When** the
   write fails, **Then** the operation aborts and returns no VSD.
9. **Given** the card does not respond, **When** the configured hard timeout
   (default 30 s) elapses, **Then** the operation aborts with a gematik SOAP fault and
   the eGK reservation is released.
10. **Given** an eGK is already exclusively reserved by an in-flight `ReadVSD`,
    **When** a second `ReadVSD` for the same eGK arrives, **Then** it fails fast with a
    card-busy / reservation fault and does not block.

---

### Edge Cases

- **GVD-only authorisation failure**: protected data unreadable for lack of
  authorisation is *not* fatal — PD and VD are returned, GVD omitted (US2).
- **Transitional GVD copy in EF.VD**: the legacy GVD copy embedded in `EF.VD` must
  never be read or returned; VD reading honours the VD start/end offsets.
- **eGK removed mid-operation**: a card error during a read aborts the operation with
  a card-read fault (3011) or the relevant card-session fault.
- **HBA used without UserId in context**: a context that omits the required `UserId`
  for an HBA-based call is rejected by context/authorisation validation.
- **Wrong card type as HpcHandle/EhcHandle**: a handle that does not reference the
  expected card type (eGK for `EhcHandle`, HBA/SMC-B for `HpcHandle`) is rejected.
- **Unknown VSD storage-structure version**: an `EF.StatusVD` whose
  storage-structure version is unknown aborts the operation with a fault.
- **VSD container content**: the eGK's VSD XML is returned unmodified and is not
  validated against its content schema by this service.
- **Both cards must be inserted**: the eGK and the HBA/SMC-B must be inserted and
  functional and already resolved to handles before `ReadVSD`; otherwise the call is
  rejected with a card-session fault.
- **Card unresponsive / read hangs**: if the operation does not complete within the
  configured hard timeout (default 30 s), it aborts with a fault and releases the eGK
  reservation rather than blocking indefinitely.
- **Concurrent read of the same eGK**: a second `ReadVSD` for an eGK already
  exclusively reserved by an in-flight call fails fast with a card-busy fault; it does
  not wait for the first to finish.

## Requirements *(mandatory)*

### Functional Requirements

#### SOAP interface & contract

- **FR-001**: The system MUST implement the `I_VSDService` interface with the single
  operation `ReadVSD` exactly as defined by `api-telematik/conn/vsds/VSDService.wsdl`
  and `VSDService.xsd` (wrapped document/literal SOAP over HTTP). *(VSDM-A_2633,
  VSDM-A_2596)*
- **FR-002**: The system MUST accept the `ReadVSD` input parameters `EhcHandle`,
  `HpcHandle`, `PerformOnlineCheck`, `ReadOnlineReceipt` and `Context`
  (MandantId, ClientSystemId, WorkplaceId, UserId), with `MandantId`,
  `ClientSystemId` and `WorkplaceId` always required and `UserId` required when the
  `HpcHandle` references an HBA. *(VSDM-A_2693)*
- **FR-003**: The system MUST validate every incoming request against the WSDL/XSD
  schema and against the permissible values of each element needed for processing,
  rejecting invalid messages with a fault. *(VSDM-A_2675, VSDM-A_2689, VSDM-A_2703)*
- **FR-004**: On success the system MUST return a `ReadVSDResponse` always containing
  `PersoenlicheVersichertendaten`, `AllgemeineVersicherungsdaten` and `VSD_Status`;
  `GeschuetzteVersichertendaten` MUST be present only when protected-data access is
  authorised; `Pruefungsnachweis` MUST never be present (out of scope). *(VSDM-A_2634,
  VSDM-A_2647)*
- **FR-005**: The system MUST return the VSD container payloads (PD, VD, GVD)
  gzip-compressed and Base64-encoded, byte-for-byte as read from the eGK, without
  modifying or content-validating the container XML. *(VSDM-A_2652, VSDM-A_2691)*

#### Local-only enforcement (no UFS/CMS)

- **FR-006**: The system MUST NOT communicate with any external TI back-end service
  (Update Flag Service / UFS, Versichertendatendienst / VSDD, Card Management System
  / CMS, Intermediär-VSDM, or any DNS-SD service localisation). The complete operation
  MUST be satisfiable from the local konnektor and the inserted cards alone.
- **FR-007**: The system MUST reject a request with `PerformOnlineCheck=true` by
  returning a gematik SOAP fault stating that the online check is not supported, and
  MUST read no VSD for such a request.
- **FR-008**: The system MUST reject a request with `ReadOnlineReceipt=true` by
  returning a gematik SOAP fault stating that the Prüfungsnachweis is not supported.
- **FR-009**: The system MUST never generate, read, decrypt or return a
  Prüfungsnachweis, and MUST NOT require any Prüfungsnachweis key material
  (VSDM-PNW-Key) to operate.

#### Access authorisation & context

- **FR-010**: Before reading, the system MUST verify that both the HBA/SMC-B
  (`HpcHandle`) and the eGK (`EhcHandle`) may be used in the supplied call context
  (MandantId / ClientSystemId / WorkplaceId / UserId). A failed authorisation check
  MUST abort the operation with a fault. *(TUC_KON_000, VSDM-A_2693)*

#### Card-to-card authentication

- **FR-011**: The system MUST perform a card-to-card authentication between the eGK
  (`EhcHandle`) and the HBA/SMC-B (`HpcHandle`) to unlock the eGK for reading the
  protected data (GVD) and for writing the data-access audit log. *(VSDM-A_2572,
  VSDM-A_2573, VSDM-A_2662, TUC_KON_005)*
- **FR-012**: Before card-to-card authentication, the system MUST verify the
  HBA/SMC-B PIN security state (PIN.SMC for an SMC-B, PIN.CH for an HBA). If the
  security state is insufficient the operation MUST abort with error code **3041**
  (SMC-B) or **3042** (HBA), the fault detail identifying the affected card handle.
  *(VSDM-A_2572, TUC_KON_022)*
- **FR-013**: The system MUST record, for the data-access audit, the acting party
  identity (actor name / ICCSN) extracted from the subject of the SMC-B/HBA
  authentication certificate used for the card-to-card authentication. *(VSDM-A_2586,
  VSDM-A_2587, TUC_KON_034)*

#### eGK validity & card session

- **FR-014**: The system MUST reserve the eGK card session for the duration of the
  read and release the reservation when the operation completes or aborts.
  *(TUC_KON_023)*
- **FR-015**: The system MUST verify the eGK is usable and valid before reading:
  the eGK health application (DF.HCA) MUST NOT be blocked, and the eGK authentication
  certificate MUST be valid. A blocked health application MUST yield gematik fault
  **114**; an offline-invalid certificate MUST yield **107**; an online-revoked
  certificate MUST yield **106**. In each case no VSD is returned. *(TUC_KON_018)*
- **FR-016**: The system MUST support only eGK generations G1-plus, G2 and G2.1; an
  unsupported (older) generation MUST abort the operation with a fault. *(VSDM-A_2607,
  VSDM-A_2687)*

#### Reading the VSD containers

- **FR-017**: The system MUST read the VSD status container (`EF.StatusVD`) first. If
  it reports `Status='1'` (VSD inconsistent / transactions open) the operation MUST
  abort with error code **3001**. *(VSDM-A_2660)*
- **FR-018**: The system MUST convert the `EF.StatusVD` contents into the
  `VSD_Status` response structure: the update status, the last-update Timestamp
  rendered as `dateTime`, and the schema Version rendered as a dotted version (e.g.
  `7.3.1`). *(VSDM-A_2708, VSDM-A_3063)*
- **FR-019**: The system MUST read the personal insured data (`EF.PD`) and the
  general insurance data (`EF.VD`) from the eGK; these are always returned on success.
  *(VSDM-A_2567, VSDM-A_2568, VSDM-A_2569, VSDM-A_2570, VSDM-A_2571, TUC_KON_202)*
- **FR-020**: When reading VD, the system MUST honour the VD start/end offsets so
  that only the VD portion is read, and MUST NOT read the transitional GVD copy stored
  inside `EF.VD`. *(VSDM-A_2784)*
- **FR-021**: The system MUST read the protected insured data (`EF.GVD`) only when
  the card-to-card authentication has authorised protected-data access. If GVD cannot
  be read for lack of authorisation, the system MUST still return PD, VD and
  VSD_Status without raising a fault. *(VSDM-A_2574, VSDM-A_2647)*
- **FR-022**: If reading any VSD container (PD, VD or GVD) fails technically, the
  system MUST abort with error code **3011**, the detail text identifying the affected
  container. *(VSDM-A_2660)*

#### Audit logging on the eGK

- **FR-023**: The system MUST write the data-access audit entries to the eGK
  (including an entry for reading the protected VSD when GVD was read) before
  returning the VSD. If the audit log cannot be written, the operation MUST abort and
  MUST NOT return any VSD. *(VSDM-A_2586, VSDM-A_2587, VSDM-A_2654, TUC_KON_006)*

#### Error reporting

- **FR-024**: Every aborting error MUST be returned as a gematik SOAP fault carrying
  the `Error` structure with Severity `Fatal`, a unique EventID, a LogReference, and
  exactly one Trace element. *(VSDM-A_2682)*
- **FR-025**: A SOAP fault MUST NOT contain security-relevant information such as
  stack traces or internal implementation detail. *(VSDM-A_2682)*
- **FR-026**: The system MUST support multiple concurrent `ReadVSD` calls (e.g. for
  different cards / contexts) without cross-talk between calls. *(VSDM-A_2633,
  VSDM-A_2565)*

#### Performance, concurrency & data protection

- **FR-027**: The system MUST bound the total `ReadVSD` operation by a hard timeout
  (default 30 seconds, aligned with MAXTIME_VSDM, administrator-configurable); on
  expiry it MUST abort with a gematik SOAP fault and release the eGK reservation. No
  fixed p95 latency target applies to the card-bound read itself (it is hardware-bound
  by smartcard I/O); konnektor-side processing overhead excluding card I/O MUST remain
  within the constitution's API-latency budget. *(VSDM-A_2998)*
- **FR-028**: The system MUST NOT write decoded VSD payloads or any insured-person PII
  to logs, and MUST NOT persist them; decoded VSD MUST be held only in memory for the
  duration of the call and released afterwards. Card handles and error codes MAY be
  logged; insured data MUST NOT.
- **FR-029**: When a `ReadVSD` call cannot reserve the eGK because another call already
  holds the exclusive reservation, the system MUST fail fast with a card-busy /
  reservation fault rather than blocking; the caller MAY retry. *(TUC_KON_023)*

### Key Entities *(include if feature involves data)*

- **ReadVSD Request**: The inbound SOAP request. Attributes: `EhcHandle` (eGK card
  handle), `HpcHandle` (HBA or SMC-B card handle), `PerformOnlineCheck` (must be
  false), `ReadOnlineReceipt` (must be false), and `Context` (MandantId,
  ClientSystemId, WorkplaceId, optional UserId).
- **ReadVSD Response**: The outbound SOAP response. Always carries
  `PersoenlicheVersichertendaten`, `AllgemeineVersicherungsdaten` and `VSD_Status`;
  optionally `GeschuetzteVersichertendaten`; never `Pruefungsnachweis`.
- **VSD Container (PD / VD / GVD)**: A gzip-compressed, Base64-encoded XML data block
  read from an eGK elementary file (`EF.PD`, `EF.VD`, `EF.GVD`) in MF/DF.HCA. PD and
  VD are unprotected; GVD requires card-to-card unlock.
- **VSD_Status**: Status of the VSD on the card — `Status` (`0` = consistent / no open
  transactions, `1` = inconsistent), `Timestamp` (last update, as `dateTime`),
  `Version` (e.g. `7.3.1`). Derived from `EF.StatusVD`.
- **Card Handle**: A konnektor reference to an inserted card resolved by preceding
  operations — `EhcHandle` → eGK, `HpcHandle` → HBA or SMC-B.
- **Call Context**: The caller's mandant / client-system / workplace / user identity,
  used to authorise card use.
- **Error / Fault**: A gematik error structure returned in a SOAP fault (Severity
  `Fatal`, EventID, LogReference, single Trace), carrying one of the VSDM error codes
  (3001, 3011, 3041, 3042) or a general konnektor/OM code (106, 107, 114).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a valid, unblocked eGK and an authorised HBA/SMC-B, a local
  `ReadVSD` returns `PersoenlicheVersichertendaten`, `AllgemeineVersicherungsdaten`
  and a populated `VSD_Status` in 100% of attempts where the card is physically
  readable.
- **SC-002**: When protected-data access is authorised, `GeschuetzteVersichertendaten`
  is present in 100% of successful reads; when it is not authorised, it is absent in
  100% of reads while PD and VD remain present — i.e. an unauthorised GVD never causes
  the operation to fail.
- **SC-003**: 100% of requests with `PerformOnlineCheck=true` and 100% of requests
  with `ReadOnlineReceipt=true` are rejected with a gematik SOAP fault, and no VSD is
  read for a rejected request.
- **SC-004**: Across an end-to-end run, the number of network connections opened to
  any external TI service (UFS, VSDD/CMS, Intermediär) is exactly zero.
- **SC-005**: Each defined failure condition maps to the correct gematik fault code —
  PIN-not-enabled → 3041/3042, inconsistent VSD → 3001, container read failure →
  3011, blocked health application → 114, invalid eGK certificate → 107/106 — in 100%
  of the corresponding test cases, with no fault exposing a stack trace.
- **SC-006**: No response ever contains a `Pruefungsnachweis` element.
- **SC-007**: Every implemented gematik requirement (Afo) referenced by this feature
  has at least one automated test whose name includes the Afo ID, and unit-test
  coverage for the feature's modules is at or above 80%.
- **SC-008**: When the card does not respond, `ReadVSD` aborts within the configured
  hard timeout (default 30 s), returns a gematik SOAP fault and releases the eGK
  reservation, in 100% of timeout test cases — it never blocks indefinitely.
- **SC-009**: Across the full test suite, no log output and no persisted store
  contains a decoded VSD payload or insured-person PII (verified by scanning logs and
  storage).
- **SC-010**: A second concurrent `ReadVSD` for an already-reserved eGK fails fast
  with a card-busy fault in 100% of cases, with no indefinite blocking of the caller.

## Assumptions

- The eGK and the HBA/SMC-B are inserted, functional and already resolved to card
  handles by preceding card operations; resolving handles and inserting cards is not
  part of this feature.
- The konnektor's existing card-session / card-management, context-validation,
  card-to-card-authentication and file-read/write building blocks (the
  `TUC_KON_*` functions) are available for reuse; this feature orchestrates them and
  does not re-implement card communication from scratch.
- Raising a card's PIN security state (`VerifyPin` for PIN.SMC / PIN.CH) is performed
  by the primary system through the konnektor's existing interface, not by this
  feature; this feature only checks the security state and reports 3041/3042 when it
  is insufficient.
- `api-telematik/conn/vsds/VSDService.wsdl` and `VSDService.xsd` are the authoritative
  contract for the operation; the generated service interface is treated as the
  externally mandated contract.
- The eGK's VSD container XML is returned exactly as stored on the card; this service
  does not parse or validate the insured-data XML content.
- The online update path (UFS `GetUpdateFlags`, VSDD/CMS updates, Intermediär
  localisation, `AutoUpdateVSD`, `ReadVSDAdV`) and the sibling KVK service
  (`I_KVKService` / `ReadKVK`) are out of scope.
- The Prüfungsnachweis and its per-Mandant VSDM-PNW-Key configuration are out of
  scope; no symmetric key management is introduced by this feature.
- Card-level details of the card-to-card authentication (CV certificate chains, key
  references) are defined by the eGK object-system specifications and are encapsulated
  behind the existing card-to-card-authentication building block.
