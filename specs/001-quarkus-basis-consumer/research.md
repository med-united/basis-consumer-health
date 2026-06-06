# Research: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Branch**: `001-quarkus-basis-consumer` | **Date**: 2026-06-05

Topics 1–7 cover the pluggable cryptographic provider and SICCT Quarkus extension. Topics 8–10 cover SOAP services (Apache CXF), LDAP proxy, and openkim submodule integration.

---

## Topic 1: SunPKCS11 in Java 21 — Multi-Instance Programmatic Configuration

**Decision**: Use `Security.getProvider("SunPKCS11").configure(cfgString)` to programmatically instantiate each PKCS#11 provider; register each via `Security.addProvider(provider)`.

**Rationale**: Since Java 9, the `new sun.security.pkcs11.SunPKCS11(configFile)` constructor is replaced by the standard `Provider.configure()` API. The method accepts either a file path or an inline configuration string prefixed with `--`. Multiple instances are supported as long as each has a unique `name` attribute in its configuration — the JVM registers them as `SunPKCS11-<name>`.

**Implementation pattern**:
```
String cfg = "--\nlibrary=/path/to/libpkcs11.so\nname=Utimaco\nslotListIndex=0";
Provider p = Security.getProvider("SunPKCS11").configure(cfg);
Security.addProvider(p);
KeyStore ks = KeyStore.getInstance("PKCS11", p);
ks.load(null, pin.toCharArray());
```

**Alternatives considered**:
- IAIK PKCS#11 wrapper (third-party): More features but additional dependency weight and license cost; rejected in favour of the JDK built-in.
- Bouncy Castle PKCS11 bridge: BC FIPS does not replace SunPKCS11 for HSM token operations; BC is used only for P12 parsing and JCA operations.

**Constraint**: Each PKCS#11 library name attribute must be globally unique per JVM instance. The `Pkcs11CryptoProvider` must enforce this at startup and fail with a descriptive error on collision.

---

## Topic 2: Multiple SunPKCS11 Instances in One JVM

**Decision**: Supported without workaround; implemented in `Pkcs11CryptoProvider` with one `Security.addProvider` call per configured slot.

**Rationale**: The JVM supports multiple SunPKCS11 provider registrations. Each registered instance has the name `SunPKCS11-<configuredName>`. Constraints: (a) name uniqueness per JVM; (b) the underlying PKCS#11 library must support multi-session use (all production-grade HSM drivers do; Utimaco CryptoServer explicitly supports it).

**Alternatives considered**: Single-provider multiplexing (one SunPKCS11 instance, multiple logical slots): not feasible — SunPKCS11 is bound to one library per instance.

---

## Topic 3: javax.smartcardio on Linux + OpenJDK 21

**Decision**: Use `javax.smartcardio` (JDK built-in) with a dedicated card-event monitoring thread using `CardTerminals.waitForChange(timeoutMs)`.

**Rationale**: OpenJDK ships `libj2pcsc.so` which wraps PC/SC Lite (`libpcsclite.so.1`) on Linux. This works in JVM mode without any additional native libraries beyond the `pcsclite` package and `pcscd` daemon. There are no Quarkus-specific issues in JVM mode.

**Known limitation — native mode**: Quarkus native image does not include `javax.smartcardio` classes or `libj2pcsc` by default. Native mode is explicitly excluded from v1 scope for this reason.

**Card event monitoring pattern**:
```
CardTerminals terminals = TerminalFactory.getDefault().terminals();
while (running) {
    terminals.waitForChange(5_000);      // blocks up to 5 s
    terminals.list(State.CARD_INSERTION).forEach(t -> onCardInserted(t));
    terminals.list(State.CARD_REMOVAL).forEach(t -> onCardRemoved(t));
}
```
This dedicated thread runs inside `PcscCryptoProvider` and updates available alias registrations on the shared `RoutingCryptoProvider`.

**Alternatives considered**: Polling loop with `list(CARD_PRESENT)` every N seconds: rejected — `waitForChange` is more efficient and avoids missed events between polls.

---

## Topic 4: Quarkus Extension Lifecycle for Netty TCP Connections

**Decision**: Use `@ApplicationScoped` + `@Observes StartupEvent` / `@Observes ShutdownEvent` for Netty connection pool management in the SICCT extension runtime module.

**Rationale**: `StartupEvent` fires after all CDI beans are fully initialized, making it safe to inject other beans (config, metrics). `@Startup` has edge-case ordering issues when injected beans are needed. `@PreDestroy` on the `NioEventLoopGroup` handles graceful shutdown.

**Reconnect strategy**: Manual `ChannelInboundHandler.channelInactive()` schedules a reconnect on the Netty event loop using `EventLoop.schedule()` with exponential backoff. A `@Scheduled(every = "30s")` watchdog in `SicctTerminalManager` additionally ensures any stale-disconnected terminals are retried even if the `channelInactive` event is missed.

**Alternatives considered**:
- SmallRye Fault Tolerance `@Retry`: Designed for inbound request handling, not persistent outbound TCP sockets. Rejected.
- Vert.x HttpClient / NetClient: The rest of the Basis Consumer does not use Vert.x client APIs for TI protocol work; consistency with the SICCT protocol implementation (Netty) takes precedence.

---

## Topic 5: SmallRye Health from a Quarkus Extension

**Decision**: `@ApplicationScoped @Readiness HealthCheck` in the runtime module; `HealthBuildItem` in the deployment module to optionally wire the check.

**Key class**: `io.quarkus.smallrye.health.deployment.spi.HealthBuildItem` — registers the check at build time. The `@Readiness` bean is injected into SmallRye Health automatically when it is on the classpath and the `HealthBuildItem` is emitted.

---

## Topic 6: SoftHSM2 in Quarkus @QuarkusTest for PKCS#11 + ECC

**Decision**: Implement a `QuarkusTestResourceLifecycleManager` that initializes SoftHSM2 token, registers the SunPKCS11 provider, and tears down after all tests.

**Rationale**: `@BeforeAll` runs after Quarkus CDI context boots; SunPKCS11 must be registered before CDI if crypto provider beans depend on it at startup. `QuarkusTestResourceLifecycleManager.start()` runs before the Quarkus context — the correct injection point.

**ECC support**: SoftHSM2 2.x supports P-256, P-384, P-521 (ECDSA and ECDH). Key generation via:
```
keytool -genkeypair -keyalg EC -keysize 256 -storetype PKCS11 -providerName SunPKCS11-SoftHSM ...
```
Or directly via `KeyPairGenerator.getInstance("EC", softHsmProvider)`.

**Alternatives considered**: Bouncy Castle software PKCS#11 shim: Does not exist as a standalone; SoftHSM2 is the industry standard for CI PKCS#11 testing.

---

## Topic 7: JCE Routing Pattern (CDI-Native vs. java.security.Provider)

**Decision**: CDI-native routing (`RoutingCryptoProvider` interface dispatching to typed adapter beans by alias prefix) rather than registering a custom `java.security.Provider`.

**Rationale**: Implementing a custom `java.security.Provider` requires implementing `KeyStoreSpi`, `SignatureSpi`, etc. — extensive boilerplate for no user-visible benefit in a CDI application. The CDI approach is cleaner, more testable, and avoids JVM-global registration side-effects. The adapters still use JCA/JCE APIs internally (`Signature.getInstance()`, `Cipher.getInstance()`, `KeyStore.getInstance()`), satisfying the "JCE implementation" mandate.

**Alias namespacing**: `KeyAlias` format is `<source-type>/<user-defined-name>`, e.g., `p12/konnektor-smcb`, `pkcs11/utimaco-slot0`, `pcsc/reader-a-slot0`, `sicct/terminal-1-slot0`. The `RoutingCryptoProvider` extracts the source-type prefix to select the correct adapter.

**Alternatives considered**: Custom `java.security.Provider` registration: Rejected due to complexity, JVM-global scope, and lack of CDI lifecycle integration.

---

---

## Topic 8: Apache CXF SOAP Services in Quarkus

**Decision**: Use `quarkus-cxf` (the Quarkus CXF extension) to host all SOAP services (`EncryptionService`, `SignatureService`, `CertificateService`, Konnektor compatibility). The `@WebService` implementation classes are CDI beans and inject `CryptoProvider` directly.

**Rationale**: Apache CXF is the industry standard for SOAP in the Java ecosystem and the most mature Quarkus SOAP extension. The Konnektor WSDLs and the Basis Consumer WSDLs are specified by gematik; CXF's WSDL-first approach (`wsdl2java`) generates the stubs and ensures schema validity. CXF supports interceptors for tenant isolation enforcement (route by JWT / client certificate claim to tenant namespace).

**Tenant isolation at SOAP layer**: A CXF `PhaseInterceptor<Message>` in the `PRE_INVOKE` phase reads the tenant claim from the incoming request's TLS client certificate or bearer token, establishes the tenant scope in a CDI `@RequestScoped` context object, and rejects the request if the tenant is unknown or deprovisioned.

**Alternatives considered**: RESTEasy + custom JSON layer: Rejected — gematik specifies SOAP WSDLs; a REST shim would require maintaining two interface layers and lose schema validation for free. Jakarta EE Metro: Rejected — less Quarkus ecosystem integration than CXF.

---

## Topic 9: LDAPv3 Proxy Implementation

**Decision**: Implement the LDAP proxy using UnboundID LDAP SDK (client side, for connecting to TI VZD) and a lightweight Netty-based LDAPv3 server codec (for accepting client connections). Only Bind, Unbind, Search, Abandon are forwarded; all others are immediately rejected with error 53.

**Rationale**: UnboundID LDAP SDK is the most capable Java LDAP client library and handles asynchronous operations cleanly. Using Netty for the server side is consistent with the SICCT extension and avoids introducing yet another framework. The proxy is stateless (no session caching beyond the LDAP Bind authentication token).

**Request filtering**: An enum-driven filter checks `ProtocolOp` type on each incoming message before forwarding. Unrecognized or disallowed ops return an immediate `LDAPResult(53, unwillingToPerform)` without forwarding.

**Alternatives considered**: Apache Directory Server (LDAP server library): Rejected — too heavyweight for a transparent proxy use case; includes directory functionality not needed here. Pure Netty LDAPv3 codec from scratch: Rejected — UnboundID already provides a complete codec.

---

## Topic 10: openkim Submodule Integration

**Decision**: Fork https://github.com/sberg-net/openkim into the organization's GitHub organization, then add it as a git submodule at `app/openkim/`. A `KomLeAdapter` CDI bean wraps the openkim Spring Boot context (openkim uses Spring Boot internally) as an embedded application within the Quarkus JVM using Spring Boot's programmatic startup API.

**Rationale**: openkim is a Spring Boot application. Running it as an embedded context within the Quarkus JVM is feasible (both use the standard JVM; Spring Boot's `SpringApplication.run()` can be called from a CDI `@Startup` method). This avoids the operational complexity of running openkim as a separate microservice sidecar, and allows both to share the same `CryptoProvider` CDI bean for signing operations.

**Spring Boot / Quarkus coexistence constraint**: Spring Boot and Quarkus can coexist on the JVM; they use different classloaders and IoC containers. The shared surface is: (a) the `CryptoProvider` interface (passed to openkim via configuration/callback), and (b) the SMTP/POP3 ports that openkim opens. CDI beans do NOT automatically inject into Spring beans; the bridge is explicit (adapter pattern).

**Fork synchronization**: The CI pipeline includes a weekly job that checks the openkim upstream for new releases and opens a pull request to the fork when updates are available. The submodule pin is updated via PR review.

**Alternatives considered**: openkim as a sidecar container in the Kubernetes pod: Architecturally cleaner separation but adds inter-process HTTP/gRPC communication for every KOM-LE crypto operation, introducing latency and a network failure mode. Rejected for v1; re-evaluate in v2 if the embedded context causes stability issues.

---

## Resolution of NEEDS CLARIFICATION Items

None — the spec had no `[NEEDS CLARIFICATION]` markers. All design decisions above are consistent with the spec's Assumptions section.
