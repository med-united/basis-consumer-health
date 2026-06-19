# Implementation Plan: UI5 Konnektor SOAP Frontend (ui5-conn-frontend)

**Branch**: `011-ui5-conn-frontend` | **Date**: 2026-06-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/011-ui5-conn-frontend/spec.md`

## Summary

Build a responsive single-page UI5 application — a new Maven module **`ui5-conn-frontend`** — that lets technicians and integrators exercise the konnektor SOAP services already exposed by `konnektor-soap-server` (Card, Card Terminal, Certificate, Event, Encryption, Signature, Auth Signature). The app ships its built assets under `src/main/resources/META-INF/resources/conn-ui/` and is added as a dependency of **`quarkus-server`**, so Quarkus serves the SPA from the **same origin** as the SOAP endpoints (`/ws/conn/*`). No proxy/relay and no CORS handling are required (per clarification).

The home screen is a `sap.m.TileContainer` with one tile per service, sourced from a static **service catalog** JSON. Selecting a tile opens a service client built on `sap.f.FlexibleColumnLayout`: a master list populated **only** from that service's read-type operations (services without one show a permanent placeholder), plus an action button per SOAP operation. Each action opens a `sap.m.Dialog` whose form fields and a raw-XML `CodeEditor` are both bound to a single `sap.ui.model.xml.XMLModel` holding the **full SOAP envelope** (headers + body). Editing either view updates the shared model; malformed XML is blocked before send. Submitting POSTs the serialized envelope to `/ws/conn/<Service>` (`text/xml`, `SOAPAction` header); the response or SOAP fault is parsed and shown, and any affected master list is refreshed.

The invocation context (`mandantId`/`clientSystemId`/`workplaceId`/`userId`) is pre-filled from **server-configured defaults** exposed by a tiny read-only config endpoint in the new module and is user-overridable per request. Access control is **inherited** from `quarkus-server` (OIDC in prod, open in dev) — this feature adds no auth layer.

## Technical Context

**Language/Version**: JavaScript (ES2020, UI5 module syntax) for the SPA; Java 21 LTS for the small server-side config endpoint.

**Primary Dependencies**:

| Dependency | Purpose |
|---|---|
| **OpenUI5** (Apache-2.0) | UI toolkit: `sap.m`, `sap.f.FlexibleColumnLayout`, `sap.m.TileContainer`/`GenericTile`, `sap.ui.layout.form`, `sap.ui.codeeditor.CodeEditor`, `sap.ui.model.xml.XMLModel`. License-clean per Principle VII. |
| **@ui5/cli** (UI5 Tooling) via **frontend-maven-plugin** | Build (`ui5 build self-contained` → offline, same-origin bundle) and test (karma + QUnit/OPA5). Node used at **build time only**; no Node at runtime. |
| Browser `fetch` + `DOMParser`/`XMLSerializer` (standard interfaces, Principle VIII) | Raw SOAP transport and XML model ↔ raw-text synchronization. No SOAP client library. |
| Quarkus 3.34.7 REST (`quarkus-rest` + `quarkus-rest-jackson`) | One read-only `GET /conn-ui/config.json` endpoint exposing server-configured default invocation context. |
| Quarkus static resource serving (`META-INF/resources`, built-in) | Serves the SPA same-origin with `/ws/conn/*`; no extra extension. |
| JUnit 5 + `@QuarkusTest` (already present) | Tests the config endpoint. |

**Storage**: None. No database, no server-side session. Sensitive inputs (PINs, document content) live only in the browser for the lifetime of the active request (FR-016). The service catalog is a static JSON asset; default context comes from runtime config.

**Testing**:
- **JS unit (QUnit)**: SOAP envelope construction from catalog templates; envelope ↔ form binding paths; raw-XML ↔ XMLModel round-trip with zero drift (SC-004); XML validity gate (SC-007); SOAP-fault parsing.
- **JS integration (OPA5)** against a mock SOAP server: tile launchpad → open client → list population → open action dialog → toggle form/raw → edit in both → submit → response/fault rendering → list refresh (User Stories 1–3).
- **Java (`@QuarkusTest`)**: `/conn-ui/config.json` returns configured defaults; never leaks secrets.
- Coverage gate ≥ 80% on SPA source modules (Principle II), enforced in CI via karma coverage. Spec-named tests (e.g., `verify_pin_round_trips_form_and_raw_xml`).

**Target Platform**: Modern evergreen browsers (desktop + tablet + phone viewports) served by the existing Linux/JVM `quarkus-server` (Kubernetes pod), consistent with features 001/010.

**Project Type**: Web frontend module added to the existing multi-module Maven reactor; one new reactor edge `quarkus-server → ui5-conn-frontend`.

**Performance Goals** (Principle IV): First Contentful Paint < 1.5 s and critical render < 2 s on 4G via the self-contained bundle + async library loading; interaction feedback < 100 ms (busy indicators on submit), screen transitions < 300 ms; resident memory < 150 MB with no retention of request payloads beyond the active call.

**Constraints**: Same-origin only (no CORS); raw SOAP/XML semantics preserved end-to-end (FR-009); no persistence of request history or sensitive data (FR-016); malformed XML never sent (FR-013); responsive down to phone single-column (FR-015); adding a new service must be a catalog entry, not new code (SC-003); inherits host auth (no new auth surface).

**Scale/Scope**: 7 services, ~25 operations total, ~50 form fields across operations. Single-konnektor client; tens of concurrent operators. No throughput SLA beyond responsive UI budgets.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Gate | Status | Notes |
|---|---|---|
| Principle I — No premature abstractions / no single-impl interface; no `Impl` suffix; readability; dependency hygiene | ✅ PASS | One generic, catalog-driven `SoapClientController` serves all services (DRY) instead of 7 near-duplicate controllers; no speculative interfaces. New runtime deps justified by capability gap: OpenUI5 (UI toolkit), `quarkus-rest` (expose runtime config). Build-time Node confined to `frontend-maven-plugin`. No `Impl` names. |
| Principle II — Test-first, ≥ 80% coverage, deterministic | ✅ PASS | QUnit + OPA5 authored before controllers; OPA5 runs against a deterministic mock SOAP server (no live konnektor); round-trip/validity assertions are pure-function deterministic. Java config endpoint has `@QuarkusTest`. Coverage gate ≥ 80% in CI. |
| Principle III — UX consistency (design system, error/empty/loading states, WCAG 2.1 AA, terminology) | ✅ PASS | All UI from OpenUI5 design system; uniform navigation (FlexibleColumnLayout back, dialog save/cancel); mandatory loading (busy), empty (no entities / no-read-op placeholder), and error (SOAP fault) states defined per FR-003/FR-010/FR-011; OpenUI5 controls ship WCAG 2.1 AA roles/labels — verified for custom bits; gematik/TI terminology used for service & operation labels. |
| Principle IV — Performance budgets | ✅ PASS | Self-contained bundle + async libs for FCP/load budgets; busy indicators for <100 ms feedback; no unbounded buffers; payloads released after each call (<150 MB). |
| Principle V — Security & gematik TI compliance (gemSpec_DS_Hersteller) + OWASP Top 10 | ✅ PASS | Same-origin keeps TLS/mTLS + konnektor secrets server-side (no browser exposure); inherits OIDC auth; PIN inputs masked, never logged/persisted (FR-016); responses rendered with output encoding (no XSS); config endpoint exposes no secret material; threat analysis to follow via after_implement hook. |
| Principle VI — UML documentation | ✅ PASS | use-case, deployment, component, sequence-invoke-operation, sequence-xml-toggle, state-request-dialog, class-catalog + `diagrams/README.md` produced in Phase 1. |
| Principle VII — External library evaluation & security review | ✅ PASS | OpenUI5 (Apache-2.0) evaluated vs hand-rolled UI and SAPUI5/CDN; `@ui5/cli` build-time only; license + maintenance recorded in research.md; both enter the SBOM. |
| Principle VIII — Standard interface adoption | ✅ PASS | Browser standards (`fetch`, `DOMParser`, `XMLSerializer`) and the framework's standard `XMLModel`/routing rather than bespoke wrappers; JAX-RS for the config endpoint. |
| Quality Gate — SBOM / new dependency | ✅ PASS | OpenUI5, `@ui5/cli` (build), `quarkus-rest(+jackson)` added to SBOM with license + review note. |

**Result: PASS — no violations. Complexity Tracking not required.**

## Project Structure

### Documentation (this feature)

```text
specs/011-ui5-conn-frontend/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions (hosting, UI5 dist, build, transport, XML model, catalog, config, auth, testing)
├── data-model.md        # Phase 1 — client-side entities + config payload schema (no DB)
├── quickstart.md        # Phase 1 — build, run, and walk the three user-story scenarios
├── contracts/
│   ├── service-catalog.md      # JSON schema: services → operations → envelope templates + field maps + read/mutate flags
│   ├── soap-invocation.md      # HTTP contract for POST /ws/conn/<Service> (headers, SOAPAction, response/fault)
│   ├── ui-config-endpoint.md   # GET /conn-ui/config.json response schema (default invocation context)
│   └── xml-model-binding.md    # XMLModel binding + form↔raw sync rules + validity gate
├── diagrams/
│   ├── README.md
│   ├── use-case.puml
│   ├── deployment.puml
│   ├── component.puml
│   ├── sequence-invoke-operation.puml
│   ├── sequence-xml-toggle.puml
│   ├── state-request-dialog.puml
│   └── class-catalog.puml
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
ui5-conn-frontend/                        # NEW reactor module (add to root pom <modules>)
├── pom.xml                               # parent basis-consumer-parent; deps: quarkus-rest, quarkus-rest-jackson,
│                                         #   quarkus-arc; build: frontend-maven-plugin → @ui5/cli (ui5 build self-contained)
├── ui5.yaml                              # OpenUI5 framework + libraries; self-contained build config
├── package.json                          # @ui5/cli, karma, qunit, OPA deps (build/test only)
└── src/
    ├── main/
    │   ├── webapp/                       # UI5 app source (built into target/classes/META-INF/resources/conn-ui)
    │   │   ├── manifest.json             # app id, routing (home + service client), models, dependencies
    │   │   ├── index.html                # bootstraps the self-contained OpenUI5 bundle
    │   │   ├── Component.js
    │   │   ├── model/
    │   │   │   ├── ServiceCatalog.js     # loads catalog.json; exposes services/operations
    │   │   │   └── SoapEnvelope.js       # build envelope from template; XMLModel<->raw text; validity check
    │   │   ├── service/
    │   │   │   ├── SoapClient.js         # fetch POST to /ws/conn/<Service>; parse response/fault
    │   │   │   └── ConfigService.js      # GET /conn-ui/config.json (default context)
    │   │   ├── controller/
    │   │   │   ├── Home.controller.js            # TileContainer of services
    │   │   │   ├── ServiceClient.controller.js   # FlexibleColumnLayout: list + actions (generic, catalog-driven)
    │   │   │   └── OperationDialog.js # form<->raw toggle, validate, submit
    │   │   ├── view/
    │   │   │   ├── Home.view.xml
    │   │   │   ├── ServiceClient.view.xml
    │   │   │   └── fragment/OperationDialog.fragment.xml
    │   │   ├── i18n/i18n.properties
    │   │   └── catalog/catalog.json      # static service/operation catalog (7 services, ~25 ops)
    │   └── java/de/servicehealtherx/connui/
    │       └── ConnUiConfigResource.java # @Path("/conn-ui/config.json") GET → default ContextType (from config)
    └── test/
        ├── webapp/                       # QUnit (unit) + OPA5 (integration) + mock SOAP server
        │   ├── unit/...
        │   └── integration/...
        └── java/de/servicehealtherx/connui/
            └── ConnUiConfigResourceTest.java     # @QuarkusTest

quarkus-server/
└── pom.xml                               # MODIFIED: add dependency on ui5-conn-frontend
```

**Structure Decision**: A single new frontend module `ui5-conn-frontend` holds the UI5 SPA (built into `META-INF/resources/conn-ui`) plus one small JAX-RS config resource. The only new reactor edge is `quarkus-server → ui5-conn-frontend`; the module depends downward solely on Quarkus REST + ARC, keeping the graph acyclic. The SPA is **catalog-driven** so the 7 services and ~25 operations are data, not code — a new service is a `catalog.json` entry plus its envelope template/field map (SC-003), and a single generic controller set serves them all (Principle I, DRY).

## Complexity Tracking

> No Constitution Check violations — section intentionally empty.
