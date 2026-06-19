# Contract: ReadVSD SOAP operation

**Interface**: `I_VSDService` / `VSDServicePortType.readVSD`
**WSDL**: `api-telematik/conn/vsds/VSDService.wsdl` · **XSD**: `…/VSDService.xsd`
**Namespace**: `http://ws.gematik.de/conn/vsds/VSDService/v5.2`
**Generated package**: `de.gematik.ws.conn.vsds.vsdservice.v5_2`
**Endpoint**: `@CXFEndpoint("/conn/VSDService")` → served at `/ws/conn/VSDService`
**Style**: document/literal wrapped, SOAP 1.1 over HTTP

## Request — `ReadVSD`

| Element | Type | Rule |
|---|---|---|
| `EhcHandle` | string | Card handle of the eGK. Resolved via `CmCardList.findByHandle`; must reference an eGK. |
| `HpcHandle` | string | Card handle of the HBA or SMC-B used for C2C. |
| `PerformOnlineCheck` | boolean | **MUST be `false`.** `true` → fault (online check unsupported — FR-007). |
| `ReadOnlineReceipt` | boolean | **MUST be `false`.** `true` → fault (Prüfungsnachweis unsupported — FR-008). |
| `Context` | `ContextType` | `MandantId`, `ClientSystemId`, `WorkplaceId` required; `UserId` required iff `HpcHandle`→HBA (FR-002, VSDM-A_2693). |

## Response — `ReadVSDResponse`

| Element | Type | Presence |
|---|---|---|
| `PersoenlicheVersichertendaten` | base64Binary | **always** — raw EF.PD bytes, Base64 of the gzip-compressed card content |
| `AllgemeineVersicherungsdaten` | base64Binary | **always** — raw EF.VD bytes |
| `GeschuetzteVersichertendaten` | base64Binary | **only when** C2C authorised GVD; omitted otherwise (FR-021) |
| `VSD_Status` | `VSD_StatusType` | **always** — Status `[01]`, Timestamp `dateTime`, Version `\d{1,3}\.\d{1,3}\.\d{1,4}` |
| `Pruefungsnachweis` | base64Binary | **never** set by this feature (out of scope) |

The three container payloads are returned **byte-for-byte** as read from the card
(gzip-compressed on the eGK) — no decompression, no XML validation (FR-005,
VSDM-A_2652/2691).

## Faults — `FaultMessage` (carries `de.gematik.ws.tel.error.v2.Error`)

All faults: Severity `Fatal`, one `Trace`, an EventID + LogReference, **no stack
traces / no PII** (FR-024/FR-025, VSDM-A_2682). Code mapping:

| Condition | Code | Source |
|---|---|---|
| `PerformOnlineCheck=true` | (reject) | FR-007 — online check unsupported |
| `ReadOnlineReceipt=true` | (reject) | FR-008 — receipt unsupported |
| SMC-B PIN.SMC security state insufficient | **3041** | FR-012 |
| HBA PIN.CH security state insufficient | **3042** | FR-012 |
| EF.StatusVD `Status='1'` (inconsistent) | **3001** | FR-017, VSDM-A_2660 |
| Reading a VSD container (PD/VD/GVD) failed | **3011** | FR-022 |
| eGK health application (DF.HCA) blocked | **114** | FR-015 (gemSpec_OM) |
| eGK AUT certificate offline-invalid | **107** | FR-015 |
| eGK AUT certificate online-revoked | **106** | FR-015 |
| eGK reserved by another in-flight call | (card-busy) | FR-029 |
| Operation exceeds hard timeout (default 30 s) | (timeout) | FR-027 |

> Note: the existing `KonnektorServiceHelper.buildError` hardcodes code `4001`; a new
> `VsdmFaultFactory` parameterises the VSDM-specific codes above.

## Behavioural guarantees (acceptance-linked)

- Success returns PD+VD+VSD_Status always; GVD conditionally; never PN. (US1, US2)
- No external TI service is contacted for any request (FR-006 / SC-004).
- GVD-authorisation failure is **not** a fault — PD+VD+Status still returned (US2, FR-021).
