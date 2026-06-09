# Data Model: P12 CryptoProvider

**Feature**: `003-p12-crypto-provider` | **Date**: 2026-06-09

## Overview

This feature introduces no new persistent entities. All state is held in-memory within the CDI bean for the duration of the application session. The model below documents the relationships between the existing classes that `P12CryptoProvider` orchestrates.

---

## Entity: P12CryptoProvider

**Module**: `crypto-p12-lib`  
**Package**: `de.servicehealtherx.crypto.p12`  
**Lifecycle**: `@ApplicationScoped` — singleton, initialized once at startup via `@PostConstruct`

| Field | Type | Mutability | Description |
|-------|------|------------|-------------|
| `adapters` | `Map<String, P12KeyStoreAdapter>` | Read-only after `@PostConstruct` | Keyed by alias string (`p12/<name>`), in configuration order |

**Invariants**:
- `adapters` is fully populated before the bean is exposed to callers (CDI `@PostConstruct` guarantee)
- Keys match `p12/<name>` pattern (enforced by `KeyAlias`)
- No entry is ever added or removed after init (no hot-reload in this feature)

---

## Entity: P12KeyStoreAdapter *(existing, in `crypto-lib`)*

**Module**: `crypto-lib`  
**Package**: `de.servicehealtherx.crypto.adapter`  
**Lifecycle**: Plain Java object, owned by `P12CryptoProvider`

| Field | Type | Mutability | Description |
|-------|------|------------|-------------|
| `alias` | `KeyAlias` | Immutable | Identifies this keystore entry (e.g., `p12/smcb-aut`) |
| `p12Path` | `String` | Immutable | Filesystem path to the `.p12` file |
| `keystorePassword` | `char[]` | Immutable | Password for the PKCS12 container |
| `entryPassword` | `char[]` | Immutable | Password for the private key entry (defaults to `keystorePassword`) |
| `keyStore` | `KeyStore` (volatile) | Set once on successful `engineLoad` | JDK `KeyStore` loaded from the `.p12` file |
| `descriptor` | `KeyStoreDescriptor` | Mutable (thread-safe via volatile fields) | Tracks availability and error state |

**State transitions** (on `engineLoad`):

```
UNAVAILABLE ──(load succeeds)──► AVAILABLE
UNAVAILABLE ──(load fails)────► ERROR
```

---

## Entity: KeyStoreDescriptor *(existing, in `crypto-lib`)*

**Module**: `crypto-lib`  
**Package**: `de.servicehealtherx.crypto`

| Field | Type | Description |
|-------|------|-------------|
| `alias` | `KeyAlias` | The alias this descriptor belongs to |
| `sourceType` | `SourceType` | Always `P12` for adapters managed by this provider |
| `availability` | `KeyStoreAvailability` (volatile) | `AVAILABLE`, `UNAVAILABLE`, or `ERROR` |
| `errorMessage` | `String` (volatile) | Set when `markError()` is called; `null` otherwise |
| `lastUpdated` | `Instant` (volatile) | Updated on every state transition |

---

## Entity: CryptoOperationRequest *(existing, in `crypto-lib`)*

Carries a single crypto operation's inputs:

| Field | Type | Description |
|-------|------|-------------|
| `alias` | `KeyAlias` | Target keystore (must be `p12/...` for this provider) |
| `algorithm` | `String` | JCA algorithm string (e.g., `"SHA256withECDSA"`, `"ECIES"`) |
| `data` | `byte[]` | Input bytes (data to sign/verify/decrypt) |
| `callerIdentity` | `String` | Opaque identifier for audit logging |

---

## Entity: CryptoOperationResult *(existing, in `crypto-lib`)*

Carries a single crypto operation's outputs:

| Field | Type | Description |
|-------|------|-------------|
| `alias` | `KeyAlias` | The alias that performed the operation |
| `result` | `byte[]` | Output bytes (signature bytes, plaintext, etc.) |
| `certificate` | `X509Certificate` | Leaf certificate from the keystore entry (null for decrypt) |
| `algorithm` | `String` | Algorithm used (echoed from request) |

---

## Configuration Binding: P12KeyStoreConfig *(existing, in `crypto-lib`)*

Quarkus `@ConfigMapping` bound to `quarkus.crypto.p12`:

```
quarkus.crypto.p12[0].alias             = p12/smcb-aut
quarkus.crypto.p12[0].path              = /path/to/smcb-aut.p12
quarkus.crypto.p12[0].keystore-password = 00
quarkus.crypto.p12[0].entry-password    = (optional, defaults to keystore-password)
quarkus.crypto.p12[0].entry-alias       = (optional, first entry in keystore used if absent)

quarkus.crypto.p12[1].alias             = p12/smcb-enc
...

quarkus.crypto.p12[2].alias             = p12/smcb-osig
...
```

Each `P12Entry` maps to one `P12KeyStoreAdapter` instance created during `@PostConstruct`.

---

## Relationship Diagram

```
P12KeyStoreConfig (CDI injected)
    │
    │ 1..* P12Entry (per configured keystore)
    ▼
P12CryptoProvider (@ApplicationScoped)
    │ owns Map<String, P12KeyStoreAdapter>
    │
    ├──► P12KeyStoreAdapter [alias=p12/smcb-aut]
    │         └──► KeyStoreDescriptor
    │         └──► KeyStore (PKCS12, loaded from file)
    │
    ├──► P12KeyStoreAdapter [alias=p12/smcb-enc]
    │         └──► KeyStoreDescriptor
    │         └──► KeyStore (PKCS12, loaded from file)
    │
    └──► P12KeyStoreAdapter [alias=p12/smcb-osig]
              └──► KeyStoreDescriptor
              └──► KeyStore (PKCS12, loaded from file)
```
