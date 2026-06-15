# Basis Consumer Health

A multi-tenant TI (Telematikinfrastruktur) platform service providing Konnektor-compatible SOAP APIs,
hybrid document encryption/signing, VZD LDAP proxy, KOM-LE/KIM secure messaging, and SICCT card
terminal management for German healthcare institutions.

## Developer start

```
./mvn install
./mvnw quarkus:dev -pl :quarkus-server
```

## Logging / APDU tracing

Every card exchange can be traced in hex. `ApduTrace` (in `apdu-lib`) logs each sent command APDU
and received response APDU on the logger **`de.servicehealtherx.apdu.trace`** at level **FINE**
(`DEBUG` for the JBoss LogManager). It is off by default and adds no overhead unless enabled. PINs
in VERIFY / CHANGE / RESET REFERENCE DATA commands are masked; certificate and personal data read
from the card is logged in full, so only enable tracing for debugging and protect the output.

Example output:

```
APDU > [reader-A slot 1] 00 A4 04 0C 07 D2 76 00 01 44 80 00
APDU < [reader-A slot 1] 6F 1A ... 90 00 SW=9000
```

Tracing is configured in `quarkus-server/src/main/resources/application.properties`. It is **on in
the `dev` profile** (`mvnw quarkus:dev`) and off otherwise:

```properties
%dev.quarkus.log.category."de.servicehealtherx.apdu.trace".level=DEBUG
```

To trace in production too, add the same property without the `%dev.` prefix. The console handler
emits the records as soon as the category is lowered to `DEBUG`.

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

### API / Shared libraries

| Module                | Description                                                                                                                        |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `api-telematik`       | Generated JAXB/JAX-WS stubs from gematik WSDL/XSD (consumer + conn interfaces)                                                     |
| `crypto-lib`          | TSL management, certificate parsing/validation, CryptoProvider CDI interface and key store adapters, JMX beans                     |
| `crypto-services-lib` | EncryptionService, SignatureService, CertificateService business logic; MicroProfile ConfigSource DB; JPA AppConfigProperty entity |
| `apdu-lib`            | Pure APDU generation library implementing 27 TUCs from gemSpec_Kon V5.27.0 §4.1.5.4 (no transport)                                 |
| `sicct-lib`           | SICCT ASN.1 codec, Netty 4 TCP/IP client pool, CardTerminal JPA entity, TPM 2.0 key sealing, BackupRestoreManagement JMX bean      |

### CryptoProvider adapters

| Module             | Description                                                                           |
| ------------------ | ------------------------------------------------------------------------------------- |
| `crypto-p12-lib`   | CryptoProvider backed by PKCS#12 keystores                                            |
| `crypto-pcsc-lib`  | CryptoProvider backed by local PC/SC card readers via `javax.smartcardio`             |
| `crypto-sicct-lib` | CryptoProvider backed by networked SICCT card terminals via `quarkus-sicct-extension` |

### Quarkus extensions

| Module                                | Description                                                                                                                   |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `quarkus-sicct-extension`             | Quarkus extension: SicctTerminalManager, connection pool, card event router, SmallRye Health check (runtime + deployment)     |
| `quarkus-ldap-proxy-server-extension` | Quarkus extension: Netty 4 LDAPv3 server pipeline, TI DNS-SD discovery, LDAPS upstream proxy to TI VZD (runtime + deployment) |

### Runnable servers

| Module                  | Description                                                                                |
| ----------------------- | ------------------------------------------------------------------------------------------ |
| `quarkus-server`        | Main Quarkus application bundling all services; CDI integration; embeds Hawtio             |
| `consumer-soap-server`  | Quarkus application exposing the Basis Consumer SOAP interface (`api-telematik/consumer`)  |
| `konnektor-soap-server` | Quarkus application exposing Konnektor SOAP compatibility interface (`api-telematik/conn`) |

### External submodule

| Module    | Description                                                                                                         |
| --------- | ------------------------------------------------------------------------------------------------------------------- |
| `openkim` | KIM/KOM-LE client module (fork of [med-united/openkim](https://github.com/med-united/openkim), originally by sberg) |

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
