# Diagrams: UI5 Konnektor SOAP Frontend (ui5-conn-frontend)

Render with [PlantUML](https://plantuml.com/) or view directly in GitHub using the
[PlantUML for GitHub](https://github.com/plantuml/plantuml-for-github) browser extension.

Proxy image link format:
`https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-ui5-conn-frontend/diagrams/<file>.puml`

| Priority | Diagram | File | Description |
|---|---|---|---|
| 1 | Use Case | [use-case.puml](use-case.puml) | Technician interactions: browse tiles, open client, invoke ops, form/raw edit, inspect response/fault |
| 2 | Deployment | [deployment.puml](deployment.puml) | Browser SPA ↔ `quarkus-server` (static + config + `/ws/conn/*`) ↔ konnektor; same-origin |
| 3 | Component | [component.puml](component.puml) | SPA modules (catalog-driven controllers, XMLModel, SoapClient) + `ConnUiConfigResource` |
| 4 | Sequence — Invoke Operation | [sequence-invoke-operation.puml](sequence-invoke-operation.puml) | Action → dialog → validate → POST SOAP → response/fault → list refresh |
| 4 | Sequence — XML Toggle | [sequence-xml-toggle.puml](sequence-xml-toggle.puml) | form↔raw synchronization through one XMLModel + validity gate |
| 5 | State — Request Dialog | [state-request-dialog.puml](state-request-dialog.puml) | Form/Raw/Invalid/Submitting/ShowResult/ShowFault transitions |
| 6 | Class | [class-catalog.puml](class-catalog.puml) | Service/Operation/Field/RowMapping + RequestEnvelope/Response/Fault model |

### Rendered previews

**Use Case** — ![use-case](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-ui5-conn-frontend/diagrams/use-case.puml)

**Deployment** — ![deployment](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-ui5-conn-frontend/diagrams/deployment.puml)

**Component** — ![component](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-ui5-conn-frontend/diagrams/component.puml)

**Sequence — Invoke Operation** — ![sequence-invoke-operation](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-ui5-conn-frontend/diagrams/sequence-invoke-operation.puml)

**Sequence — XML Toggle** — ![sequence-xml-toggle](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-ui5-conn-frontend/diagrams/sequence-xml-toggle.puml)

**State — Request Dialog** — ![state-request-dialog](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-ui5-conn-frontend/diagrams/state-request-dialog.puml)

**Class** — ![class-catalog](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-ui5-conn-frontend/diagrams/class-catalog.puml)
