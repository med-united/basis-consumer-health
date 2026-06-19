# Diagrams: VSDService — Local ReadVSD (Card-to-Card + eGK Read)

Render with [PlantUML](https://plantuml.com/) or view directly in GitHub using the
[PlantUML for GitHub](https://github.com/plantuml/plantuml-for-github) browser extension.

Proxy image link format:
`https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/011-read-vsd/specs/011-read-vsd/diagrams/<file>.puml`

| Priority | Diagram | File | Description |
|---|---|---|---|
| 1 | Use Case | [use-case.puml](use-case.puml) | Primary system, HBA/SMC-B, eGK and admin interactions with the local ReadVSD |
| 2 | Deployment | [deployment.puml](deployment.puml) | Konnektor pod, modules, card terminal — no external TI connections |
| 3 | Component | [component.puml](component.puml) | `konnektor-soap-server` → `apdu-lib` (vsdm/c2c) → `CardReaderPort`/Bouncy Castle |
| 4 | Sequence — ReadVSD | [sequence-readvsd.puml](sequence-readvsd.puml) | Validate → reserve → eGK check → C2C → read PD/VD/StatusVD/GVD → audit → response |
| 4 | Sequence — C2C | [sequence-c2c.puml](sequence-c2c.puml) | TUC_KON_005 ELC mutual auth: CVC chain → PSO Verify → GENERAL AUTHENTICATE → AUT_VSD |
| 5 | State — ReadVSD | [state-readvsd.puml](state-readvsd.puml) | Operation + eGK reservation lifecycle, fault codes, timeout, card-busy |
| 6 | Class | [class-vsdm.puml](class-vsdm.puml) | SOAP edge + `ReadVsdService` orchestrator + APDU/C2C/StatusVD classes |

### Rendered previews

**Use Case** — ![use-case](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-read-vsd/diagrams/use-case.puml)

**Deployment** — ![deployment](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-read-vsd/diagrams/deployment.puml)

**Component** — ![component](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-read-vsd/diagrams/component.puml)

**Sequence — ReadVSD** — ![sequence-readvsd](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-read-vsd/diagrams/sequence-readvsd.puml)

**Sequence — C2C** — ![sequence-c2c](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-read-vsd/diagrams/sequence-c2c.puml)

**State — ReadVSD** — ![state-readvsd](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-read-vsd/diagrams/state-readvsd.puml)

**Class** — ![class-vsdm](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/develop/specs/011-read-vsd/diagrams/class-vsdm.puml)
