# Contract: P12CryptoProvider implements CryptoProvider

**Feature**: `003-p12-crypto-provider` | **Date**: 2026-06-10 (updated)

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
| **Algorithm** | If `request.algorithm` is null or blank: EC key → `SHA256withECDSA`; RSA key → `SHA256withRSA/PSS` (PSS salt=32). Explicit `request.algorithm` overrides auto-detection. |
| **Returns** | `CryptoOperationResult` with: `alias = request.alias`, `result = signature bytes`, `certificate = leaf X509Certificate from keystore`, `algorithm = resolved algorithm (never null)` |
| **Throws** | `IllegalArgumentException` if alias SourceType is not P12 |
| **Throws** | `IllegalArgumentException` if no adapter registered for alias |
| **Throws** | `IllegalStateException` if adapter is not in AVAILABLE state |
| **Throws** | `IllegalStateException` if no default algorithm exists for the key type and `request.algorithm` is null |
| **Throws** | `RuntimeException` wrapping the cause on JCA errors |

---

## `verify(CryptoOperationRequest request, byte[] signature): boolean`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | Verify `signature` against `request.data` using the public key from the identified keystore entry |
| **Alias constraint** | `request.alias.sourceType()` MUST be `SourceType.P12` |
| **Alias routing** | Adapter with key `request.alias.value()` MUST exist in `adapters` |
| **Availability** | Adapter MUST be in `AVAILABLE` state |
| **Algorithm** | Same auto-detect logic as `sign`; `request.algorithm` and the key type must match the algorithm used to produce the signature |
| **Returns** | `true` if the signature is cryptographically valid; `false` otherwise |
| **Throws** | Same as `sign` |

---

## `encrypt(CryptoOperationRequest request): CryptoOperationResult`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | NOT SUPPORTED by this provider |
| **Always throws** | `UnsupportedOperationException` referencing the future ECIES feature |
| **Rationale** | TI uses ECIES (ECDH + HKDF/SHA-256/X9.63 + AES-256-CBC + CMAC per gemSpec_Krypt §4.7) — a multi-step hybrid protocol that will be implemented in a dedicated ECIES feature |

---

## `decrypt(CryptoOperationRequest request): CryptoOperationResult`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | NOT SUPPORTED by this provider |
| **Always throws** | `UnsupportedOperationException` referencing the future ECIES feature |
| **Rationale** | Same as `encrypt` — ECIES decryption (gemSpec_COS §6.8.2.3) is deferred to its own feature |

---

## `listKeyStores(): List<KeyStoreDescriptor>`

| Aspect | Specification |
|--------|---------------|
| **Purpose** | Return metadata for all P12 keystores managed by this provider |
| **Returns** | Unmodifiable `List<KeyStoreDescriptor>` in filesystem discovery order (order `.p12` files were found during startup scan) |
| **Size** | Equals the number of `.p12`/`.pfx` files discovered in the certs directory at startup, plus any subsequently uploaded via MBean |
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
| **Size** | Equals the number of `p12/...` aliases managed by this provider |
| **Thread safety** | Safe to call concurrently |

---

## Error Message Requirements

| Scenario | Log Level | Password in Message |
|----------|-----------|---------------------|
| P12 file not found at startup | WARN | MUST NOT appear |
| Wrong keystore password at startup | WARN | MUST NOT appear |
| Operation on unavailable alias | (throws, no log) | N/A |
| JCA algorithm not found | ERROR (in adapter) | MUST NOT appear |
