# Diagrams: CETP Client (Event Delivery to Client Systems)

Render with [PlantUML](https://plantuml.com/) or view directly in GitHub using the
[PlantUML for GitHub](https://github.com/plantuml/plantuml-for-github) browser extension.

Proxy image link format:
`https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/010-cetp-client/specs/010-cetp-client/diagrams/<file>.puml`

| Priority | Diagram | File | Description |
|---|---|---|---|
| 1 | Use Case | [use-case.puml](use-case.puml) | Client system, basis-service and admin interactions with the Systeminformationsdienst PUSH |
| 2 | Deployment | [deployment.puml](deployment.puml) | Konnektor pod, modules, DB, gSMC-K/HSM, and the client-system event sink over CETP/mTLS |
| 3 | Component | [component.puml](component.puml) | `konnektor-soap-server` → `cetp-client-lib` → `quarkus-sicct-extension`/`api-telematik` |
| 4 | Sequence — Event Delivery | [sequence-event-delivery.puml](sequence-event-delivery.puml) | TUC_KON_256 filter pipeline → frame → send → failure counting / Auto-Unsubscribe |
| 4 | Sequence — Subscribe | [sequence-subscribe.puml](sequence-subscribe.puml) | Subscribe → checkArguments → access check → persist (uniqueness key, now+25h) |
| 5 | State — Subscription | [state-subscription.puml](state-subscription.puml) | ACTIVE / EXPIRED / AUTO_UNSUBSCRIBED lifecycle and transitions |
| 6 | Class | [class-subscription.puml](class-subscription.puml) | Domain + delivery + JPA model; `EventDeliveryFilter` (3 impls) |

### Rendered previews

**Use Case** — ![use-case](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/010-cetp-client/diagrams/use-case.puml)

**Deployment** — ![deployment](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/010-cetp-client/diagrams/deployment.puml)

**Component** — ![component](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/010-cetp-client/diagrams/component.puml)

**Sequence — Event Delivery** — ![sequence-event-delivery](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/010-cetp-client/diagrams/sequence-event-delivery.puml)

**Sequence — Subscribe** — ![sequence-subscribe](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/010-cetp-client/diagrams/sequence-subscribe.puml)

**State — Subscription** — ![state-subscription](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/010-cetp-client/diagrams/state-subscription.puml)

**Class** — ![class-subscription](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/010-cetp-client/diagrams/class-subscription.puml)
