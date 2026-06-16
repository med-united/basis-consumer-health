# Implementation Plan: ECIES Transport Encryption in EncryptionService

**Branch**: `009-ecies-encryption-service` | **Date**: 2026-06-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/009-ecies-encryption-service/spec.md`

## Summary

Replace the placeholder hybrid key-wrap in `EncryptionService` with the **gematik TI-ECIES transport-encryption** scheme of gemSpec_Krypt §4.7 (Afo **A_17220**). A document is encrypted once with a random 256-bit AES **transport key** using **AES-256-GCM** (RFC 5084), packaged as a CMS **`AuthEnvelopedData`** (RFC 5083). For **each** elliptic-curve recipient the transport key is wrapped with ECIES (gemSpec_COS ELC, §6.8.1.4 / §6.8.2.3) and carried in a `KeyTransRecipientInfo` whose `keyEncryptionAlgorithm` is **`oid_ti_ecies_transport_encryption` (1.2.276.0.76.4.222)** and whose `encryptedKey` is the **card-compatible `(PO, C, T)` ASN.1 tuple** (ephemeral public point, AES-CBC ciphertext of the transport key, AES-CMAC tag). Multi-recipient is one `RecipientInfo` per recipient over a single shared content ciphertext.

Encryption needs only the recipient's public key (no card). **Decryption** unwraps the transport key by performing the ELC operation with the recipient's private key, which may be software-resident (P12) or held on a smart card (eGK/SMC-B/HBA). Per the clarification, this is exposed through a **standard JCE `Cipher`/`CipherSpi`** seam (`Cipher.getInstance("ELC", …)`): the software path runs ECKA + X9.63-KDF/SHA-256 + AES-CBC + CMAC via Bouncy Castle; the card path issues `PSO:DECIPHER` APDUs (gemSpec_COS §6.8.2.3) under the hood. `EncryptionService` calls only JCE and stays unaware of whether the key is software- or card-resident.

The ECIES/CMS primitives and the JCE provider live in **`crypto-lib`** (the crypto layer); orchestration stays in **`EncryptionService`** in `crypto-services-lib`.

## Technical Context

**Language/Version**: Java 21 LTS

**Primary Dependencies**:

| Dependency | Purpose |
|---|---|
| Bouncy Castle `bcpkix-jdk18on` / `bcprov-jdk18on` (already in `crypto-lib`) | CMS `AuthEnvelopedData` build/parse (`CMSAuthEnvelopedData*`), AES-256-GCM content encryption, EC key agreement, X9.63 KDF, AES-CMAC, ASN.1 (`ASN1*`) for the `(PO, C, T)` structure and brainpool curve OIDs |
| gemLibPki 4.0.2 (already in `crypto-lib`) | Recipient-certificate trust verification reuse via existing `TrustService` |
| Java JCE (JDK) | `Cipher`, `MessageDigest`, `KeyAgreement`, `KeyPairGenerator`, `Provider`, `CipherSpi` — the abstraction mandated by FR-014/FR-015 |
| `javax.smartcardio` + existing `crypto-pcsc-lib` / `crypto-sicct-lib` transports | Card-backed ELC decryption path (`PSO:DECIPHER`) behind the `CipherSpi` |
| JUnit 5 + Quarkus Test, Mockito (already present) | Unit + integration tests, including gematik test-vector round-trip |

**Storage**: N/A — no persistence. Transport keys and ephemeral private keys are transient and zeroized after use.

**Testing**: JUnit 5 unit tests for the ECIES/ELC primitive, the `(PO,C,T)` codec, and CMS assembly against the **gematik test vectors** (§4.7 notes gematik supplies Beispiel-Chiffrate on request); `@QuarkusTest` integration tests for `EncryptionService` round-trip over a software (P12) key and a faked card `CipherSpi`; cross-tool decryption against an OpenSSL/BC reference for SC-002.

**Target Platform**: Linux server, JVM mode, Kubernetes pod (consistent with features 001/002).

**Project Type**: Extension to the existing multi-module Maven project — primitives in `crypto-lib`, orchestration in `crypto-services-lib`.

**Performance Goals**: Per-operation budget consistent with the service today (SC-007). The document body is encrypted/decrypted off-card in software; only the ≤ ~120-byte `(PO,C,T)` transport-key cryptogram is sent to a card, keeping card round-trips within the 8 KiB APDU limit (§4.7 rationale).

**Constraints**: Curves limited to those TI cards/COS support for ELC — **brainpoolP256r1** (primary; the §4.7 example), brainpoolP384r1, brainpoolP512r1; curve taken from the recipient certificate (FR-009). EC public keys MUST use **named-curve** encoding (A_23511). 150 MB memory ceiling (Principle IV) — document encrypted in a single buffered pass with a configurable max plaintext size guarding against exhaustion (Edge Cases).

**Scale/Scope**: Single document per call, 1..N recipients (typically ≤ 10). No throughput target beyond existing service load.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Gate | Status | Notes |
|---|---|---|
| Principle I — No premature abstractions / no single-impl interface; no `Impl` suffix | ✅ PASS | The ELC-decryption seam (`ElcDecryptor`) has **two** real implementations (software/P12 and card/`PSO:DECIPHER`), justifying the interface. Names by role: `EciesTransportEncryption`, `ElcCryptogram`, `ElcCipherSpi`, `SoftwareElcDecryptor`, `CardElcDecryptor`. |
| Principle II — Test-first, ≥ 80% coverage, deterministic | ✅ PASS | Test vectors + round-trip; domain logic tested against real BC primitives; card transport replaced by a fake `ElcDecryptor` test double (infrastructure seam only, per "no mocking the domain layer"). |
| Principle V — Security & gematik TI compliance | ✅ PASS | Implements A_17220 / §4.7 normatively; reuses `TrustService`; transport key & ephemeral private key zeroized (FR-012); no secret material in logs/audit (FR-012/013). **Multi-recipient authenticity caveat** (§4.7.1) recorded in research as a known limitation — callers must sign for end-to-end authenticity. |
| Principle VI — UML diagrams | ✅ PASS | use-case, component, sequence-encrypt, sequence-decrypt-card, class diagrams + `diagrams/README.md` produced in Phase 1. State chart omitted (no rich entity lifecycle) — justified in README. |
| Principle VII — External library evaluation | ✅ PASS | Bouncy Castle evaluated and adopted for CMS/ASN.1/EC primitives (a known-good TI candidate); custom code limited to the gematik-specific `(PO,C,T)` card-compatible structure that no library emits natively. Recorded in research.md. |
| Principle VIII — Standard interface adoption | ✅ PASS | Decryption seam adopts the JDK-standard **`java.security.Provider` + `javax.crypto.CipherSpi`** rather than a bespoke interface (consistent with `KeyStoreAdapter extends KeyStoreSpi` already in the codebase). Unneeded SPI methods throw `UnsupportedOperationException`. Recorded in research.md. |
| Quality Gate — SBOM / new dependency | ✅ PASS | BC + gemLibPki already adopted and in the SBOM; **no new third-party dependency** introduced. |

**Result: PASS — no violations. Complexity Tracking not required.**

## Project Structure

### Documentation (this feature)

```text
specs/009-ecies-encryption-service/
├── plan.md              # This file
├── research.md          # Phase 0 — normative decisions (§4.7, COS ELC, library/SPI choices)
├── data-model.md        # Phase 1 — entities & ASN.1 structures
├── quickstart.md        # Phase 1 — validation guide (round-trip + test vectors)
├── contracts/
│   ├── EncryptionService-API.md      # encryptDocument / decryptDocument contract
│   ├── ELC-Cipher-transformation.md  # JCE Cipher("ELC") SPI contract
│   └── ecies-asn1.md                 # CMS AuthEnvelopedData + (PO,C,T) ASN.1 contract
├── diagrams/
│   ├── README.md
│   ├── use-case.puml
│   ├── component.puml
│   ├── sequence-encrypt.puml
│   ├── sequence-decrypt-card.puml
│   └── class-ecies.puml
└── checklists/
    └── requirements.md  # (from /speckit-specify)
```

### Source Code (repository root)

```text
crypto-lib/src/main/java/de/servicehealtherx/crypto/ecies/
├── EciesTransportEncryption.java   # Builds/parses CMS AuthEnvelopedData; orchestrates per-recipient wrap/unwrap
├── ElcCryptogram.java              # ASN.1 codec for the card-compatible (PO, C, T) tuple
├── ElcParameters.java              # Curve + X9.63-KDF(SHA-256) + AES-CBC + CMAC parameter set (COS §6.8.1.4)
├── ElcKeyWrapper.java              # Sender side: ECKA + KDF + AES-CBC + CMAC over the transport key (software)
└── jce/
    ├── ElcSecurityProvider.java    # java.security.Provider registering the "ELC" Cipher
    ├── ElcCipherSpi.java           # CipherSpi: DECRYPT routes software vs card by key type
    ├── SoftwareElcDecryptor.java   # Software (P12 / EC PrivateKey) ELC unwrap via Bouncy Castle
    ├── ElcDecryptor.java           # Seam interface (2 impls: software + card)
    └── CardElcPrivateKey.java      # Card-backed PrivateKey handle (alias + transport ref; no key bytes)

crypto-pcsc-lib / crypto-sicct-lib (existing provider modules)
└── ...CardElcDecryptor             # PSO:DECIPHER APDU implementation of ElcDecryptor (gemSpec_COS §6.8.2.3)

crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/
└── EncryptionService.java          # MODIFIED: encrypt/decrypt delegate to EciesTransportEncryption + JCE Cipher("ELC")

crypto-lib/src/test/java/de/servicehealtherx/crypto/ecies/
├── ElcCryptogramTest.java          # (PO,C,T) encode/decode vs the gematik §4.7 example bytes
├── EciesTransportEncryptionTest.java  # round-trip + multi-recipient + gematik test vectors
└── ElcCipherSpiTest.java           # software path + fake card path
```

**Structure Decision**: Crypto primitives and the JCE `ELC` provider live in **`crypto-lib`** (honouring the "in crypto-lib" intent and keeping crypto out of the service layer). The card-specific `PSO:DECIPHER` implementation of `ElcDecryptor` lives in the existing **`crypto-pcsc-lib` / `crypto-sicct-lib`** provider modules (they own the transports). `EncryptionService` in **`crypto-services-lib`** keeps only orchestration (trust check → build/parse envelope → JCE `Cipher` calls → audit) and gains no smart-card awareness.

## Complexity Tracking

> No constitution violations — section intentionally empty.
