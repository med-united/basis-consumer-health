# Research: P12 CryptoProvider Implementation

**Feature**: `003-p12-crypto-provider` | **Branch**: `007-p12-crypto-provider` | **Date**: 2026-06-09

## Decision Log

---

### D-001: Startup Initialization Mechanism

**Decision**: Use `@PostConstruct` on the `@ApplicationScoped` bean to load all configured P12 adapters eagerly at startup.

**Rationale**: `@PostConstruct` is the standard CDI lifecycle callback for initialization after dependency injection completes. It fires exactly once per bean instance, which for `@ApplicationScoped` means once at application start. This avoids needing a Quarkus-specific `StartupEvent` observer when standard CDI suffices (Principle VIII — Standard Interface Adoption).

**Alternatives considered**:
- `@Observes StartupEvent`: Quarkus-specific; not needed when `@PostConstruct` suffices.
- Lazy initialization (load on first use): Rejected — availability must be queryable from health checks before the first crypto operation.

---

### D-002: Adapter Storage Structure

**Decision**: Store adapters in a `LinkedHashMap<String, P12KeyStoreAdapter>` keyed by alias string, initialized in `@PostConstruct` and never mutated after that. Expose as read-only via `Collections.unmodifiableMap`.

**Rationale**: `LinkedHashMap` preserves insertion order (= configuration order), which makes `listKeyStores()` deterministic. The map is populated once and never written to after startup, so concurrent reads require no locking. The `volatile KeyStore keyStore` field inside each `P12KeyStoreAdapter` already handles the load visibility guarantee.

**Alternatives considered**:
- `ConcurrentHashMap`: No concurrent writes after init — overkill.
- `HashMap`: Order not preserved; makes `listKeyStores()` nondeterministic.
- Per-request loading: Rejected — P12 files are static, loading them repeatedly is wasteful and slow.

---

### D-003: Error Isolation During Startup

**Decision**: Wrap each `adapter.engineLoad(null, null)` call in a try-catch during `@PostConstruct`. On `IOException`, call `descriptor.markError(e.getMessage())` and log at WARN. Do not rethrow — the provider MUST remain functional for other aliases.

**Rationale**: A missing or corrupted P12 file for one identity must not prevent other identities from being used (FR-008). The `KeyStoreDescriptor` error state serves as the signal to health checks and operators. Passwords MUST NOT appear in log messages (FR-009) — `P12KeyStoreAdapter` already enforces this by only logging `e.getMessage()` which does not contain password characters.

**Alternatives considered**:
- Fail-fast startup: Rejected — degrades availability for all identities when only one is broken.
- Silent suppression (no log): Rejected — operators must be alerted; WARN is the right level.

---

### D-004: Routing Requests to Adapters

**Decision**: Resolve the target adapter by `request.alias.value()` map lookup. Validate `SourceType.P12` first; throw `IllegalArgumentException` with a descriptive message if the alias is for a different source type or if no adapter is registered for that alias.

**Rationale**: `KeyAlias.sourceType()` is already implemented and reliably extracts the type prefix. Checking it before the map lookup gives a cleaner error message ("alias is for PCSC, not P12") vs a generic "key not found". This matches the contract in FR-002 and FR-003.

**Alternatives considered**:
- Let the map return null and throw NPE: Rejected — cryptic error.
- Catch `IllegalStateException` from the adapter's `ensureLoaded()`: Still needed as a fallback, but routing validation happens first.

---

### D-005: `encrypt` Operation

**Decision**: `P12CryptoProvider.encrypt` throws `UnsupportedOperationException` with the message: `"P12CryptoProvider does not support encrypt — use the certificate's public key directly for asymmetric encryption"`.

**Rationale**: Asymmetric encryption in TI workflows is performed by the caller using the peer's X.509 certificate public key, not the local private key. `P12KeyStoreAdapter.encrypt` already carries this restriction. Surfacing it clearly at the provider level avoids confusion (FR-007).

---

### D-006: New Libraries Required

**Decision**: No new external libraries are needed.

**Rationale**: PKCS#12 loading uses the JDK standard `KeyStore.getInstance("PKCS12")` API, which is already encapsulated in the existing `P12KeyStoreAdapter` in `crypto-lib`. Bouncy Castle is already in the dependency tree for other modules but is not required here — the JDK ships a full PKCS12 provider since Java 9. Principle VII (external library evaluation before writing custom code) is satisfied by confirming the JDK capability is sufficient.

---

### D-007: Test Configuration

**Decision**: Use `@QuarkusTest` with `application.properties` in `src/test/resources` to configure the three Zeta SMC-B keystores (AUT, ENC, OSIG). The passwords come from `password.txt` (value: `00`).

**Rationale**: Quarkus `@ConfigMapping` injection works cleanly in `@QuarkusTest` context via `application.properties`. This avoids manual wiring and tests the full CDI initialization path including `@PostConstruct`. The Zeta keystores are already present in `src/test/resources/80276688311000300107-Zeta/`.

**Alternatives considered**:
- Unit test with manual construction: Possible but does not test the CDI injection path. Can be added for targeted unit tests of routing logic only.
- Mocking `P12KeyStoreConfig`: Rejected — overly complex; test properties file is simpler and more realistic.

---

### D-008: Thread Safety

**Decision**: The `LinkedHashMap` of adapters is read-only after `@PostConstruct` completes. Individual adapter state (`volatile KeyStore keyStore`) is already thread-safe per `P12KeyStoreAdapter`. No additional synchronization is needed in `P12CryptoProvider`.

**Rationale**: CDI guarantees `@PostConstruct` completes before the bean is visible to other beans. The adapter's `volatile` field ensures the loaded keystore is visible across threads. Crypto operations (`Signature`, `Cipher`) are instantiated per-call, so there is no shared mutable state.
