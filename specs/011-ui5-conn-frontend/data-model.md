# Phase 1 Data Model: UI5 Konnektor SOAP Frontend

This feature has **no persistent storage**. The "data model" is the set of client-side
entities (mostly static configuration and transient per-request state) plus the schema of
the one server-provided config payload. Nothing here is written to a database; sensitive
request data exists only for the lifetime of the active call (FR-016).

## Entities

### Service (static, from `catalog.json`)
The konnektor SOAP service represented by one home tile.

| Field | Type | Notes |
|---|---|---|
| `id` | string | Stable key, e.g. `card`, `event`, `signature` |
| `title` | string | Human-readable label (TI terminology) |
| `icon` | string | UI5 icon URI for the tile |
| `endpoint` | string | Path under the host, e.g. `/ws/conn/CardService` |
| `namespace` | string | Target namespace, e.g. `http://ws.gematik.de/conn/CardService/WSDL/v8.1` |
| `listOperationId` | string \| null | Operation whose result fills the master list; `null` → permanent placeholder pane (Encryption, Signature, Auth Signature) |
| `operations` | Operation[] | Operations this service exposes |

**Validation**: `id` unique; `endpoint` must start with `/ws/conn/`; if `listOperationId` set it must reference an `operations[].id` with `isRead = true`.

### Operation (static, from `catalog.json`)
A single SOAP action and everything needed to render and send it.

| Field | Type | Notes |
|---|---|---|
| `id` | string | Unique within the service, e.g. `verifyPin` |
| `title` | string | Action button label |
| `soapAction` | string | `SOAPAction` HTTP header value |
| `envelopeTemplate` | string | Full `<soap:Envelope>` skeleton with `{{context.*}}` and field placeholders |
| `fields` | Field[] | Form field map |
| `isRead` | boolean | True if it returns entities to list (no mutation) |
| `mutatesEntity` | boolean | True → refresh the service's master list on success |
| `rowMapping` | RowMapping \| null | For read ops: how to project response nodes into list rows |

**Validation**: `isRead` and `mutatesEntity` are mutually exclusive; `envelopeTemplate` must be well-formed XML; every `fields[].path` must resolve within the template.

### Field (static, part of Operation)
One form input bound to a node in the envelope.

| Field | Type | Notes |
|---|---|---|
| `label` | string | i18n key / display label |
| `path` | string | XML binding path into the envelope (the node the form control edits) |
| `type` | enum | `text` \| `password` \| `number` \| `boolean` \| `base64` \| `enum` |
| `required` | boolean | Drives pre-send validation (FR-014) |
| `sensitive` | boolean | `true` → masked, never logged/persisted (FR-016); implies `type=password` for PINs |
| `enumValues` | string[] \| null | For `type=enum` |

### InvocationContext (transient, defaults from server)
The `ContextType` block injected into each envelope.

| Field | Type | Notes |
|---|---|---|
| `mandantId` | string | Pre-filled from config, user-overridable |
| `clientSystemId` | string | " |
| `workplaceId` | string | " |
| `userId` | string \| null | Optional |

**Source**: `GET /conn-ui/config.json` at startup (R7). **Lifecycle**: held in a client model; edits apply per request only.

### RequestEnvelope (transient, per dialog)
The single editable representation shared by the form and raw views.

| Field | Type | Notes |
|---|---|---|
| `operationId` | string | Which operation this envelope is for |
| `document` | XML DOM (in `XMLModel`) | Full SOAP envelope; the one source of truth |
| `isValid` | boolean | Result of the last `DOMParser` parse (FR-013/SC-007) |
| `viewMode` | enum | `form` \| `raw` — active editor |

**State transitions**:
```
[Closed] --open action--> [Form] <--toggle--> [Raw]
[Form]/[Raw] --invalid edit--> [Invalid] --(must fix)--> back to last valid mode
[Form]/[Raw] --submit (valid)--> [Submitting] --response--> [ShowResult]
                                              --fault/error--> [ShowFault] (entered data preserved, FR-011)
[ShowResult] --(mutatesEntity)--> refresh master list (FR-012)
```

### ServiceResponse / Fault (transient)
Outcome of a submitted operation.

| Field | Type | Notes |
|---|---|---|
| `httpStatus` | number | 200 success; 500 → inspect for `soap:Fault` |
| `rawXml` | string | Always inspectable (FR-010) |
| `parsed` | XML DOM | For success rendering / row extraction |
| `fault` | { code, message, detail } \| null | Populated on `soap:Fault` (FR-011) |

### Service Main Entity (transient list rows)
Rows shown in the FlexibleColumnLayout master list, produced by `rowMapping` from a read operation's response (e.g., cards from EventService `GetCards`, certificates from CertificateService `ReadCardCertificate`). Shape is per service; not persisted (FR-003).

## Config payload schema (`GET /conn-ui/config.json`)
```json
{
  "defaultContext": {
    "mandantId": "string",
    "clientSystemId": "string",
    "workplaceId": "string",
    "userId": "string|null"
  }
}
```
Contains **no secret material** (Principle V). See `contracts/ui-config-endpoint.md`.
