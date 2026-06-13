# Diagrams — apdu-lib TUC Card Management

**Feature Branch**: `007-apdu-lib-tuc-card-mgmt`  
**Render tool**: [PlantUML for GitHub](https://github.com/plantuml/plantuml-for-github) browser extension  

To view `.puml` files rendered inline in the GitHub web UI, install the PlantUML for GitHub browser extension.

---

## Diagram Index

| File | Type | Description |
|------|------|-------------|
| [use-case.puml](use-case.puml) | Use Case | Actors (Fachmodul, Konnektor Card Service, Card Terminal, Card) and all 27 TUC interactions |
| [deployment.puml](deployment.puml) | Deployment | Konnektor host topology: apdu-lib, sicct-lib, crypto-lib, test environment |
| [component.puml](component.puml) | Component | Module and package structure; dependency edges; CardTerminalTransport implementations |
| [sequence-verify-pin.puml](sequence-verify-pin.puml) | Sequence | TUC_KON_012 Verify PIN happy path — eGK Gen 2.0, local PIN-Pad |
| [sequence-card-to-card-auth.puml](sequence-card-to-card-auth.puml) | Sequence | TUC_KON_005 Card-to-Card Authentication — SMC-B → eGK Gen 1+, one-sided RSA |
| [state-card-session.puml](state-card-session.puml) | State Chart | CardSession lifecycle: lock states, PIN auth transitions, secure session |
| [class-model.puml](class-model.puml) | Class | Domain model: CardSession, AuthState, enums, CommandApdu, ResponseApdu, TucException, CardTerminalTransport |

---

## GitHub Proxy Image Links

Replace `<org>` and `<repo>` with the actual GitHub organization and repository name when the repository is pushed.

### Use Case Diagram
![use-case](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/<org>/<repo>/007-apdu-lib-tuc-card-mgmt/specs/007-apdu-lib-tuc-card-mgmt/diagrams/use-case.puml)

### Deployment Diagram
![deployment](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/<org>/<repo>/007-apdu-lib-tuc-card-mgmt/specs/007-apdu-lib-tuc-card-mgmt/diagrams/deployment.puml)

### Component Diagram
![component](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/<org>/<repo>/007-apdu-lib-tuc-card-mgmt/specs/007-apdu-lib-tuc-card-mgmt/diagrams/component.puml)

### Sequence — Verify PIN
![sequence-verify-pin](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/<org>/<repo>/007-apdu-lib-tuc-card-mgmt/specs/007-apdu-lib-tuc-card-mgmt/diagrams/sequence-verify-pin.puml)

### Sequence — Card-to-Card Authentication
![sequence-card-to-card-auth](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/<org>/<repo>/007-apdu-lib-tuc-card-mgmt/specs/007-apdu-lib-tuc-card-mgmt/diagrams/sequence-card-to-card-auth.puml)

### State — Card Session
![state-card-session](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/<org>/<repo>/007-apdu-lib-tuc-card-mgmt/specs/007-apdu-lib-tuc-card-mgmt/diagrams/state-card-session.puml)

### Class — Domain Model
![class-model](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/<org>/<repo>/007-apdu-lib-tuc-card-mgmt/specs/007-apdu-lib-tuc-card-mgmt/diagrams/class-model.puml)
