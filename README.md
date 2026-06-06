# Basis Consumer Health

A multi-tenant TI (Telematikinfrastruktur) platform service providing Konnektor-compatible SOAP APIs,
hybrid document encryption/signing, VZD LDAP proxy, KOM-LE/KIM secure messaging, and SICCT card
terminal management for German healthcare institutions.

## Architecture

See the full feature specification at
[specs/001-quarkus-basis-consumer/spec.md](specs/001-quarkus-basis-consumer/spec.md).

UML diagrams are available in
[specs/001-quarkus-basis-consumer/diagrams/](specs/001-quarkus-basis-consumer/diagrams/).

### UML Diagrams in GitHub

To render PlantUML (`.puml`) diagrams directly in your browser on GitHub, install the
**[PlantUML for GitHub](https://github.com/plantuml/plantuml-for-github)** browser extension.
It automatically renders `.puml` files inline — no server setup required.

## Modules

| Module | Description |
|--------|-------------|
| `basis-consumer-server` | Main Quarkus application (CDI, JPA, Hawtio, SmallRye Health) |
| `konnektor-server` | Apache CXF SOAP endpoints (Konnektor-compatible WSDL) |
| `ldap-proxy-server` | Netty LDAPv3 proxy → TI VZD (LDAPS) |
| `signature-lib` | CryptoProvider CDI interface + P12/PKCS#11/PC/SC/SICCT adapters |
| `sicct-lib` | SICCT Quarkus Extension (Netty TCP client, EHEALTH AUTHENTICATE) |
| `sicct-server` | SICCT protocol utilities and ASN.1 definitions |
| `openkim` (submodule) | KIM/KOM-LE client module (fork of [sberg-net/openkim](https://github.com/sberg-net/openkim)) |

## Key capabilities

- **Multi-tenancy**: One Kubernetes namespace per tenant; full isolation of keys, sessions, audit logs
- **Pluggable crypto**: P12, PKCS#11 HSM (Utimaco), PC/SC USB readers, SICCT networked terminals — simultaneously
- **EHEALTH AUTHENTICATE**: Full terminal pairing (CREATE), session validation (VALIDATE), and maintenance pairing (ADD) per gemSpec_KT V3.17.0
- **Remote-PIN**: gSMC-KT trusted channel PIN entry per TIP1-A_5012
- **Konnektor SOAP**: All gemSpec_Kon V5.27.0 §3.6 operations (SignDocument, EncryptDocument, VerifyPin, ExternalAuthenticate, ...)
- **gematik TI certified**: Afo traceability per commit, mandatory SSDLC, gemKPT_Test V3.6.0 test lifecycle

## Normative sources

- gemSpec_Basis_Consumer_V1.12.1
- gemSpec_Kon_V5.27.0
- gemSpec_KT_V3.17.0
- gemSpec_DS_Hersteller_V1.7.0
- gemKPT_Test_V3.6.0
- SICCT-Spezifikation-1.3.0

## Specification & planning artifacts

```
specs/001-quarkus-basis-consumer/
├── spec.md          # Feature specification (95 FRs)
├── plan.md          # Implementation plan
├── research.md      # Technical research findings
├── data-model.md    # Runtime & persistence data model
├── quickstart.md    # Validation scenarios
├── contracts/       # Config schemas & interface contracts
├── diagrams/        # PlantUML diagrams (use-case, deployment, component, sequence, state, class)
└── checklists/      # Quality checklists
```
