# Feature Specification: P12 CryptoProvider Implementation

**Feature Branch**: `007-p12-crypto-provider`

**Created**: 2026-06-09

**Status**: Draft

## Clarifications

### Session 2026-06-10

- Q: Should the certs folder be a classpath resource (read-only) or a writable filesystem path? → A: Filesystem path (configurable via `quarkus.crypto.p12.certs-dir`). On startup, any `.p12`/`.pfx` files (and sibling `password.txt` files) found on the classpath under the same logical path are copied into the filesystem certs dir before the scan runs.
- Supported signature algorithms: RSASSA-PSS with SHA-256 (for RSA keys) and ECDSA with SHA-256 (for EC keys). The algorithm is selected automatically based on the private key type in the loaded keystore entry.
- ECIES (gemSpec_Krypt §4.7, gemSpec_COS §6.8.2.3) is the TI standard for ENC key encrypt/decrypt. Both `encrypt` and `decrypt` are deferred to a dedicated ECIES feature; both throw `UnsupportedOperationException` in this provider.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Sign data using an SMC-B authentication key (Priority: P1)

A calling service submits data bytes and a key alias identifying a loaded P12 keystore entry. The system returns a digital signature and the corresponding certificate so the caller can include both in a downstream gematik TI request.

**Why this priority**: Signing with the SMC-B AUT key is the most fundamental operation required by TI workflows (e.g., VSDM, NFDM authentication). Without it, no authenticated TI operation can proceed.

**Independent Test**: Load the `C_SMCB_AUT_E256_X509` keystore from the test resources, call `sign` with a known byte array, verify the returned signature against the public key in the returned certificate.

**Acceptance Scenarios**:

1. **Given** the P12 provider is initialised with the AUT keystore at alias `p12/smcb-aut`, **When** `sign` is called with valid data and a matching `KeyAlias`, **Then** a non-empty byte array is returned along with the leaf X.509 certificate.
2. **Given** an EC private key in the loaded keystore, **When** `sign` is called, **Then** the signature is produced using ECDSA with SHA-256, and `verify` confirms it valid.
3. **Given** an RSA private key in the loaded keystore, **When** `sign` is called, **Then** the signature is produced using RSASSA-PSS with SHA-256, and `verify` confirms it valid.
4. **Given** an EC private key in the keystore and an explicit algorithm identifier in the request, **When** `sign` is called, **Then** the specified algorithm is used instead of the key-type default, and `verify` with the same explicit algorithm confirms the signature valid.
5. **Given** the provider is initialised, **When** `sign` is called with an alias that does not match any loaded keystore, **Then** an `IllegalArgumentException` is thrown.
6. **Given** the provider is initialised, **When** `sign` is called and the underlying keystore is in `ERROR` state, **Then** an `IllegalStateException` is thrown.

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

### User Story 3 — ECIES Encrypt / Decrypt using the SMC-B ENC key *(Deferred — own feature)*

TI uses **ECIES** (Elliptic Curve Integrated Encryption Scheme) for all ECC-based encryption and decryption operations, as defined in **gemSpec_Krypt §4.7** and **gemSpec_COS §6.8.2.3**. ECIES is a hybrid scheme combining:

- **Asymmetric part**: ECDH key agreement per BSI-TR-03111 §4.3.1
- **Key derivation**: HKDF with SHA-256 and X9.63 counter (BSI-TR-03110-3 §A.2.3.2)
- **Symmetric encryption**: AES-256-CBC
- **MAC**: CMAC

Because ECIES requires a multi-step protocol that cannot be implemented by a single `Cipher.getInstance(algorithm)` call, and because the full algorithm specification and test vectors will be provided in a dedicated ECIES feature, **both `encrypt` and `decrypt` are out of scope for this feature**.

**Calling `encrypt` or `decrypt` on `P12CryptoProvider` MUST throw `UnsupportedOperationException`** directing the caller to the future ECIES implementation.

---

### User Story 4 — Inspect keystore availability (Priority: P1)

A health check or management component queries which P12 keystores are loaded and their current availability status. This lets the platform know which cryptographic identities are usable without triggering an actual crypto operation.

**Why this priority**: Availability reporting is required for liveness/readiness probes and for the JMX management interface defined in `CryptoProviderManagement`. It enables operational monitoring before any actual signing occurs.

**Independent Test**: Place two P12 files in the scanned certs folder; confirm `listKeyStores()` returns exactly two descriptors. Break one file; confirm its availability is `ERROR` and the other remains `AVAILABLE`.

**Acceptance Scenarios**:

1. **Given** the certs folder contains three P12 files (AUT, ENC, OSIG) each with a sibling `password.txt`, **When** `listKeyStores()` is called, **Then** exactly three `KeyStoreDescriptor` objects are returned.
2. **Given** a keystore successfully loaded, **When** `getAvailability(alias)` is called, **Then** `AVAILABLE` is returned.
3. **Given** a keystore that failed to load, **When** `getAvailability(alias)` is called, **Then** `ERROR` is returned.
4. **Given** an alias unknown to this provider, **When** `getAvailability(alias)` is called, **Then** `UNAVAILABLE` is returned.
5. **Given** all keystores initialised, **When** `getAvailabilities()` is called, **Then** a map keyed by alias string with correct availability values is returned.

---

### User Story 5 — Upload a new P12 certificate via JMX MBean (Priority: P2)

An operator uploads a new P12 file (plus its password) through a JMX MBean at runtime without restarting the application. The provider immediately loads the new keystore and makes it available for crypto operations.

**Why this priority**: Operators need to renew or add SMC-B / HBA certificates without a service restart. The MBean is the operational control plane for certificate lifecycle.

**Independent Test**: Call `uploadCertificate(alias, p12Bytes, password)` on the MBean; confirm the file appears in the certs folder with a sibling `password.txt`, and `listKeyStores()` subsequently includes the new alias at `AVAILABLE`.

**Acceptance Scenarios**:

1. **Given** a valid P12 byte array and password, **When** `uploadCertificate` is called on the MBean, **Then** the file is written to the configured certs folder, a `password.txt` sibling is written, and the keystore is loaded and returned as `AVAILABLE`.
2. **Given** an invalid P12 byte array, **When** `uploadCertificate` is called, **Then** the provider throws an error, no file is written to disk, and the existing keystores are unaffected.
3. **Given** an alias that already exists, **When** `uploadCertificate` is called, **Then** the existing file is replaced and the adapter is reloaded.

---

### Edge Cases

- What happens when a discovered P12 file has no sibling `password.txt`? The adapter MUST enter `ERROR` state with a clear log message; the provider MUST remain operational for all other aliases.
- What happens when `password.txt` exists but its contents are the wrong password for the P12 file? Same `ERROR` state; the error message MUST NOT include the password string.
- What happens when the certs folder does not exist or is empty? The provider starts with zero adapters and logs a warning; no exception is thrown.
- What happens when `sign` or `verify` is called on an alias belonging to a different `SourceType` (e.g., `pcsc/...`)? The provider MUST reject it with an `IllegalArgumentException` — it handles `p12/` aliases only.
- What happens when `encrypt` or `decrypt` is called? `UnsupportedOperationException` is thrown; ECIES-based encrypt/decrypt is deferred to a dedicated future feature (gemSpec_Krypt §4.7).
- What happens when the P12 file is present but contains no private key entries? An `IllegalStateException` is thrown at sign/verify time.
- What happens when `uploadCertificate` is called with P12 data that cannot be opened with the supplied password? An exception is thrown, nothing is written to disk, and existing adapters are unchanged.
- What happens when a new P12 file is placed directly in the certs folder (without going through the MBean)? It is NOT picked up automatically at runtime; only MBean uploads and startup scans register adapters.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: At startup the provider MUST:
  1. **Bootstrap copy** — scan the classpath for `*.p12` / `*.pfx` files under the logical certs path and copy each file (plus any sibling `password.txt`) into the configured filesystem certs directory if the file does not already exist there. This seeds the writable folder from packaged defaults without overwriting operator-managed files.
  2. **Filesystem scan** — recursively scan the configured filesystem certs directory (configurable via `quarkus.crypto.p12.certs-dir`; no hard-coded default) for files matching `*.p12` or `*.pfx` and load one `P12KeyStoreAdapter` per discovered file. No explicit list of keystores in configuration is required or supported.
- **FR-002**: For each discovered P12 file the provider MUST look for a sibling file named `password.txt` in the same directory. The trimmed contents of `password.txt` are used as the keystore password (and entry password). If no `password.txt` exists the provider MUST record an `ERROR` on that adapter's descriptor and skip loading it; it MUST NOT fall back to an empty or default password.
- **FR-003**: The alias for each discovered keystore is derived from the P12 file's path relative to the certs root, with the extension stripped and path separators replaced by `/`, prefixed with `p12/` — e.g. `certs/smcb/aut.p12` → `p12/smcb/aut`.
- **FR-004**: The provider MUST route `sign` and `verify` operations to the adapter whose alias matches `request.alias`; if no adapter matches, it MUST throw `IllegalArgumentException`.
- **FR-005**: The provider MUST reject any request whose `request.alias` `SourceType` is not `P12`; if the alias is for another source type, it MUST throw `IllegalArgumentException`.
- **FR-006**: `listKeyStores()` MUST return an unmodifiable list of `KeyStoreDescriptor` objects, one per discovered adapter, in file-system discovery order.
- **FR-007**: `getAvailability(alias)` MUST return the current availability of the adapter with that alias, or `UNAVAILABLE` if the alias is not managed by this provider.
- **FR-008**: `getAvailabilities()` MUST return a map of alias-string to `KeyStoreAvailability` for all adapters managed by this provider.
- **FR-009**: `encrypt` and `decrypt` are NOT supported by this provider. TI uses ECIES (ECDH + HKDF/SHA-256/X9.63 + AES-256-CBC + CMAC) as defined in **gemSpec_Krypt §4.7** and **gemSpec_COS §6.8.2.3**, which will be implemented in a dedicated ECIES feature. Calling either method MUST throw `UnsupportedOperationException` with a message referencing the ECIES feature.
- **FR-010**: Errors during keystore load MUST be recorded on the descriptor (`markError`) and MUST NOT propagate as unchecked exceptions out of the startup initialisation; the provider MUST remain available for all other aliases.
- **FR-011**: Keystore passwords MUST NOT appear in log output at any level.
- **FR-012**: The provider MUST be verifiable with the three SMC-B test keystores in `src/test/resources/80276688311000300107-Zeta` (AUT, ENC, OSIG variants, each with password `00` per their sibling `password.txt`).
- **FR-013**: A JMX MBean (`P12CertManagementMBean`) MUST expose an `uploadCertificate(String alias, byte[] p12Data, String password)` operation. On invocation it MUST: (1) validate the P12 bytes can be opened with the given password, (2) write the file to `<certs-dir>/<alias>.p12` and write the password to `<certs-dir>/<alias>/password.txt` relative to the alias subdirectory structure, (3) register and load the new `P12KeyStoreAdapter` immediately, making it available for crypto operations without a restart.
- **FR-014**: If `uploadCertificate` receives invalid P12 data (cannot be opened with the supplied password), it MUST throw an exception, write nothing to disk, and leave existing adapters unchanged.
- **FR-015**: The `sign` and `verify` operations MUST select the signature algorithm as follows:
  1. If `CryptoOperationRequest` carries an explicit algorithm identifier, that algorithm MUST be used.
  2. If no algorithm is specified, the algorithm is chosen automatically from the private key type of the loaded keystore entry:
     - EC keys → **ECDSA with SHA-256** (`SHA256withECDSA`)
     - RSA keys → **RSASSA-PSS with SHA-256** (`SHA256withRSA/PSS`, salt length = digest length = 32 bytes)
  3. A key type for which no default algorithm is defined, combined with no explicit algorithm in the request, MUST cause an `IllegalStateException` with a descriptive message at operation time.

### Key Entities

- **P12CryptoProvider**: The `@ApplicationScoped` CDI bean implementing `CryptoProvider`; holds and manages a collection of `P12KeyStoreAdapter` instances discovered by folder scan.
- **P12CertScanner**: A helper invoked at `@PostConstruct` that walks the configured certs folder recursively, locates `*.p12` / `*.pfx` files, reads the sibling `password.txt`, derives the alias, and returns initialised `P12KeyStoreAdapter` instances.
- **P12KeyStoreAdapter** (existing in `crypto-lib`): Handles the actual PKCS#12 file loading and cryptographic operations for a single alias.
- **P12CryptoConfig**: Quarkus `@ConfigMapping` (prefix `quarkus.crypto.p12`) providing only `certsDir` (type `String`, default `certs`). Replaces the former list-based `P12KeyStoreConfig`.
- **P12CertManagement** / **P12CertManagementMBean**: JMX MBean (`@MXBean`) registered under `de.servicehealtherx.crypto.p12:type=P12CertManagement`. Exposes `uploadCertificate(String alias, byte[] p12Data, String password)` and `listCertificates(): String[]`.
- **KeyStoreDescriptor** (existing in `crypto-lib`): Tracks availability and error state per adapter.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `sign` and `verify` are fully covered by passing unit tests using the Zeta SMC-B test keystores. The `UnsupportedOperationException` path for both `encrypt` and `decrypt` is verified by tests. Tests MUST explicitly verify that ECDSA with SHA-256 is used for EC keys and RSASSA-PSS with SHA-256 is used for RSA keys.
- **SC-002**: `listKeyStores()`, `getAvailability()`, and `getAvailabilities()` return correct values for both the happy path and the error path (missing `password.txt`, corrupted P12 file), verified by tests.
- **SC-003**: No `UnsupportedOperationException` is thrown from `sign` or `verify` when the preconditions are met. `encrypt` and `decrypt` intentionally throw `UnsupportedOperationException` pointing to the future ECIES feature.
- **SC-004**: A misconfigured keystore (missing `password.txt`, bad password, or corrupt file) causes only that adapter's descriptor to enter `ERROR` state; other adapters continue to function and their operations succeed.
- **SC-005**: Keystore passwords do not appear in any log output at any level, verified by inspecting captured log output in tests.
- **SC-006**: `uploadCertificate` on the MBean persists the file and `password.txt` to the certs folder, the new alias appears in `listKeyStores()` as `AVAILABLE`, and subsequent `sign` calls using the new alias succeed — verified by an integration test.

## Assumptions

- The three Zeta SMC-B keystores (AUT, ENC, OSIG) each use password `00` (as found in `password.txt` in each subdirectory of the test resources).
- Keystore entry password equals the keystore password read from `password.txt`; there is no separate per-entry password configuration.
- The alias derived from the file path (`p12/<relative-path-without-extension>`) is guaranteed to satisfy the `KeyAlias` source-type constraint; alias validation is not the provider's responsibility.
- The caller MAY supply an explicit signature algorithm in `CryptoOperationRequest`; if absent, the algorithm is determined by the private key type. No SHA-1-based algorithms are supported.
- Startup initialisation performs the classpath bootstrap copy then scans the filesystem certs folder eagerly; there is no lazy-load requirement in this feature.
- The filesystem certs directory (`quarkus.crypto.p12.certs-dir`) is writable at runtime so both the classpath bootstrap copy and MBean uploads can persist files to it.
- Classpath-packaged P12 files are treated as defaults: they are copied to the filesystem certs dir only if the file is not already present (no overwrite of operator-managed files).
- Both `encrypt` and `decrypt` are intentionally excluded from this feature. TI uses ECIES (ECDH + HKDF/SHA-256/X9.63 + AES-256-CBC + CMAC per gemSpec_Krypt §4.7 and gemSpec_COS §6.8.2.3), which is a multi-step hybrid protocol requiring its own dedicated feature and test vectors.
- The existing `P12KeyStoreAdapter` in `crypto-lib` is the correct and complete adapter implementation; this feature wires it into the provider bean and does not rewrite the adapter.
- The former list-based `P12KeyStoreConfig` is superseded by the simpler `P12CryptoConfig` (folder path only); any existing Quarkus config properties for the old list-based mapping are no longer used.
