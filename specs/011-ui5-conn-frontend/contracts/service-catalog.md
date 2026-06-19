# Contract: Service Catalog (`catalog/catalog.json`)

The catalog is the static, design-time description that drives the entire SPA. Adding or
changing a service/operation is a catalog edit, not a code change (SC-003). It is bundled
with the app (not server-provided) because the service set is fixed at build time.

## Top-level shape
```json
{
  "services": [ Service, ... ]
}
```

## Service object
```json
{
  "id": "card",
  "title": "Card Service",
  "icon": "sap-icon://it-system",
  "endpoint": "/ws/conn/CardService",
  "namespace": "http://ws.gematik.de/conn/CardService/WSDL/v8.1",
  "listOperationId": null,
  "operations": [ Operation, ... ]
}
```
Rules: `id` unique; `endpoint` MUST start with `/ws/conn/`; `listOperationId` (if non-null)
MUST reference an operation with `isRead: true`. `listOperationId: null` ⇒ permanent
placeholder pane (Encryption, Signature, Auth Signature).

## Operation object
```json
{
  "id": "verifyPin",
  "title": "Verify PIN",
  "soapAction": "http://ws.gematik.de/conn/CardService/v8.1#VerifyPin",
  "envelopeTemplate": "<soap:Envelope ...>...</soap:Envelope>",
  "isRead": false,
  "mutatesEntity": false,
  "rowMapping": null,
  "fields": [ Field, ... ]
}
```
Rules: `isRead` XOR `mutatesEntity` (never both true); `envelopeTemplate` MUST be
well-formed XML containing a `ContextType` block with `{{context.mandantId}}`,
`{{context.clientSystemId}}`, `{{context.workplaceId}}`, optional `{{context.userId}}`
placeholders; every `fields[].path` MUST resolve to a node in the template.

## Field object
```json
{
  "label": "card.verifyPin.pinRef",
  "path": "/soap:Envelope/soap:Body/CardService:VerifyPin/CardService:PinTyp",
  "type": "enum",
  "required": true,
  "sensitive": false,
  "enumValues": ["PIN.CH", "PIN.SMC"]
}
```
`type` ∈ `text|password|number|boolean|base64|enum`. `sensitive: true` ⇒ masked input,
excluded from any logging (FR-016). `path` is the binding path used by both the form
control and the XMLModel.

## rowMapping (read operations only)
```json
{
  "rowsPath": "//CardService:Cards/CardService:Card",
  "columns": [
    { "label": "ICCSN", "path": "CardService:Iccsn" },
    { "label": "Type",  "path": "CardService:CardType" },
    { "label": "Handle","path": "CardService:CardHandle" }
  ]
}
```
Used to project a read operation's response into FlexibleColumnLayout master-list rows (FR-003).

## Catalog coverage (must be present)
| Service | endpoint | operations |
|---|---|---|
| Card Service (v8.1) | `/ws/conn/CardService` | verifyPin, changePin, unblockPin, getPinStatus, authorizeSMC |
| Card Terminal Service (v1.1) | `/ws/conn/CardTerminalService` | requestCard, ejectCard |
| Certificate Service (v6.0) | `/ws/conn/CertificateService` | readCardCertificate (read), checkCertificateExpiration, verifyCertificate |
| Event Service (v7.2) | `/ws/conn/EventService` | getCards (read), getCardTerminals, getResourceInformation, subscribe, unsubscribe, getSubscription, renewSubscriptions |
| Encryption Service | `/ws/conn/EncryptionService` | encryptDocument, decryptDocument (no list op) |
| Signature Service (v7.5) | `/ws/conn/SignatureService` | signDocument, verifyDocument, getJobNumber, stopSignature, getSignatureMode, activateComfortSignature, deactivateComfortSignature (no list op) |
| Auth Signature Service (v7.4) | `/ws/conn/AuthSignatureService` | externalAuthenticate (no list op) |

Templates/field maps are authored from the gematik conn WSDLs/XSDs in the `api-telematik`
submodule (`ebk_6.0.3`).
