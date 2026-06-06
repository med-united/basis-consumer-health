# Contract: CryptoProvider CDI Interface

**Feature**: `001-quarkus-basis-consumer` | **Date**: 2026-06-05 (updated 2026-06-06)

This document defines the CDI interface contract for the unified cryptographic provider and the `KeyStoreAdapter` abstract base class that all key store implementations extend.

---

## Interface: `CryptoProvider`

**Package**: `de.servicehealtherx.crypto`
**CDI scope**: `@ApplicationScoped` (injected by `RoutingCryptoProvider`)

### Operations

#### `sign(alias, params, data) → byte[]`

Perform a signing operation using the private key identified by `alias`.

**Preconditions**:
- `alias` must be registered and currently AVAILABLE
- `params.algorithm` must be `ECDSA`, `RSA_PSS`, or `RSA_PKCS1`
- `data` is the raw bytes to sign (document hash or raw document, depending on algorithm)

**Postconditions**:
- Returns DER-encoded signature bytes
- Operation recorded in the structured audit log (alias, algorithm, caller, success)
- Private key bytes do NOT appear in result, logs, or any intermediate variable outside the adapter

**Failure modes**:
- `KeyNotFoundException`: alias not registered
- `KeyStoreUnavailableException`: alias registered but adapter currently UNAVAILABLE or ERROR
- `UnsupportedAlgorithmException`: algorithm not supported by the key type
- `CryptoOperationException`: underlying operation failed (HSM error, card error)
- `OperationTimeoutException`: exceeded configured timeout (SICCT and PC/SC only)

#### `verify(alias, params, data, signature) → boolean`

Verify a signature using the public key / certificate associated with `alias`.

**Preconditions**:
- `alias` must be registered
- `signature` is the DER-encoded signature bytes to verify
- `data` is the original signed data

**Postconditions**:
- Returns `true` if signature is valid, `false` if invalid
- Operation recorded in audit log

**Failure modes**: Same as `sign` (except `KeyStoreUnavailableException` — `verify` uses the public key/cert, which is always extractable even when the private key is unavailable)

#### `encrypt(alias, params, plaintext) → byte[]`

Encrypt using the public key of the certificate associated with `alias`.

#### `decrypt(alias, params, ciphertext) → byte[]`

Decrypt using the private key identified by `alias`. Same failure modes as `sign`.

**Postconditions**:
- Returns plaintext bytes; no certificate in result

#### `listKeyStores() → List<KeyStoreAdapter>`

Return all registered `KeyStoreAdapter` instances. Each adapter exposes its aliases via `engineAliases()`.

**Postconditions**:
- Result is a snapshot; adapters may be added/removed between calls
- Returns empty list when no key stores are configured

#### `getAvailability(alias) → AvailabilityStatus`

**Failure modes**: `KeyNotFoundException` if alias not registered in any adapter.

#### `getAvailabilities() → Map<String, AvailabilityStatus>`

Return availability of all aliases across all adapters as a snapshot.

---

## Abstract Class: `KeyStoreAdapter`

**Package**: `de.servicehealtherx.crypto`
**Extends**: `java.security.KeyStoreSpi`

Each hardware or software key store (P12, PKCS#11, PC/SC, SICCT) extends `KeyStoreAdapter`. This makes every adapter a fully compliant JCA `KeyStoreSpi` that can be wrapped in a `java.security.KeyStore` facade if needed.

### Inherited `KeyStoreSpi` contract (all `engine*` methods are `protected abstract`)

| Method | Behaviour |
|--------|-----------|
| `engineGetKey(alias, password)` | Returns `PrivateKeyReference` or `SecretKeyReference`; returns `null` if alias not found. For hardware stores `password` is ignored (PIN is configured separately). |
| `engineGetCertificate(alias)` | Returns the X.509 certificate for the given alias; `null` if not found. Used by `verify` and `encrypt` to obtain the public key. |
| `engineGetCertificateChain(alias)` | Full certificate chain; `null` if alias not found or no chain stored. |
| `engineAliases()` | Enumeration of all aliases managed by this adapter. |
| `engineContainsAlias(alias)` | Returns `true` if this adapter owns the alias. |
| `engineSize()` | Number of entries. |
| `engineIsKeyEntry(alias)` | Returns `true` if the alias has an associated private or secret key. |
| `engineIsCertificateEntry(alias)` | Returns `true` if the alias has only a certificate (no private key). |
| `engineGetCertificateAlias(cert)` | Reverse lookup by certificate. |
| `engineLoad(stream, password)` | Initialize the adapter. `stream=null` means use the configured connection/path (all hardware adapters use `null`). `P12KeyStoreAdapter` reads the P12 file from `stream` or from its configured path when `stream=null`. |
| `engineStore(stream, password)` | Persist state. `P12KeyStoreAdapter` writes the updated keystore to `stream`. Hardware adapters (`Pkcs11`, `PcscKeyStoreAdapter`, `SicctKeyStoreAdapter`) throw `KeyStoreException("store not supported for hardware key stores")`. |
| `engineSetKeyEntry(...)` | `P12KeyStoreAdapter`: add/replace key entry. Hardware adapters: `KeyStoreException`. |
| `engineDeleteEntry(alias)` | `P12KeyStoreAdapter`: removes entry. Hardware adapters: `KeyStoreException`. |

### Added crypto operation methods

| Method | Description |
|--------|-------------|
| `sign(keyRef: PrivateKeyReference, params, data): byte[]` | Execute signing on this adapter using the referenced private key. |
| `verify(keyRef: PublicKeyReference, params, data, signature): boolean` | Execute signature verification using the referenced public key/cert. |
| `encrypt(keyRef: PublicKeyReference, params, plaintext): byte[]` | Encrypt with the referenced public key. |
| `decrypt(keyRef: PrivateKeyReference, params, ciphertext): byte[]` | Decrypt with the referenced private key. |
| `getAvailability(): AvailabilityStatus` | Current availability of this adapter (AVAILABLE / UNAVAILABLE / ERROR). |

### `engineGetKey` covariant return type override

Each concrete implementation overrides `engineGetKey` with a covariant return type:

```java
@Override
public PrivateKeyReference engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException, UnrecoverableKeyException { ... }
```

This is valid because `PrivateKeyReference extends java.security.Key`. The `password` parameter is:
- `P12KeyStoreAdapter`: used as the entry password (falls back to `entryPassword` from config if `null`)
- `Pkcs11KeyStoreAdapter`, `PcscKeyStoreAdapter`, `SicctKeyStoreAdapter`: ignored; PIN/token authentication uses adapter-internal configuration

---

## Routing Rule

`RoutingCryptoProvider` resolves an alias to the responsible adapter by iterating `listKeyStores()` and calling `engineContainsAlias(alias)`:

| Alias prefix | Concrete adapter |
|-------------|-----------------|
| `p12/`      | `P12KeyStoreAdapter` |
| `pkcs11/`   | `Pkcs11KeyStoreAdapter` |
| `pcsc/`     | `PcscKeyStoreAdapter` |
| `sicct/`    | `SicctKeyStoreAdapter` |

If no adapter contains the alias, throws `KeyNotFoundException`.

**Routing pattern** (example for `sign`):
```
alias = "sicct/praxis-a-slot0"
adapter = listKeyStores().stream()
    .filter(a -> a.engineContainsAlias(alias))
    .findFirst()
    .orElseThrow(KeyNotFoundException::new)
keyRef = (PrivateKeyReference) adapter.engineGetKey(alias, null)
return adapter.sign(keyRef, params, data)
```

---

## Audit Log Entry Schema

Every call to `sign()`, `verify()`, `encrypt()`, or `decrypt()` MUST produce a structured log entry at `INFO` level:

```json
{
  "event": "crypto_operation",
  "alias": "pkcs11/utimaco-slot0",
  "operationType": "SIGN",
  "algorithm": "SHA256withECDSA",
  "callerIdentity": "tenant-abc/signature-service",
  "success": true,
  "durationMs": 47,
  "timestampUtc": "2026-06-05T10:30:00.000Z"
}
```

On failure, add:
```json
  "success": false,
  "errorType": "KeyStoreUnavailableException",
  "errorMessage": "SICCT terminal terminal-1 is disconnected"
```

**Key material MUST NOT appear in any log field.**

---

## Health Check Contract

`CryptoProviderHealthCheck` implements `HealthCheck` and is registered as `@Readiness`.

**Response when all aliases available**:
```json
{
  "status": "UP",
  "checks": [{ "name": "crypto-provider", "status": "UP",
               "data": { "totalAliases": 4, "availableAliases": 4 } }]
}
```

**Response when any alias unavailable**:
```json
{
  "status": "DOWN",
  "checks": [{ "name": "crypto-provider", "status": "DOWN",
               "data": { "totalAliases": 4, "availableAliases": 3,
                         "unavailable": ["sicct/terminal-1-slot0"] } }]
}
```
