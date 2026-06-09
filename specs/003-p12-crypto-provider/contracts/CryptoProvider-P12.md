# Contract: P12CryptoProvider implements CryptoProvider

**Feature**: `003-p12-crypto-provider` | **Date**: 2026-06-09

This document specifies the precise behavioural contract of each `CryptoProvider` method as implemented by `P12CryptoProvider`. It is the authoritative reference for test authors and callers of this provider.

---

## Preconditions (all methods)

- The `P12CryptoProvider` bean has completed `@PostConstruct` initialization.
- `request` is not null.
- `request.alias` is not null and conforms to `KeyAlias` format.

---

## `sign(CryptoOperationRequest request): CryptoOperationResult`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | Sign `request.data` with the private key from the identified P12 keystore entry |
| **Alias constraint** | `request.alias.sourceType()` MUST be `SourceType.P12` |
| **Alias routing** | Adapter with key `request.alias.value()` MUST exist in `adapters` |
| **Availability** | Adapter MUST be in `AVAILABLE` state (`keyStore != null`) |
| **Returns** | `CryptoOperationResult` with: `alias = request.alias`, `result = signature bytes`, `certificate = leaf X509Certificate from keystore`, `algorithm = request.algorithm` |
| **Throws** | `IllegalArgumentException` if alias SourceType is not P12 |
| **Throws** | `IllegalArgumentException` if no adapter registered for alias |
| **Throws** | `IllegalStateException` if adapter is not in AVAILABLE state |
| **Throws** | `RuntimeException` wrapping the cause on JCA errors |

---

## `verify(CryptoOperationRequest request, byte[] signature): boolean`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | Verify `signature` against `request.data` using the public key from the identified keystore entry |
| **Alias constraint** | `request.alias.sourceType()` MUST be `SourceType.P12` |
| **Alias routing** | Adapter with key `request.alias.value()` MUST exist in `adapters` |
| **Availability** | Adapter MUST be in `AVAILABLE` state |
| **Returns** | `true` if the signature is cryptographically valid; `false` otherwise |
| **Throws** | Same as `sign` |

---

## `encrypt(CryptoOperationRequest request): CryptoOperationResult`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | NOT SUPPORTED by this provider |
| **Always throws** | `UnsupportedOperationException` with message: `"P12CryptoProvider does not support encrypt — use the certificate's public key directly for asymmetric encryption"` |
| **Rationale** | Asymmetric encryption in TI workflows is performed by the caller using the peer certificate's public key; the private key is never used for encryption |

---

## `decrypt(CryptoOperationRequest request): CryptoOperationResult`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | Decrypt `request.data` with the private key from the identified P12 keystore entry |
| **Alias constraint** | `request.alias.sourceType()` MUST be `SourceType.P12` |
| **Alias routing** | Adapter with key `request.alias.value()` MUST exist in `adapters` |
| **Availability** | Adapter MUST be in `AVAILABLE` state |
| **Returns** | `CryptoOperationResult` with: `alias = request.alias`, `result = plaintext bytes`, `certificate = null`, `algorithm = request.algorithm` |
| **Throws** | Same as `sign`; additionally `RuntimeException` on JCA decryption failure (wrong key, wrong algorithm, corrupted ciphertext) |

---

## `listKeyStores(): List<KeyStoreDescriptor>`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | Return metadata for all P12 keystores managed by this provider |
| **Returns** | Unmodifiable `List<KeyStoreDescriptor>` in configuration order (order adapters were defined in `quarkus.crypto.p12[*]`) |
| **Size** | Equals the number of configured `quarkus.crypto.p12` entries |
| **Availability states** | Each descriptor reflects the current availability; may be `AVAILABLE`, `UNAVAILABLE` (initial before load), or `ERROR` (load failed) |
| **Thread safety** | Safe to call concurrently; list and descriptor references are stable |

---

## `getAvailability(KeyAlias alias): KeyStoreAvailability`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | Return availability for a specific alias |
| **Returns** | `AVAILABLE` / `ERROR` if alias is managed by this provider; `UNAVAILABLE` if alias is unknown to this provider (regardless of SourceType) |
| **Thread safety** | Safe to call concurrently |

---

## `getAvailabilities(): Map<String, KeyStoreAvailability>`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | Return a snapshot of all alias → availability mappings for this provider |
| **Returns** | `Map<String, KeyStoreAvailability>` keyed by alias string, containing all `p12/...` aliases managed by this provider |
| **Size** | Equals the number of configured `quarkus.crypto.p12` entries |
| **Thread safety** | Safe to call concurrently |

---

## Error Message Requirements

| Scenario | Log Level | Password in Message |
|----------|-----------|---------------------|
| P12 file not found at startup | WARN | MUST NOT appear |
| Wrong keystore password at startup | WARN | MUST NOT appear |
| Operation on unavailable alias | (throws, no log) | N/A |
| JCA algorithm not found | ERROR (in adapter) | MUST NOT appear |
