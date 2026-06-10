# Research: P12 CryptoProvider Implementation

**Feature**: `003-p12-crypto-provider` | **Branch**: `007-p12-crypto-provider` | **Date**: 2026-06-10

---

### D-001: Startup Initialization — folder scan + classpath bootstrap

**Decision**: `@PostConstruct` on `P12CryptoProvider` delegates to `P12CertScanner` which:
1. **Bootstrap copy**: Enumerates classpath resources matching `<certsDir>/**/*.p12` and `<certsDir>/**/*.pfx` via `Thread.currentThread().getContextClassLoader().getResources(...)`. Copies each file (and any sibling `password.txt` in the same classpath directory) to the configured filesystem certs directory, skipping files already present.
2. **Filesystem scan**: Walks the filesystem certs directory recursively with `Files.walk()`, collects every `.p12`/`.pfx`, reads the sibling `password.txt`, derives the alias, constructs and loads one `P12KeyStoreAdapter` per file.

**Rationale**: Classpath bootstrap seeds the writable folder from packaged defaults without overwriting operator-managed files. `Files.walk()` is the standard JDK NIO2 API for recursive directory traversal — no third-party libraries needed. `@PostConstruct` ensures the adapter map is fully populated before the bean is visible to callers.

**Classpath enumeration caveat**: In Quarkus native image mode, `ClassLoader.getResources()` cannot enumerate directories. The bootstrap copy is therefore limited to JVM mode. Native-mode deployments must pre-populate the filesystem certs dir from an init-container or volume mount.

**Alternatives considered**:
- `@Observes StartupEvent`: Quarkus-specific; standard CDI `@PostConstruct` is sufficient (Principle VIII).
- Lazy initialization: Rejected — availability must be queryable by health checks before any crypto operation.

---

### D-002: Adapter storage — concurrent read, locked write

**Decision**: `P12CryptoProvider` holds adapters in a `CopyOnWriteArrayList<P12KeyStoreAdapter>` (for ordered iteration and lock-free reads) and a `ConcurrentHashMap<String, P12KeyStoreAdapter>` (for O(1) alias lookup). MBean uploads acquire a write lock (`ReentrantLock`) to ensure the two structures stay consistent during concurrent uploads.

**Rationale**: Sign/verify/decrypt/listKeyStores are high-frequency read operations that must not block on each other. MBean uploads are rare operator actions. `CopyOnWriteArrayList` makes `listKeyStores()` allocation-free (returns an unmodifiable view) under concurrent reads. `ConcurrentHashMap` provides lock-free reads for routing.

**Alternatives considered**:
- `LinkedHashMap` (original D-002): Sufficient for startup-only writes, but MBean upload requires safe concurrent modification — rejected.
- `Collections.synchronizedMap`: Coarser locking; degrades read throughput — rejected.

---

### D-003: Error isolation during startup

**Decision**: Wrap each adapter creation and `engineLoad()` call in try-catch inside `P12CertScanner`. On failure (missing `password.txt`, wrong password, corrupt file), create a `P12KeyStoreDescriptorError` sentinel — a `P12KeyStoreAdapter` constructed with the derived alias and path but immediately `markError()`'d — and add it to the adapter list. Log at WARN. Continue to the next file.

**Rationale**: A single missing or corrupt P12 must not prevent other identities from loading (FR-010). The error adapter is visible in `listKeyStores()` with `ERROR` state, satisfying availability reporting (SC-002).

**Alternatives considered**:
- Fail-fast startup: Rejected — degrades availability for all identities.
- Skip errored file entirely: Rejected — operators must see the error state in `listKeyStores()`.

---

### D-004: Routing requests to adapters

**Decision**: In `P12CryptoProvider`, before routing to any adapter:
1. Check `request.alias.sourceType() == SourceType.P12` → `IllegalArgumentException` if not P12.
2. Look up adapter by `request.alias.value()` in the `ConcurrentHashMap` → `IllegalArgumentException` if absent.
3. Delegate to the adapter's method. The adapter's `ensureLoaded()` will throw `IllegalStateException` if in ERROR state.

**Rationale**: Validates at the provider boundary before delegating (FR-004, FR-005). Clean, ordered error messages.

---

### D-005: Algorithm resolution — auto-detect in P12KeyStoreAdapter (sign/verify only)

**Note**: `encrypt` and `decrypt` both throw `UnsupportedOperationException` — ECIES (gemSpec_Krypt §4.7) is deferred to a dedicated feature. Algorithm resolution applies to `sign` and `verify` only.

**Decision**: Modify `P12KeyStoreAdapter.sign()` and `verify()` to call a `resolveAlgorithm(PrivateKey key, String requested)` helper. When `requested` is null or blank, inspect `key.getAlgorithm()`:
- `"EC"` → `"SHA256withECDSA"`
- `"RSA"` → `"SHA256withRSA/PSS"` with `PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)` applied to the `Signature` instance
- Any other type → `IllegalStateException` with descriptive message

**Rationale**: The adapter already holds the loaded keystore and private key — it is the only place that can inspect the key type without extra round-trips. Putting the fallback here means callers get the correct algorithm regardless of whether they supply one or not (FR-015). Bouncy Castle (already on the classpath) handles RSASSA-PSS correctly via its JCE provider; register `BouncyCastleProvider` in a static initializer if not already present.

**Note**: `CryptoOperationResult.algorithm` echoes the _resolved_ algorithm, not the request's raw string, so callers know which algorithm was actually used.

---

### D-006: KeyAlias pattern extension for nested paths

**Decision**: Extend `KeyAlias` pattern from  
`^(p12|pkcs11|pcsc|sicct)/[a-z0-9\-_]+$`  
to  
`^(p12|pkcs11|pcsc|sicct)(/[a-z0-9][a-z0-9\-_.]*)+$`

**Alias normalization in P12CertScanner** (applied before constructing `KeyAlias`):
1. Compute relative path from certs root (using `Path.relativize`)
2. Strip file extension (`.p12` / `.pfx`)
3. Lowercase entire string
4. Replace each character not in `[a-z0-9\-_./]` with `-`
5. Collapse consecutive `-` to a single `-`
6. Trim leading/trailing `-` per path segment
7. Prefix with `p12/`

Example: `certs/Zeta/C_SMCB_AUT.p12` → `p12/zeta/c-smcb-aut`

**Rationale**: Folder-scan aliases must carry hierarchy (path segments) to remain unique and human-readable. The existing single-segment constraint would reject any nested path. Extending to multiple segments is a backward-compatible change — existing single-segment aliases still match.

**Alternatives considered**:
- Flatten to single hyphen-joined segment: Produces unwieldy aliases for the deep Zeta paths; loses hierarchy.
- Allow arbitrary strings: Breaks the source-type prefix guarantee that the rest of the system relies on.

---

### D-007: P12CertManagement MBean — upload file layout

**Decision**: `uploadCertificate(String alias, byte[] p12Data, String password)` where `alias` is in `p12/<path>` form:
1. Validate `KeyAlias` format; strip `p12/` prefix to get relative path (e.g., `smcb/aut`)
2. Target P12 file: `<certs-dir>/<relative-path>.p12` (create intermediate directories as needed)
3. Target password file: `<certs-dir>/<relative-path-parent>/password.txt` (sibling of the P12 file)
4. Validate P12 bytes open with supplied password **before** writing to disk
5. Atomically write P12 file via temp file + rename; write password file
6. Construct, load, and register the new `P12KeyStoreAdapter`

**Rationale**: The sibling `password.txt` layout matches exactly what `P12CertScanner` reads on startup (FR-002), ensuring a restarted application re-discovers the uploaded cert correctly. Writing to a temp file then renaming is atomic on POSIX filesystems, preventing corrupt partial writes visible to the scanner.

**Alternatives considered**:
- Write password to a subdirectory of the alias name: Inconsistent with scan rule; would break restart re-discovery.

---

### D-008: P12CryptoConfig — minimal config mapping

**Decision**: Define `P12CryptoConfig` as a `@ConfigMapping(prefix = "quarkus.crypto.p12")` interface in `crypto-p12-lib` with a single method `certsDir(): String`. No default is provided at the mapping level; the test `application.properties` sets `quarkus.crypto.p12.certs-dir` to a temp dir.

**Rationale**: The existing `P12KeyStoreConfig` in `crypto-lib` is list-based and tightly coupled to the old per-cert config approach. Defining a new minimal config in `crypto-p12-lib` keeps the config concern in the module that uses it. `P12KeyStoreConfig` is left in `crypto-lib` but is no longer referenced by `P12CryptoProvider`.

---

### D-009: MBean registration — follow established pattern

**Decision**: `P12CertManagement` follows the identical pattern as `CryptoProviderManagement` and `KeyStoreReloadManagement`:
- `@ApplicationScoped` bean implementing `P12CertManagementMBean`
- `@PostConstruct registerMBean()` via `ManagementFactory.getPlatformMBeanServer()`
- `@PreDestroy deregisterMBean()`
- Object name: `de.servicehealtherx:module=crypto-p12-lib,name=P12CertManagement`

`P12CertManagementMBean` follows the JMX MBean interface naming convention (interface name = implementation class name + `MBean` suffix), consistent with the project's existing JMX interfaces (Principle VIII).

---

### D-010: No new external libraries required

**Decision**: All required functionality is covered by the JDK standard library and existing dependencies.

| Capability | Source |
|---|---|
| PKCS12 keystore loading | JDK `KeyStore.getInstance("PKCS12")` — already in `P12KeyStoreAdapter` |
| ECDSA / RSASSA-PSS signing | JDK JCA `Signature` API + Bouncy Castle (already in `crypto-lib`) |
| Recursive directory walk | JDK NIO2 `Files.walk()` |
| Classpath resource enumeration | `ClassLoader.getResources()` (JDK standard) |
| JMX MBean registration | `javax.management` (JDK built-in) |
| Config injection | SmallRye Config via `quarkus-arc` (already transitive) |

**Rationale**: Principle VII (evaluate existing libraries before writing custom code) is satisfied by confirming all capabilities are already available. No security review is required for a new dependency because no new dependency is introduced.
