# Contract: UI Config Endpoint (`GET /conn-ui/config.json`)

A read-only JAX-RS resource in `ui5-conn-frontend` exposing server-configured defaults so
the SPA can pre-fill the invocation context (R7, FR-014a). Served same-origin by
`quarkus-server`, behind the host's existing access policy (R8).

## Request
```
GET /conn-ui/config.json HTTP/1.1
Accept: application/json
```

## Response
```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "defaultContext": {
    "mandantId": "Mandant1",
    "clientSystemId": "ClientSystem1",
    "workplaceId": "Workplace1",
    "userId": null
  }
}
```

## Rules
- Values are read from Quarkus config (e.g., `connui.default-context.*`), with safe blank
  defaults if unset.
- The payload MUST NOT contain secrets, credentials, TLS material, or konnektor connection
  internals (Principle V). It carries only invocation-context defaults.
- Idempotent, side-effect free, cacheable per session.

## Behavior in the SPA
- Fetched once at `Component` init; stored in a client model.
- Used to fill `{{context.*}}` placeholders when building each operation envelope.
- The user may override any value per request in the form or raw view; overrides never
  write back to the server.

## Testing (`@QuarkusTest`)
- Returns configured values when set.
- Returns blank-but-valid defaults when unset.
- Asserts no secret-looking keys are present in the payload.
