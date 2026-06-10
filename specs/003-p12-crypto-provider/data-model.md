# Data Model: P12 CryptoProvider

**Feature**: `003-p12-crypto-provider` | **Date**: 2026-06-10

## Overview

This feature introduces no new persistent entities. All state is held in-memory within CDI beans for the duration of the application session. The data model documents:
- New classes introduced by this feature (`P12CryptoConfig`, `P12CertScanner`, `P12CertManagement`)
- Existing classes consumed or modified (`P12KeyStoreAdapter`, `KeyAlias`, `CryptoOperationRequest`)

---

## Entity: P12CryptoProvider *(modified)*

**Module**: `crypto-p12-lib`
**Package**: `de.servicehealtherx.crypto.p12`
**Lifecycle**: `@ApplicationScoped` — singleton, initialized once at startup via `@PostConstruct`

| Field | Type | Mutability | Description |
|-------|------|------------|-------------|
| `adapters` | `CopyOnWriteArrayList<P12KeyStoreAdapter>` | Read-only after init; append via MBean | Ordered list for `listKeyStores()` |
| `adapterMap` | `ConcurrentHashMap<String, P12KeyStoreAdapter>` | Read-only after init; append via MBean | Keyed by alias string for O(1) routing |
| `uploadLock` | `ReentrantLock` | Immutable reference | Serializes concurrent `uploadCertificate` calls |
| `config` | `P12CryptoConfig` | Injected, immutable | Provides `certsDir` path |
| `scanner` | `P12CertScanner` | Injected, immutable | Performs classpath copy + filesystem scan at startup |

**Invariants**:
- `adapters` and `adapterMap` are always consistent: every adapter in the list is in the map and vice versa
- Both structures are fully populated before the bean is visible to callers (CDI `@PostConstruct` guarantee)

---

## Entity: P12CertScanner *(new)*

**Module**: `crypto-p12-lib`
**Package**: `de.servicehealtherx.crypto.p12`
**Lifecycle**: `@ApplicationScoped` CDI bean (stateless — performs work on invocation)

Responsibilities:
1. **Bootstrap copy**: Enumerate classpath entries under `certsDir`, copy `.p12`/`.pfx` + sibling `password.txt` to filesystem certs dir (skip-if-present)
2. **Filesystem scan**: Walk `certsDir` recursively, for each `.p12`/`.pfx` file: read sibling `password.txt`, derive `KeyAlias`, construct and load `P12KeyStoreAdapter`
3. Return `List<P12KeyStoreAdapter>` (includes error-state adapters for files that fail to load)

**Alias derivation** (from filesystem path relative to certs root):
```
relative path → lowercase → replace non-[a-z0-9-_.] chars with '-'
             → collapse runs of '-' → trim per-segment → prefix "p12/"
```

---

## Entity: P12CryptoConfig *(new)*

**Module**: `crypto-p12-lib`
**Package**: `de.servicehealtherx.crypto.p12`

Quarkus `@ConfigMapping(prefix = "quarkus.crypto.p12")`:

| Method | Type | Config key | Description |
|--------|------|------------|-------------|
| `certsDir()` | `String` | `quarkus.crypto.p12.certs-dir` | Absolute or working-dir-relative filesystem path to the certs folder |

No production default. Tests set this to a temp directory pre-populated from test resources.

---

## Entity: P12CertManagement *(new)*

**Module**: `crypto-p12-lib`
**Package**: `de.servicehealtherx.crypto.p12`
**Lifecycle**: `@ApplicationScoped`; registered as JMX MBean at `@PostConstruct`

| Field | Type | Description |
|-------|------|-------------|
| `provider` | `P12CryptoProvider` | CDI injection; used to register newly uploaded adapters |

JMX Object name: `de.servicehealtherx:module=crypto-p12-lib,name=P12CertManagement`

Operations (see [MBean contract](contracts/P12CertManagement-MBean.md)):

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `uploadCertificate` | `(String alias, byte[] p12Data, String password): void` | Validate, persist to disk, register adapter |
| `listCertificates` | `(): String` | JSON array of currently loaded alias strings |

---

## Entity: P12CertManagementMBean *(new, interface)*

**Module**: `crypto-p12-lib`
**Package**: `de.servicehealtherx.crypto.p12`

Standard JMX MBean interface (name = `P12CertManagement` + `MBean` suffix):

```java
public interface P12CertManagementMBean {
    void uploadCertificate(String alias, byte[] p12Data, String password);
    String listCertificates();
}
```

---

## Entity: P12KeyStoreAdapter *(modified — crypto-lib)*

**Module**: `crypto-lib`
**Package**: `de.servicehealtherx.crypto.adapter`

Existing fields unchanged. New behavior added to `sign()` and `verify()`:

| Field | Type | Mutability | Description |
|-------|------|------------|-------------|
| `alias` | `KeyAlias` | Immutable | Identifies this keystore entry |
| `p12Path` | `String` | Immutable | Absolute filesystem path to the `.p12` file |
| `keystorePassword` | `char[]` | Immutable | PKCS12 container password |
| `entryPassword` | `char[]` | Immutable | Private key entry password (= keystorePassword) |
| `keyStore` | `KeyStore` (volatile) | Set once on `engineLoad` | JDK `KeyStore` |
| `descriptor` | `KeyStoreDescriptor` | Mutable (thread-safe) | Tracks availability |

**Algorithm resolution** (new, in `sign` and `verify`):
```
resolveAlgorithm(privateKey, request.algorithm):
  if request.algorithm != null && !blank → return request.algorithm
  if key.getAlgorithm() == "EC"  → return "SHA256withECDSA"
  if key.getAlgorithm() == "RSA" → configure PSSParameterSpec, return "SHA256withRSA/PSS"
  else → throw IllegalStateException("No default algorithm for key type …")
```

`CryptoOperationResult.algorithm` returns the **resolved** algorithm (never null).

**State transitions** (unchanged):
```
UNAVAILABLE ──(engineLoad succeeds)──► AVAILABLE
UNAVAILABLE ──(engineLoad fails)────► ERROR
```

---

## Entity: KeyAlias *(modified — crypto-lib)*

**Module**: `crypto-lib`
**Package**: `de.servicehealtherx.crypto`

Pattern extended from single-segment to multi-segment:

| Before | After |
|--------|-------|
| `^(p12\|pkcs11\|pcsc\|sicct)/[a-z0-9\-_]+$` | `^(p12\|pkcs11\|pcsc\|sicct)(/[a-z0-9][a-z0-9\-_.]*)+$` |

Max length constraint (128 chars) and source-type extraction are unchanged.

---

## Entity: CryptoOperationRequest *(no change — crypto-lib)*

| Field | Type | Notes |
|-------|------|-------|
| `alias` | `KeyAlias` | Target keystore (must be `p12/...`) |
| `algorithm` | `String` | **Nullable** — null triggers key-type auto-detection in the adapter |
| `data` | `byte[]` | Input bytes |
| `callerIdentity` | `String` | Audit logging |

---

## Entity: CryptoOperationResult *(no change — crypto-lib)*

| Field | Type | Notes |
|-------|------|-------|
| `alias` | `KeyAlias` | Performing alias |
| `result` | `byte[]` | Signature, plaintext, etc. |
| `certificate` | `X509Certificate` | Leaf cert (null for decrypt) |
| `algorithm` | `String` | **Resolved** algorithm — never null |

---

## Relationship Diagram

```
quarkus.crypto.p12.certs-dir (config)
         │
         ▼
  P12CryptoConfig
         │ certsDir
         ▼
  P12CertScanner ─────────────────────────────────────────────────────────┐
         │ 1. bootstrap copy: classpath → filesystem                      │
         │ 2. scan filesystem, read password.txt, derive alias            │
         ▼                                                                 │
  P12CryptoProvider (@ApplicationScoped, implements CryptoProvider)       │
         │ CopyOnWriteArrayList + ConcurrentHashMap of adapters           │
         │                                                                 │
         ├──► P12KeyStoreAdapter [alias=p12/zeta/smcb-aut]               │
         │         └──► KeyStoreDescriptor                                │
         │         └──► KeyStore (PKCS12)                                 │
         │                                                                 │
         ├──► P12KeyStoreAdapter [alias=p12/zeta/smcb-enc]               │
         │         └──► KeyStoreDescriptor                                │
         │         └──► KeyStore (PKCS12)                                 │
         │                                                                 │
         └──► P12KeyStoreAdapter [alias=p12/zeta/smcb-osig]              │
                   └──► KeyStoreDescriptor                                │
                   └──► KeyStore (PKCS12)                                 │
                                                                          │
  P12CertManagement (@ApplicationScoped, JMX MBean) ──────────────────────┘
         │ uploadCertificate: validate → write disk → register adapter
         │ listCertificates: delegates to P12CryptoProvider
```
