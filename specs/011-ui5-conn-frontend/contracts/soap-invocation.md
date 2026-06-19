# Contract: SOAP Invocation (browser → `/ws/conn/<Service>`)

How the SPA sends an operation and interprets the result. Same-origin, raw SOAP 1.1.

## Request
```
POST /ws/conn/<Service> HTTP/1.1
Host: <same origin as the SPA>
Content-Type: text/xml; charset=UTF-8
SOAPAction: "<operation.soapAction>"

<soap:Envelope ...> ... full envelope from the XMLModel ... </soap:Envelope>
```
- Body is the exact serialized content of the dialog's `XMLModel` (full envelope incl.
  headers), after the pre-send validity gate passes (FR-013).
- No CORS headers needed (same origin, R1). No auth headers added by the SPA — the host
  server's policy applies (R8).
- Sensitive field values (PINs, document bytes) appear only in this request body and are
  never logged client-side (FR-016).

## Success response
```
HTTP/1.1 200 OK
Content-Type: text/xml; charset=UTF-8

<soap:Envelope><soap:Body> <...Response/> </soap:Body></soap:Envelope>
```
- Parsed with `DOMParser`. Raw body always available to the user (FR-010).
- If `operation.isRead`, apply `rowMapping` to refresh the master list.
- If `operation.mutatesEntity`, re-invoke the service's `listOperationId` to refresh (FR-012).

## Fault response
```
HTTP/1.1 500 Internal Server Error
Content-Type: text/xml; charset=UTF-8

<soap:Envelope><soap:Body>
  <soap:Fault>
    <faultcode>...</faultcode>
    <faultstring>...</faultstring>
    <detail> ...gematik Trace/Error... </detail>
  </soap:Fault>
</soap:Body></soap:Envelope>
```
- HTTP 500 with a `soap:Fault` body is a **structured fault**, not a transport error:
  extract `faultcode`/`faultstring`/`detail` and show them; entered data is preserved (FR-011).
- The gematik `detail` (e.g., `Trace`/`Error` with code) is rendered when present.

## Transport / error states
| Condition | UI behavior |
|---|---|
| Network failure / timeout | Non-blocking error with retry; entered data preserved (Edge: service unreachable) |
| HTTP 200, non-SOAP body | Show raw body + "unexpected response" message |
| HTTP 401/403 (host auth) | Surface auth-required message (host OIDC), no app-level handling |
| Long-running op (e.g., signDocument) | Busy/pending indicator until response (Edge: async) |

## Output encoding (security)
Response XML is rendered via escaped UI5 controls / CodeEditor (read-only) — never injected
as HTML — to prevent XSS from konnektor-returned content (Principle V / OWASP).
