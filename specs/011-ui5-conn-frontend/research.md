# Phase 0 Research: UI5 Konnektor SOAP Frontend

All Technical Context unknowns are resolved below. Decisions marked **(clarified)** were
fixed during `/speckit-clarify` (Session 2026-06-18) and are restated here for traceability.

## R1 — Same-origin hosting strategy **(clarified)**

- **Decision**: Ship the built SPA under `src/main/resources/META-INF/resources/conn-ui/`. Quarkus serves `META-INF/resources` as static content automatically. Add `ui5-conn-frontend` as a dependency of `quarkus-server`, which already hosts the konnektor SOAP endpoints at `quarkus.cxf.path=/ws` → `/ws/conn/<Service>`. UI and SOAP therefore share one origin.
- **Rationale**: Same-origin removes CORS entirely and keeps konnektor TLS/mTLS material and secrets server-side (Principle V). No relay/proxy code to build or secure.
- **Alternatives rejected**: Browser→konnektor direct (would expose TLS client credentials, needs CORS); a dedicated reverse-proxy/relay module (extra moving part, no benefit once co-hosted).

## R2 — UI5 distribution & framework

- **Decision**: Use **OpenUI5** (Apache-2.0), built **self-contained** (`ui5 build self-contained`) so the runtime subset is bundled with the app and served same-origin.
- **Rationale**: Apache-2.0 is license-clean (Principle VII). Self-contained bundling means no external CDN dependency — important for restricted TI networks — and a deterministic, offline-capable artifact. OpenUI5 provides every control the spec needs (TileContainer/GenericTile, FlexibleColumnLayout, Form, CodeEditor, XMLModel).
- **Alternatives rejected**: SAPUI5 from the public CDN (external runtime dependency, unavailable in air-gapped TI deployments, licensing); hand-rolled HTML/JS (re-invents a design system, fails Principle III consistency + WCAG, large effort).

## R3 — Build toolchain

- **Decision**: Drive `@ui5/cli` (UI5 Tooling) from Maven via **frontend-maven-plugin**, which provisions a local Node/npm during the build only. `ui5 build self-contained` output is emitted into `target/classes/META-INF/resources/conn-ui` so it lands on the Quarkus classpath. Tests run via karma in the same plugin lifecycle.
- **Rationale**: UI5 Tooling is the standard, supported way to build/test/optimize a UI5 app and to obtain coverage (Principle II). Node is confined to build time; no Node at runtime. The optimized bundle satisfies the FCP/load budgets (Principle IV).
- **Alternatives rejected**: Hand-authored unbundled sources + vendored runtime (no minification/coverage tooling, weaker Principle II/IV story); committing Node tools into the Java build globally (dependency hygiene — keep it plugin-local).

## R4 — Browser→SOAP transport

- **Decision**: Use the browser `fetch` API to `POST` the serialized SOAP envelope to `/ws/conn/<Service>` with `Content-Type: text/xml; charset=UTF-8` and the operation's `SOAPAction` header. Parse the `text/xml` response with `DOMParser`. HTTP 500 with a `soap:Fault` body is treated as a structured fault, not a transport error.
- **Rationale**: Standard interfaces (Principle VIII); no SOAP client library needed. Same-origin → no CORS preflight. CXF accepts standard SOAP 1.1 POSTs.
- **Alternatives rejected**: A JS SOAP library (unnecessary dependency; we already have the raw envelope from the XMLModel); JSON-over-REST shim (would hide SOAP semantics, violating FR-009).

## R5 — Request representation & form↔raw synchronization **(raw scope clarified)**

- **Decision**: Each operation dialog owns one `sap.ui.model.xml.XMLModel` holding the **full SOAP envelope** (`<soap:Envelope>` headers + body). Form controls bind to nodes via XML binding paths; the raw view is a `sap.ui.codeeditor.CodeEditor` showing `XMLSerializer` output. Form edits write to the model (→ raw reflects on next serialize); raw edits are parsed with `DOMParser` and `setData` back into the model (→ form reflects). Before a view switch or submit, validity is checked via `DOMParser` `parsererror` detection; invalid XML blocks the action with a message (FR-013, SC-007). A round-trip (form→raw→form and raw→form→raw) must show zero drift (SC-004).
- **Rationale**: `XMLModel` is the framework-native way to two-way bind XML, mapping directly onto "an XML model that directly represents the SOAP XML." One shared model guarantees both views never diverge.
- **Alternatives rejected**: Separate JSON form model + string XML kept in sync by hand (drift risk, fails SC-004); body-only XML (rejected by clarification — full envelope is required).

## R6 — Service catalog (data-driven services/operations)

- **Decision**: A static `catalog.json` enumerates the 7 services and their operations. Per operation: display name, endpoint path, `SOAPAction`, target namespace, an **envelope template** (XML skeleton with context placeholders), a **field map** (label, binding path, control type, sensitive flag), an `isRead` flag (whether it populates the master list and how to map rows), and a `mutatesEntity` flag (refresh the list on success). One generic controller set renders any catalog entry.
- **Rationale**: Makes services/operations data, not code (SC-003: adding a service = a catalog entry). Avoids 7 duplicated clients (Principle I, DRY). Templates/field maps are authored from the gematik conn WSDLs/XSDs in the `api-telematik` submodule (branch `ebk_6.0.3`); the namespaces/versions are already known (CardService v8.1, CardTerminalService v1.1, CertificateService v6.0, EventService v7.2, SignatureService v7.5, AuthSignatureService v7.4, EncryptionService).
- **Alternatives rejected**: One bespoke view/controller per service (duplication, higher maintenance); runtime WSDL parsing in the browser (complex, slow, no benefit for a fixed service set).

## R7 — Default invocation context source **(clarified)**

- **Decision**: A read-only `GET /conn-ui/config.json` JAX-RS endpoint in `ui5-conn-frontend` returns server-configured defaults (`mandantId`, `clientSystemId`, `workplaceId`, `userId`) read from Quarkus config. The SPA fetches it at startup and pre-fills the context block of every envelope; the user can override per request in the form or raw view (FR-014a).
- **Rationale**: Honors "server-configured defaults, user-overridable" without baking environment values into the static bundle. Keeps the (tiny) server code inside the new module rather than touching `konnektor-soap-server`.
- **Alternatives rejected**: Hard-coding context in the bundle (not environment-aware); manual entry every call (poor UX, rejected by clarification); a fixed/non-editable context (rejected by clarification).

## R8 — Access control **(clarified)**

- **Decision**: No application-specific auth. The SPA and config endpoint live behind whatever `quarkus-server` already enforces — OIDC in `%prod`, open in `%dev` (`%dev.quarkus.oidc.enabled=false`).
- **Rationale**: One consistent, reviewable security boundary (Principle I — no duplicate mechanisms; Principle V). Co-hosting means the same policy protects UI and SOAP.
- **Alternatives rejected**: A dedicated app login (parallel auth scheme); open/no-control (weaker posture for PIN/signing operations).

## R9 — Testing approach

- **Decision**: QUnit unit tests for envelope construction, binding-path mapping, raw↔model round-trip (SC-004), validity gate (SC-007), and fault parsing; OPA5 journey tests (User Stories 1–3) against a **mock SOAP server** serving canned success and fault responses; a `@QuarkusTest` for the config endpoint. CI enforces ≥ 80% coverage on SPA source (Principle II). Tests authored before controllers; spec-named (e.g., `lists_services_as_tiles_on_home`, `verify_pin_round_trips_form_and_raw_xml`, `rejects_malformed_envelope_before_send`).
- **Rationale**: Deterministic (no live konnektor), covers the measurable success criteria directly.
- **Alternatives rejected**: Manual-only testing (fails Principle II); live-konnektor integration tests in CI (non-deterministic, needs hardware/cards).

## Resolved unknowns summary

| Unknown | Resolution |
|---|---|
| Browser↔SOAP transport under CORS/TLS | R1 same-origin co-hosting + R4 `fetch` raw SOAP |
| UI5 runtime distribution (offline TI) | R2 OpenUI5 self-contained bundle |
| Build without polluting Java pipeline | R3 frontend-maven-plugin (build-time Node only) |
| Raw XML scope | R5 full SOAP envelope, single XMLModel |
| How services/operations are defined | R6 static catalog + generic controllers |
| Source of invocation-context defaults | R7 `/conn-ui/config.json` endpoint |
| Authentication | R8 inherit `quarkus-server` |
| Test strategy & coverage | R9 QUnit + OPA5 + `@QuarkusTest` |
