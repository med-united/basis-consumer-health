# Diagrams: ECIES Transport Encryption

Render with [PlantUML](https://plantuml.com/) or view directly in GitHub using the
[PlantUML for GitHub](https://github.com/plantuml/plantuml-for-github) browser extension.

Proxy image link format:
`https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/009-ecies-encryption-service/specs/009-ecies-encryption-service/diagrams/<file>.puml`

| Priority | Diagram | File | Description |
|---|---|---|---|
| 1 | Use Case | [use-case.puml](use-case.puml) | Caller / TI partner / smart card interactions with EncryptionService |
| 3 | Component | [component.puml](component.puml) | `crypto-services-lib` → `crypto-lib` ecies + JCE `ELC` provider; card decryptor in provider modules |
| 4 | Sequence — Encrypt | [sequence-encrypt.puml](sequence-encrypt.puml) | Transport key + AES-256-GCM + per-recipient ELC wrap → CMS AuthEnvelopedData |
| 4 | Sequence — Decrypt (card) | [sequence-decrypt-card.puml](sequence-decrypt-card.puml) | Alias select → JCE `Cipher("ELC")` → `PSO:DECIPHER` → AES-GCM decrypt |
| 6 | Class | [class-ecies.puml](class-ecies.puml) | ecies domain + `Provider`/`CipherSpi`/`ElcDecryptor` seam |

### Rendered previews

**Use Case** — ![use-case](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/009-ecies-encryption-service/specs/009-ecies-encryption-service/diagrams/use-case.puml)

**Component** — ![component](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/009-ecies-encryption-service/specs/009-ecies-encryption-service/diagrams/component.puml)

**Sequence — Encrypt** — ![sequence-encrypt](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/009-ecies-encryption-service/specs/009-ecies-encryption-service/diagrams/sequence-encrypt.puml)

**Sequence — Decrypt (card)** — ![sequence-decrypt-card](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/009-ecies-encryption-service/specs/009-ecies-encryption-service/diagrams/sequence-decrypt-card.puml)

**Class** — ![class-ecies](https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/med-united/basis-consumer-health/009-ecies-encryption-service/specs/009-ecies-encryption-service/diagrams/class-ecies.puml)

### Omitted diagram types (Principle VI justification)
- **Deployment**: unchanged — the feature adds no new node/network topology; it runs inside the existing crypto service pod (see feature 001/002 deployment).
- **State chart**: encrypt/decrypt are single-shot transformations; no domain entity has a multi-state lifecycle (transient keys are create→use→zeroize). See [data-model.md](../data-model.md).
