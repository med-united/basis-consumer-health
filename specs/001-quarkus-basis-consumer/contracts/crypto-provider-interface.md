# Contract: CryptoProvider CDI Interface

**Feature**: `002-pluggable-jce-provider` | **Date**: 2026-06-05

This document defines the CDI interface contract for the unified cryptographic provider. All internal Basis Consumer services that perform signing or decryption MUST use this interface exclusively.

---

## Interface: `CryptoProvider`

**Package**: `health.basis.consumer.crypto`
**CDI scope**: `@ApplicationScoped` (injected by `RoutingCryptoProvider`)

### Operations

#### `sign(CryptoOperationRequest request) → CryptoOperationResult`

Perform a non-QES signing operation using the private key identified by `request.alias`.

**Preconditions**:
- `request.alias` must be a registered and currently AVAILABLE alias
- `request.operationType` must be `SIGN`
- `request.algorithm` must be a JCA algorithm supported by the backing key type (e.g., `SHA256withECDSA` for ECC, `SHA256withRSA` for RSA)
- `request.data` is the raw input to be signed (digest or document, depending on algorithm)

**Postconditions**:
- Returns a `CryptoOperationResult` with non-null `result` (DER-encoded signature bytes)
- Returns a `CryptoOperationResult` with `certificate` set to the signing certificate
- The operation is recorded in the structured audit log (alias, algorithm, caller, success)
- Private key bytes do NOT appear in the result, in logs, or in any intermediate variable accessible outside the adapter

**Failure modes**:
- `KeyNotFoundException`: alias not registered
- `KeySourceUnavailableException`: alias registered but key source currently UNAVAILABLE or ERROR
- `UnsupportedAlgorithmException`: algorithm not supported by the key type of this alias
- `CryptoOperationException`: underlying signing operation failed (e.g., HSM error, card error)
- `OperationTimeoutException`: operation exceeded configured timeout (SICCT and PC/SC only)

#### `decrypt(CryptoOperationRequest request) → CryptoOperationResult`

Perform a decryption operation using the private key identified by `request.alias`.

**Preconditions** and **Failure modes**: Same as `sign`, except `request.operationType` must be `DECRYPT`.

**Postconditions**:
- Returns `CryptoOperationResult` with non-null `result` (plaintext bytes)
- `result.certificate` is null (decryption does not produce a certificate output)

#### `listAliases() → List<KeyAlias>`

Return all currently registered aliases (regardless of availability state).

**Postconditions**:
- Result is a snapshot; aliases may change between calls
- Returns empty list when no key sources are configured

#### `getAvailability(KeyAlias alias) → KeySourceAvailability`

Return the current availability state of a specific alias.

**Failure modes**:
- `KeyNotFoundException`: alias not registered

#### `getAvailabilities() → Map<KeyAlias, KeySourceAvailability>`

Return availability state of all registered aliases as a snapshot.

---

## Interface: `KeySourceAdapter`

Each key source type (P12, PKCS#11, PC/SC, SICCT) implements this internal adapter interface, registered as a CDI bean qualified with its source type.

**Package**: `health.basis.consumer.crypto.provider`

### Operations

#### `supports(KeyAlias alias) → boolean`
Returns `true` if this adapter is responsible for the given alias (i.e., alias prefix matches this adapter's source type).

#### `sign(CryptoOperationRequest request) → CryptoOperationResult`
#### `decrypt(CryptoOperationRequest request) → CryptoOperationResult`
#### `getAvailability(KeyAlias alias) → KeySourceAvailability`
#### `listAliases() → List<KeyAlias>`

Same semantics as `CryptoProvider`; scoped to this adapter's managed aliases only.

---

## Routing Rule

`RoutingCryptoProvider` selects the adapter by matching `alias.sourceType`:

| Source Type Prefix | Adapter Bean |
|-------------------|--------------|
| `p12` | `P12CryptoProvider` |
| `pkcs11` | `Pkcs11CryptoProvider` |
| `pcsc` | `PcscCryptoProvider` |
| `sicct` | `SicctCryptoProvider` |

If no adapter matches, throws `KeyNotFoundException`.

---

## Audit Log Entry Schema

Every call to `sign()` or `decrypt()` MUST produce a structured log entry at `INFO` level:

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
  "errorType": "KeySourceUnavailableException",
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
