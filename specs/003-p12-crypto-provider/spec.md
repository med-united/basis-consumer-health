# Feature Specification: P12 CryptoProvider Implementation

**Feature Branch**: `007-p12-crypto-provider`

**Created**: 2026-06-09

**Status**: Draft

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Sign data using an SMC-B authentication key (Priority: P1)

A calling service submits data bytes and a key alias identifying a loaded P12 keystore entry. The system returns a digital signature and the corresponding certificate so the caller can include both in a downstream gematik TI request.

**Why this priority**: Signing with the SMC-B AUT key is the most fundamental operation required by TI workflows (e.g., VSDM, NFDM authentication). Without it, no authenticated TI operation can proceed.

**Independent Test**: Load the `C_SMCB_AUT_E256_X509` keystore from the test resources, call `sign` with a known byte array, verify the returned signature against the public key in the returned certificate.

**Acceptance Scenarios**:

1. **Given** the P12 provider is initialised with the AUT keystore at alias `p12/smcb-aut`, **When** `sign` is called with valid data and a matching `KeyAlias`, **Then** a non-empty byte array is returned along with the leaf X.509 certificate.
2. **Given** the provider is initialised, **When** `sign` is called with an alias that does not match any loaded keystore, **Then** an `IllegalArgumentException` is thrown.
3. **Given** the provider is initialised, **When** `sign` is called and the underlying keystore is in `ERROR` state, **Then** an `IllegalStateException` is thrown.

---

### User Story 2 — Verify a signature using a loaded keystore (Priority: P2)

A calling service submits data, a signature, and a key alias. The system confirms whether the signature is valid for the given data using the public key stored in the identified keystore entry.

**Why this priority**: Verification is required to validate responses from TI services before acting on them; it pairs naturally with signing as the second half of all asymmetric workflows.

**Independent Test**: Sign a byte array using the AUT keystore, then verify the resulting signature using the same alias — expect `true`. Mutate one byte in the signature and expect `false`.

**Acceptance Scenarios**:

1. **Given** a signature produced by `sign`, **When** `verify` is called with the original data and matching alias, **Then** `true` is returned.
2. **Given** a tampered signature, **When** `verify` is called with the original data, **Then** `false` is returned.
3. **Given** an alias not matching any keystore, **When** `verify` is called, **Then** an `IllegalArgumentException` is thrown.

---

### User Story 3 — Decrypt data using the SMC-B encryption key (Priority: P3)

A calling service submits encrypted data and a key alias identifying the encryption keystore entry. The system decrypts and returns the plaintext.

**Why this priority**: The ENC key usage is required for decrypting TI VAU or KIM messages. Decrypt comes after sign and verify in implementation priority because TI authentication must work first.

**Independent Test**: Encrypt a known plaintext using the ENC certificate's public key, call `decrypt` with alias `p12/smcb-enc`, and confirm the returned bytes match the original plaintext.

**Acceptance Scenarios**:

1. **Given** data encrypted with the ENC public key, **When** `decrypt` is called with the correct alias, **Then** the original plaintext is returned.
2. **Given** an alias pointing to an AUT keystore (wrong key usage), **When** `decrypt` is called, **Then** a meaningful error is returned (operation fails, not silently wrong result).
3. **Given** an alias not matching any keystore, **When** `decrypt` is called, **Then** an `IllegalArgumentException` is thrown.

---

### User Story 4 — Inspect keystore availability (Priority: P1)

A health check or management component queries which P12 keystores are loaded and their current availability status. This lets the platform know which cryptographic identities are usable without triggering an actual crypto operation.

**Why this priority**: Availability reporting is required for liveness/readiness probes and for the JMX management interface defined in `CryptoProviderManagement`. It enables operational monitoring before any actual signing occurs.

**Independent Test**: Initialise the provider with two keystores; confirm `listKeyStores()` returns exactly two descriptors. Break one file path; confirm its availability is `ERROR` and the other remains `AVAILABLE`.

**Acceptance Scenarios**:

1. **Given** the provider is initialised with three keystores (AUT, ENC, OSIG), **When** `listKeyStores()` is called, **Then** exactly three `KeyStoreDescriptor` objects are returned.
2. **Given** a keystore successfully loaded, **When** `getAvailability(alias)` is called, **Then** `AVAILABLE` is returned.
3. **Given** a keystore that failed to load, **When** `getAvailability(alias)` is called, **Then** `ERROR` is returned.
4. **Given** an alias unknown to this provider, **When** `getAvailability(alias)` is called, **Then** `UNAVAILABLE` is returned.
5. **Given** all keystores initialised, **When** `getAvailabilities()` is called, **Then** a map keyed by alias string with correct availability values is returned.

---

### Edge Cases

- What happens when the P12 file does not exist at the configured path? The keystore descriptor MUST transition to `ERROR` state; the provider MUST remain operational for other aliases.
- What happens when the keystore password is wrong? Same `ERROR` state transition; error message MUST NOT contain the password characters.
- What happens when two configured aliases share the same P12 file path? Each is loaded independently as a separate descriptor.
- What happens when `sign` or `verify` is called on an alias belonging to a different `SourceType` (e.g., `pcsc/...`)? The provider MUST reject it with an `IllegalArgumentException` — it handles `p12/` aliases only.
- What happens when the P12 file is present but contains no private key entries? An `IllegalStateException` is thrown at operation time.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The provider MUST load one `P12KeyStoreAdapter` per configured keystore entry at startup, using alias, file path, keystore password, and optional entry password from configuration.
- **FR-002**: The provider MUST route `sign`, `verify`, and `decrypt` operations to the adapter whose alias matches `request.alias`; if no adapter matches, it MUST throw `IllegalArgumentException`.
- **FR-003**: The provider MUST reject any request whose `request.alias` `SourceType` is not `P12`; if the alias is for another source type, it MUST throw `IllegalArgumentException`.
- **FR-004**: `listKeyStores()` MUST return an unmodifiable list of `KeyStoreDescriptor` objects, one per configured adapter, in configuration order.
- **FR-005**: `getAvailability(alias)` MUST return the current availability of the adapter with that alias, or `UNAVAILABLE` if the alias is not managed by this provider.
- **FR-006**: `getAvailabilities()` MUST return a map of alias-string to `KeyStoreAvailability` for all adapters managed by this provider.
- **FR-007**: `encrypt` is NOT supported by the P12 provider (asymmetric encryption is performed externally using the certificate's public key); calling `encrypt` MUST throw `UnsupportedOperationException` with a clear message directing the caller to use the certificate-based encryption path.
- **FR-008**: Errors during keystore load MUST be recorded on the descriptor (`markError`) and MUST NOT propagate as unchecked exceptions out of the startup initialisation; the provider MUST remain available for all other aliases.
- **FR-009**: Keystore passwords MUST NOT appear in log output at any level.
- **FR-010**: The provider MUST be verifiable with the three SMC-B test keystores in `src/test/resources/80276688311000300107-Zeta` (AUT, ENC, OSIG variants, each with password `00` per `password.txt`).

### Key Entities

- **P12CryptoProvider**: The `@ApplicationScoped` CDI bean implementing `CryptoProvider`; holds and manages a collection of `P12KeyStoreAdapter` instances.
- **P12KeyStoreAdapter** (existing in `crypto-lib`): Handles the actual PKCS#12 file loading and cryptographic operations for a single alias.
- **P12KeyStoreConfig** (existing in `crypto-lib`): Quarkus `@ConfigMapping` that provides the list of configured P12 keystore entries.
- **KeyStoreDescriptor** (existing in `crypto-lib`): Tracks availability and error state per adapter.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All four `CryptoProvider` methods (`sign`, `verify`, `decrypt`, and the `UnsupportedOperationException` path for `encrypt`) are covered by passing unit tests using the Zeta SMC-B test keystores.
- **SC-002**: `listKeyStores()`, `getAvailability()`, and `getAvailabilities()` return correct values for both the happy path and the error path (missing file, wrong password), verified by tests.
- **SC-003**: No `UnsupportedOperationException` is thrown from any method other than `encrypt` when the preconditions are met — the stub bodies are fully replaced.
- **SC-004**: A misconfigured keystore (bad path or password) causes only that adapter's descriptor to enter `ERROR` state; other adapters continue to function and their operations succeed.
- **SC-005**: Keystore passwords do not appear in any log output at any level, verified by inspecting captured log output in tests.

## Assumptions

- The three Zeta SMC-B keystores (AUT, ENC, OSIG) each use password `00` (as found in `password.txt` in each subdirectory).
- Keystore entry password equals keystore password unless `entryPassword` is explicitly configured (matching existing `P12KeyStoreConfig.entryPasswordOrKeystore()` behaviour).
- The configured alias string must match the `p12/<name>` pattern enforced by `KeyAlias`; alias validation is not the provider's responsibility.
- Startup initialisation loads all configured keystores eagerly; there is no lazy-load or hot-reload requirement in this feature.
- The `encrypt` operation is intentionally excluded: asymmetric encryption for TI workflows is performed by callers using the certificate's public key directly, not via `CryptoProvider.encrypt`.
- `P12KeyStoreConfig` injection is handled by Quarkus CDI/SmallRye Config; no custom producer or factory is needed.
- The existing `P12KeyStoreAdapter` in `crypto-lib` is the correct and complete adapter implementation; this feature wires it into the provider bean, not rewrite the adapter.
