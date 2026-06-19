# ui5-conn-frontend

Responsive **OpenUI5** single-page app for exercising the konnektor SOAP services exposed by
`konnektor-soap-server`. It is served **same-origin** with the SOAP endpoints, so it calls
them directly with native SOAP/XML — no CORS, no relay, and konnektor TLS/credentials stay
server-side.

Spec & design: [`specs/011-ui5-conn-frontend/`](../specs/011-ui5-conn-frontend/).

## How it is served

- The built app is emitted to `target/classes/META-INF/resources/conn-ui/`.
- This module is a dependency of `quarkus-server`, which serves `META-INF/resources`
  statically. Open **`/conn-ui/`** on the running server (same origin as `/ws/conn/*`).
- A tiny JAX-RS endpoint, `GET /conn-ui/config.json` (`ConnUiConfigResource`), exposes the
  server-configured default invocation context (mandant / client system / workplace / user).
  Configure with:

  ```properties
  connui.default-context.mandant-id=Mandant1
  connui.default-context.client-system-id=ClientSystem1
  connui.default-context.workplace-id=Workplace1
  ```

## Build & run

```bash
# Build (provisions Node via frontend-maven-plugin, runs ui5 build + JS tests):
./mvnw -pl ui5-conn-frontend -am install

# Build only the Java config endpoint, skipping the JS toolchain (offline/CI without Node):
./mvnw -pl ui5-conn-frontend -am install -Dui5.build.skip=true

# Run the hosting server (dev: OIDC disabled), then open http://localhost:8080/conn-ui/
./mvnw -pl quarkus-server quarkus:dev
```

## Architecture (catalog-driven)

| Piece | File |
|---|---|
| Service catalog (tiles + operations + envelope templates + field maps) | `src/main/webapp/catalog/catalog.json` |
| Catalog loader/validator | `model/ServiceCatalog.js` |
| Single-source SOAP envelope (XMLModel) + raw↔model sync + validity gate | `model/SoapEnvelope.js` |
| Same-origin SOAP transport + fault parsing | `service/SoapClient.js` |
| Default-context loader | `service/ConfigService.js` |
| Home tiles | `view/Home.view.xml` + `controller/Home.controller.js` |
| Service client (FlexibleColumnLayout: master list + actions) | `view/ServiceClient.view.xml` + controller |
| Operation dialog (form ⇄ raw XML) | `view/fragment/OperationDialog.fragment.xml` + `controller/OperationDialog.js` |

### Adding a service or operation

Add an entry to `catalog.json` — a service block (`id`, `title`, `icon`, `endpoint`,
`namespace`, optional `listOperationId`) and its operations (each with `soapAction`,
`envelopeTemplate` containing `{{context.*}}` placeholders, a `fields` map, `isRead` /
`mutatesEntity`, and a `rowMapping` for read operations). No controller code changes — one
generic controller set renders every catalog entry.

## Quality notes

- **Accessibility / UX (Principle III)**: all controls from the OpenUI5 design system
  (WCAG 2.1 AA); error / empty / loading states are defined (master-list empty/placeholder,
  busy indicators, SOAP-fault MessageStrip). Layout is responsive (GridContainer tiles,
  FlexibleColumnLayout collapses to single column on phones; dialog stretches on phone).
- **Performance (Principle IV)**: optimized self-contained UI5 build; async library loading;
  busy indicators give immediate feedback; no request payloads retained after a call.
- **Security (Principle V / OWASP)**: same-origin keeps credentials server-side; sensitive
  inputs (PINs, document bytes) use masked inputs and are never logged or persisted; konnektor
  responses are shown via a read-only CodeEditor (text, no HTML injection); the config endpoint
  exposes no secrets; the app inherits `quarkus-server` access control (OIDC in prod).

## Dependencies (SBOM record — Principle VII)

| Dependency | Scope | License |
|---|---|---|
| OpenUI5 (`@openui5/*` 1.120) | runtime (bundled) | Apache-2.0 |
| `@ui5/cli`, karma, qunit | build/test only | Apache-2.0 / MIT |
| `quarkus-rest`, `quarkus-rest-jackson` | runtime (config endpoint) | Apache-2.0 |
| `frontend-maven-plugin` | build only | Apache-2.0 |

## Tests

- **JS unit** (QUnit) under `src/main/webapp/test/unit/` — covers the SOAP envelope
  round-trip (SC-004), the validity gate (SC-007), catalog validation, and SOAP-fault
  parsing. Run via karma in **script mode** (`karma.conf.js`). **13/13 green.**
  - Default reactor build skips JS tests (`-Dui5.test.skip` default `true`) so a plain
    `mvn install` needs no Chrome. Run them in CI / locally with:
    `./mvnw -pl ui5-conn-frontend -Dui5.test.skip=false test`
- **JS integration** (OPA5 journeys) under `src/main/webapp/test/integration/` — authored
  for US1–US3; currently excluded from the default karma gate (headless full-component boot
  needs harness hardening) — re-enable in `karma.conf.js`.
- **Java** `@QuarkusTest` `ConnUiConfigResourceTest` under `src/test/java/` — **2/2 green**.
