# UML Diagrams — Basis Consumer

These diagrams are written in [PlantUML](https://plantuml.com/) (`.puml` files).

**To render diagrams directly in your GitHub browser**, install the
[PlantUML for GitHub](https://github.com/plantuml/plantuml-for-github) browser extension.
It renders `.puml` files inline — no server or plugin needed.

---

## 1. Use Case Diagram

> Actors and their interactions with the Basis Consumer system.

![Use Case](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/refs/heads/develop/specs/001-quarkus-basis-consumer/diagrams/use-case.puml)

[Source: use-case.puml](use-case.puml)

---

## 2. Deployment Diagram

> Multi-tenant Kubernetes deployment with TI network connectivity, HSM, SICCT terminals, and client systems.

![Deployment](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/refs/heads/develop/specs/001-quarkus-basis-consumer/diagrams/deployment.puml)

[Source: deployment.puml](deployment.puml)

---

## 3. Component Diagram

> Maven module breakdown: how `basis-consumer-server`, `konnektor-server`, `ldap-proxy-server`, `signature-lib`, `sicct-lib` (Quarkus extension), and `openkim` (KIM submodule) relate to each other.

![Component](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/refs/heads/develop/specs/001-quarkus-basis-consumer/diagrams/component.puml)

[Source: component.puml](component.puml)

---

## 4. Sequence Diagrams

### 4a. EHEALTH TERMINAL AUTHENTICATE (Pairing + Session Validation)

> Full protocol: initial pairing (CREATE), session establishment with VALIDATE challenge/response, and maintenance pairing (ADD) after SAK.AUT key rotation.

![EHEALTH Authenticate](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/refs/heads/develop/specs/001-quarkus-basis-consumer/diagrams/sequence-ehealth-authenticate.puml)

[Source: sequence-ehealth-authenticate.puml](sequence-ehealth-authenticate.puml)

### 4b. Signing Operation (SOAP → CryptoProvider → SICCT Card)

> A `SignDocument` request routed from the SOAP endpoint through the CryptoProvider to a card in a SICCT terminal, including error path on TLS loss.

![Signing](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/refs/heads/develop/specs/001-quarkus-basis-consumer/diagrams/sequence-signing.puml)

[Source: sequence-signing.puml](sequence-signing.puml)

### 4c. Remote-PIN via gSMC-KT Trusted Channel

> PIN entry on the SICCT terminal's secure PIN pad, with gSMC-KT card-to-card trusted channel protecting the PIN in transit to the HBA.

![Remote PIN](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/refs/heads/develop/specs/001-quarkus-basis-consumer/diagrams/sequence-remote-pin.puml)

[Source: sequence-remote-pin.puml](sequence-remote-pin.puml)

---

## 5. State Charts

### 5a. SICCT Terminal — CORRELATION State Machine

> Terminal lifecycle from `bekannt` (registered in DB) through pairing to `aktiv`, including reconnection and decommission paths.

![SICCT Terminal State](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/refs/heads/develop/specs/001-quarkus-basis-consumer/diagrams/state-sicct-terminal.puml)

[Source: state-sicct-terminal.puml](state-sicct-terminal.puml)

### 5b. CryptoProvider Key Source Adapter — Availability State Machine

> How each key source adapter moves between `Initializing`, `Available`, `Unavailable`, and `Reconnecting` states.

![CryptoProvider State](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/refs/heads/develop/specs/001-quarkus-basis-consumer/diagrams/state-crypto-provider.puml)

[Source: state-crypto-provider.puml](state-crypto-provider.puml)

---

## 6. Class Diagram

> Core domain model: `CryptoProvider`, `KeySourceAdapter` hierarchy, `CardTerminalRecord` (JPA), `SicctTerminal` (runtime), `CardHandle`, and `AuditLogEntry`.

![Class Diagram](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/refs/heads/develop/specs/001-quarkus-basis-consumer/diagrams/class-domain.puml)

[Source: class-domain.puml](class-domain.puml)

---

## Local rendering

```bash
# Install PlantUML (requires Java)
brew install plantuml        # macOS
apt install plantuml         # Debian/Ubuntu

# Render all diagrams to PNG
plantuml specs/001-quarkus-basis-consumer/diagrams/*.puml
```

> **Tip**: Replace `med-united` in the proxy URLs above with your actual GitHub organization/user name to enable live rendering on GitHub without the browser extension.
