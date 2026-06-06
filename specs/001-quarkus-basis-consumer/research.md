# Research: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Branch**: `001-quarkus-basis-consumer` | **Date**: 2026-06-05 (updated 2026-06-06)

Topics 1–7 cover the pluggable cryptographic provider and SICCT Quarkus extension. Topics 8–10 cover SOAP services (Apache CXF), LDAP proxy, and openkim submodule integration. Topics 11–16 cover the new module structure: gemLibPki integration, beanit jASN1, Apache CXF wsdl2java from git submodule, connector.sds, JPA in a library module, and SICCT UDP discovery.

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

**Decision**: Fork https://github.com/sberg-net/openkim into the organization's GitHub organization, then add it as a git submodule at `openkim-server/`. Deploy it as a **standalone Spring Boot Pod** per tenant Kubernetes namespace — NOT embedded in the Quarkus `basis-consumer-server` pod (FR-070).

**Rationale**: openkim is a fully self-contained Spring Boot application with its own SMTP/POP3 server, KIM-Fachdienst connectivity, and TLS configuration. Running it as a separate pod provides operational independence, independent scaling, and clean process isolation. The `basis-consumer-server` communicates with `openkim-server` over SMTP/POP3 on the pod-internal network for KIM delegation; mail clients connect to `openkim-server` directly. `openkim-server` reaches the TI KIM-Fachdienst via the SZZP.

**Architecture**:
- `openkim-server` pod: standalone Spring Boot, SMTP+POP3 listener, connects to KIM-Fachdienst via SZZP
- `basis-consumer-server` pod: delegates KIM operations by submitting/retrieving mail via SMTP/POP3 to `openkim-server`
- No shared CDI context; communication is purely over SMTP/POP3 protocol

**Fork synchronization**: The CI pipeline includes a weekly job that checks the openkim upstream for new releases and opens a pull request to the fork when updates are available. The submodule pin is updated via PR review.

**Alternatives considered**: openkim embedded in the Quarkus JVM: Rejected — Spring Boot and Quarkus embedded in the same JVM creates classloader conflicts and couples the operational lifecycles of two independent components; a restart of the Quarkus pod would interrupt KOM-LE mail delivery.

---

---

## Topic 11: gemLibPki@4.0.2 — Security Review and Integration

**Decision**: Adopt `de.gematik.pki:gemLibPki:4.0.2` as the primary TI PKI library in `crypto-lib`. Use it for TSL download, X.509 certificate validation, OCSP, and registration number extraction.

**Security Review** (Principle VII gate):

| Criterion              | Result                                                                                                                |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------- |
| Active maintenance     | ✅ Published by gematik (gematik GmbH); last release 2024; maintained under gematik's open-source programme            |
| Known CVEs             | ✅ No unpatched CVSS ≥ 7 in gemLibPki itself; transitive Bouncy Castle 1.77 has no open critical CVEs at adoption time |
| License                | ✅ Apache License 2.0                                                                                                  |
| gematik certification  | ✅ Produced by the specification authority; aligned with gemSpec_PKI; used in gematik's own reference implementations  |
| Supply-chain integrity | ✅ Published on Maven Central with PGP signature; SHA-256 checksum verified                                            |

**Review outcome**: PASS — library adopted; SBOM entry created.

**Rationale**: gemLibPki is gematik's own reference implementation for TI PKI operations. It subsumes custom TSL download logic and OCSP validation that would otherwise require > 2,000 lines of compliant code with a high defect risk in a safety-critical domain. It also includes Bouncy Castle as a transitive dependency, centralising the JCA provider selection.

**Integration pattern**:
```java
// TSL download + trust anchor setup in TslDownloader.java
TslDownloader downloader = new TslDownloader(tslUrl);
TrustStoreManager tsm = downloader.downloadTsl();

// Cert validation in TrustService.java
CertificateVerifier verifier = new CertificateVerifier(tsm);
CertificateCheckResult result = verifier.performTucPki018Checks(cert, referenceDate, policyOid);
```

**Alternatives considered**: Custom TSL parser (Apache CXF XML + JAXB): Rejected — TSL schema is complex; implementing TUC_PKI_018 correctly without the reference library introduces compliance risk that no project-internal review can adequately mitigate.

---

## Topic 12: beanit jASN1 — Security Review and Maven Code Generation Setup

**Decision**: Adopt `com.beanit:jasn1:1.13.x` for ASN.1 code generation from `SICCT.asn1`. Code generation runs as a Maven plugin invoked during the `generate-sources` phase of `sicct-lib`.

**Security Review** (Principle VII gate):

| Criterion              | Result                                                                       |
| ---------------------- | ---------------------------------------------------------------------------- |
| Active maintenance     | ✅ Last release 2023; actively maintained; used by the opentelecoms community |
| Known CVEs             | ✅ No CVEs in NVD or OSS-Index for jasn1                                      |
| License                | ✅ Apache License 2.0                                                         |
| Certification status   | N/A — code generator, not a runtime protocol stack                           |
| Supply-chain integrity | ✅ Published on Maven Central; PGP-signed releases                            |

**Review outcome**: PASS — library adopted; SBOM entry created.

**Maven plugin configuration** (in `sicct-lib/pom.xml`):
```xml
<plugin>
  <groupId>com.beanit</groupId>
  <artifactId>jasn1-compiler</artifactId>
  <version>1.13.x</version>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
      <configuration>
        <files><file>${project.basedir}/src/main/asn1/SICCT.asn1</file></files>
        <outputDir>${project.build.directory}/generated-sources/asn1</outputDir>
        <rootPackage>de.servicehealtherx.sicct.asn1</rootPackage>
      </configuration>
    </execution>
  </executions>
</plugin>
```
Generated classes land in `target/generated-sources/asn1/` — NOT committed to git. The `generate-sources` phase regenerates them on every build.

**Constraint**: The `SICCT.asn1` file uses `IMPLICIT TAGS` at the module level and application/context-class tags. jASN1 supports these; verify round-trip in `SicctAsn1RoundTripTest` per SC-015.

---

## Topic 13: Apache CXF wsdl2java from Git Submodule in Maven

**Decision**: Configure `cxf-codegen-plugin` in both `consumer-soap-server/pom.xml` and `konnektor-soap-server/pom.xml` with WSDL paths relative to the `api-telematik/` submodule directory at the repository root.

**Pattern**:
```xml
<!-- consumer-soap-server/pom.xml -->
<plugin>
  <groupId>org.apache.cxf</groupId>
  <artifactId>cxf-codegen-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>wsdl2java</goal></goals>
      <configuration>
        <wsdlOptions>
          <wsdlOption>
            <wsdl>${project.basedir}/../api-telematik/consumer/CertificateService.wsdl</wsdl>
          </wsdlOption>
          <!-- EncryptionService.wsdl, SignatureService.wsdl similarly -->
        </wsdlOptions>
      </configuration>
    </execution>
  </executions>
</plugin>
```
The konnektor-soap-server uses `../api-telematik/conn/` paths to the 7 conn/ WSDLs.

**Constraint**: The git submodule must be initialized (`git submodule update --init --recursive`) before running `mvn generate-sources`. CI pipeline enforces this in the pre-build step. Maven multi-module reactor order ensures `api-telematik` is present before either SOAP server module is compiled.

**MTOM**: `consumer-soap-server` enables MTOM for `EncryptionService` and `SignatureService` via `<enableMTOM>true</enableMTOM>` in the CXF endpoint configuration; `konnektor-soap-server` similarly for its Encryption/Signature endpoints.

---

## Topic 14: connector.sds Generation from ServiceDirectory.xsd

**Decision**: Generate `connector.sds` at runtime via JAXB marshalling from a `ServiceDirectory` object constructed from the application's known endpoint list; serve it as a plain XML endpoint at `/connector.sds`.

**Rationale**: `connector.sds` is a gematik-defined XML document describing the services a Konnektor exposes. Its schema is `ServiceDirectory.xsd` (in `api-telematik/conn/`). Generating it at runtime ensures the endpoint URLs reflect the actual running server address (hostname, port, context path), eliminating the manual synchronisation problem of a committed static file.

**Implementation**:
```java
// ConnectorSdsResource.java in konnektor-soap-server
@Path("/connector.sds")
@Produces(MediaType.TEXT_XML)
public class ConnectorSdsResource {
    public String getConnectorSds() {
        // Build ServiceDirectory JAXB object from known CXF endpoints
        // Marshal to XML string; return
    }
}
```

**Alternatives considered**: Static committed XML file: Rejected — endpoint URLs embed hostname/port which vary per deployment; runtime generation avoids the maintenance burden. CXF `ServiceListFeature`: Does not produce the gematik-specific `ServiceDirectory` schema format.

---

## Topic 15: JPA Entities in a Library Module (sicct-lib)

**Decision**: Place `CardTerminal` (`@Entity`) in `sicct-lib`. Use Quarkus Panache's active-record pattern directly on the entity — no separate DAO or repository class. The JPA data source is configured in the parent application (whichever SOAP server module runs the full Quarkus application context).

**Naming convention**: The entity is named `CardTerminal` (not `CardTerminalRecord`). No DAO or Repository suffix classes are created; Panache active-record provides `persist()`, `find()`, `listAll()`, etc. directly on the entity class. This follows the Panache idiom and avoids the anti-pattern of a mirroring DAO class for every entity.

**Rationale**: The card terminal registry must be accessible from both the `quarkus-sicct-extension` (which manages terminal connections) and management tooling. Placing it in a shared library module (`sicct-lib`) avoids duplicating the entity definition or creating a circular dependency between extension and SOAP server modules.

**Quarkus Panache in library**: Quarkus discovers `@Entity` classes from all modules on the classpath during the deployment phase via byte-buddy instrumentation. Placing entities in a `sicct-lib` jar is standard practice for Quarkus multi-module projects. The `persistence.xml` or `quarkus.datasource` configuration remains in the application module (or both SOAP servers if both need DB access).

**Constraint**: `sicct-lib/pom.xml` declares `quarkus-hibernate-orm-panache` as a `provided` scope dependency (or `import` via BOM) to avoid pulling the full Quarkus stack into `sicct-lib` for unit tests. Unit tests of `sicct-lib` that test JPA behavior use `@QuarkusTest` with an embedded Derby via Testcontainers.

---

## Topic 16: SICCT Card Terminal UDP Service Discovery

**Decision**: Implement UDP multicast-based discovery using Netty's `NioDatagramChannel` (or `EpollDatagramChannel` on Linux) inside `SicctTerminalManager`. The Netty `Bootstrap` for UDP reuses the same `NioEventLoopGroup` already used for TCP terminal connections, keeping the thread model uniform. On receiving a SICCT announcement datagram, the handler parses the datagram to extract the terminal's IP and TCP port, then persists a `CardTerminal` JPA entity for newly discovered terminals (de-duplicated by host+port).

**Rationale**: `quarkus-sicct-extension` already depends on Netty for TCP connection management. Reusing Netty for UDP discovery avoids introducing a second I/O framework (Java NIO `DatagramChannel`), keeps the thread lifecycle under a single Quarkus-managed `EventLoopGroup`, and allows the UDP channel to be shut down as part of the same `@PreDestroy` teardown that closes TCP channels. Netty also provides built-in `DatagramPacket` decoding and exception handling that integrates with the existing pipeline model.

**Pattern**:
```java
// In SicctTerminalManager — started during @PostConstruct, shared EventLoopGroup
Bootstrap udpBootstrap = new Bootstrap()
    .group(eventLoopGroup)
    .channel(NioDatagramChannel.class)
    .option(ChannelOption.SO_REUSEADDR, true)
    .handler(new SicctDiscoveryHandler(cardTerminalRepository));

udpBootstrap.bind(SICCT_DISCOVERY_PORT).sync();

// SicctDiscoveryHandler extends SimpleChannelInboundHandler<DatagramPacket>
//   → parseSicctAnnouncement(packet) → extract host+port → CardTerminal JPA entity
```

**Alternatives considered**:
- Java NIO `DatagramChannel` standalone: Rejected — adds a separate thread/channel lifecycle outside Quarkus Netty management; inconsistent with the TCP connection model already in the extension.
- Static config only (host+port in DB via Hawtio): Still required as the fallback path — UDP discovery populates initial records, but operators can always add terminals manually via the management console per FR-096–FR-099.

---

---

## Topic 17: CryptoParameters — Normative Algorithm Requirements from gemSpec_Krypt_V2.49.0

**Decision**: The `CryptoParameters` value object carries seven fields: `algorithm: AlgorithmType`, `eccCurve: EccCurve`, `hashAlgorithm: HashAlgorithm`, `iv: byte[]`, `aad: byte[]`, `saltLength: int`, and `kdfInfo: byte[]`. The enum values below are derived normatively from gemSpec_Krypt V2.49.0 (Stand: 11.05.2026).

**AlgorithmType enum** — complete set required by TI:

| Value | TI Requirement | Source |
|-------|---------------|--------|
| `ECDSA` | Document/hash signatures; SICCT pairing ShS.AUT.KT signature; preferred algorithm until 2029+ | Tab_KRYPT_009, Tab_KRYPT_020, GS-A_5207-01 |
| `RSA_PSS` | RSASSA-PSS with SHA-256; compatible with existing cards; valid until end 2025 | GS-A_4371-02, Tab_KRYPT_009 |
| `RSA_PKCS1` | RSASSA-PKCS1-v1_5; legacy compatibility | GS-A_5071-01 |
| `RSAES_OAEP` | RSA key transport for XML hybrid encryption | GS-A_4376-02 |
| `AES_128_GCM` | AES-128/GCM; ePA-VAU ECIES content encryption (HKDF-derived 128-bit CEK) | §3.19 (A_27275), §4.7 (A_27275 step 4) |
| `AES_256_GCM` | AES-256/GCM; mandatory for XML encryption, binary ECIES content key, VSDM backup, JavaDB backup | GS-A_4373, A_17220, FR-180 |
| `AES_128_CBC` | AES-128/CBC; Card-to-Server authentication only | Tab_KRYPT_012 |
| `ECIES` | TI-ECIES per [SEC1-2009]: ephemeral ECDH + HKDF-SHA-256 + AES-GCM; used in KOM-LE, VSDM, ePA-VAU | A_17220, A_17221-01, A_27275 |

**EccCurve enum** — curves required by TI:

| Value | Usage | Source |
|-------|-------|--------|
| `NIST_P256` | ePA-VAU ECIES (P-256 ENC certificate), ECDSA verification | §3.19 (A_27275 step 1), A_17094-02 |
| `NIST_P384` | TLS ECDHE (mandatory), future card generations | A_17089-03, A_17094-02 |
| `BRAINPOOLP256R1` | Primary TI ECC curve; SICCT pairing ECDSA, KOM-LE ECIES, SMC-B G2+ cards | GS-A_5207-01, A_17090-01, Tab_KRYPT_009 |
| `BRAINPOOLP384R1` | TLS ECDHE (SHOULD support) | A_17089-03, A_17094-02 |

**HashAlgorithm enum**:

| Value | Usage | Source |
|-------|-------|--------|
| `SHA256` | Mandatory for all TI signatures, HKDF, VALIDATE challenge-response | GS-A_4371-02, GS-A_4372-02, GS-A_5091 |
| `SHA384` | BNetzA-VL signature verification; optional TLS | A_27608 |
| `SHA512` | BNetzA-VL signature verification | A_27608 |

**Key CryptoParameters field semantics** (normative):

- `saltLength` — RSA-PSS only: per GS-A_5091 saltLen = hashLen = 256 bits (32 bytes); set to -1 to use hash-length default
- `iv` — AES-GCM: 96-bit (12-byte) random IV per GS-A_4373; caller generates if null
- `aad` — AES-GCM only; the authenticated-but-not-encrypted associated data; null if unused
- `kdfInfo` — ECIES HKDF info string per [RFC-5869]; e.g. `"ecies-vau-transport"` for ePA-VAU (A_27275 step 3); null for non-ECIES algorithms

**ECIES protocol parameters** (A_17220, A_27275):
1. Generate ephemeral ECDH key pair on recipient's certificate curve (`NIST_P256` or `BRAINPOOLP256R1`)
2. ECDH → shared secret → HKDF-SHA-256(ikm=secret, info=kdfInfo) → CEK
3. CEK length: 256 bits for binary ECIES (A_17220); 128 bits for ePA-VAU (A_27275)
4. Encrypt content with AES/GCM, 96-bit random IV, 128-bit tag
5. Output: `0x04 ‖ X(32) ‖ Y(32) ‖ IV(12) ‖ ciphertext ‖ GCMtag(16)` — ASN.1 packed as `(PO, C, T)` tuple

**Rationale**: The single `AlgorithmType.AES_GCM` was insufficient because TI specifies both AES-128-GCM (ePA-VAU) and AES-256-GCM (XML encryption, binary ECIES) contexts with different key sizes. Splitting into `AES_128_GCM` / `AES_256_GCM` makes key-size requirements explicit at the API level, preventing accidental key-size mismatches. The `kdfInfo` field is needed because ECIES HKDF derivation uses protocol-specific info strings (e.g. `"ecies-vau-transport"`) that cannot be implied from the algorithm type alone.

**Alternatives considered**: Single `AES_GCM` with separate `keyLengthBits: int` field — rejected because the key is held inside the KeyStoreAdapter and its length is determined by the key material; the algorithm designator must encode the expected key size to allow the adapter to select or validate the right key.

---

## Topic 18: JMX Management Beans — Standard MBean Pattern in Quarkus (Principle VIII)

**Decision**: Implement all management beans as standard JMX MBeans per JSR 3 and `javax.management.*`, registered on `ManagementFactory.getPlatformMBeanServer()` via `@PostConstruct` in a `@ApplicationScoped` CDI bean. Hawtio (already embedded) automatically discovers all registered MBeans without additional configuration. No Quarkus-specific JMX abstraction is introduced; the JDK's `javax.management` package is the mandated standard (Principle VIII).

**Standard MBean pattern** (compile-time contract; Hawtio reads attribute/operation metadata via reflection):

```java
// Interface — mandatory; same name as implementation + "MBean" suffix
public interface TslManagementMBean {
    String reloadTsl();
    String getTslStatus();
    String getTslUrl();  // attribute: getter defines read-only attribute
}

@ApplicationScoped
public class TslManagement implements TslManagementMBean {

    @Inject TslDownloader tslDownloader;

    private ObjectName objectName;

    @PostConstruct
    void register() throws Exception {
        objectName = new ObjectName(
            "de.servicehealtherx:module=crypto-lib,name=TslManagement");
        ManagementFactory.getPlatformMBeanServer().registerMBean(this, objectName);
    }

    @PreDestroy
    void unregister() throws Exception {
        ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
    }

    @Override public String reloadTsl() { ... }
    @Override public String getTslStatus() { ... }
    @Override public String getTslUrl() { return tslDownloader.getConfiguredUrl(); }
}
```

**Hawtio integration**: Hawtio in Quarkus JVM mode connects to `ManagementFactory.getPlatformMBeanServer()` of the same JVM. No additional connector, agent, or property is required. All `de.servicehealtherx:*` ObjectNames appear in the Hawtio JMX browser under the `de.servicehealtherx` domain node, with sub-nodes per `module` and `name` key values.

**ObjectName convention**:
```
de.servicehealtherx:module=<module-folder-name>,name=<ClassName>
Examples:
  de.servicehealtherx:module=crypto-lib,name=TslManagement
  de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalConnectionManagement
  de.servicehealtherx:module=crypto-services-lib,name=SignatureServiceManagement
```
The `module` key matches the Maven module folder name exactly (FR-220). Hawtio groups MBeans by the first key value after the domain, so choosing `module` as the first key produces a clean two-level tree in the browser.

**Security constraints**:
- Remote JMX connector (`com.sun.management.jmxremote`): MUST be disabled in production (see FR-232). Hawtio in embedded mode does not require a remote JMX connector — it connects in-process.
- JVM flag: `-Dcom.sun.jndi.rmi.object.trustURLCodebase=false` MUST be set (already default in Java 17+; explicit for documentation).
- Hawtio RBAC: Hawtio's `RBACRegistry` role attribute can restrict `invoke` operations to authenticated admin users. All write/action operations (everything except getters) MUST require the `admin` role in the Hawtio role configuration.

**Unit test pattern** (FR-234 mandate):
```java
class TslManagementTest {
    private MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    private TslDownloader mock = Mockito.mock(TslDownloader.class);

    @BeforeEach void setUp() throws Exception {
        TslManagement bean = new TslManagement(mock);
        bean.register();  // or call @PostConstruct manually
    }

    @Test
    void test_FR220_TslManagement_reloadTsl_invokes_tsl_downloader() throws Exception {
        ObjectName on = new ObjectName("de.servicehealtherx:module=crypto-lib,name=TslManagement");
        String result = (String) mbs.invoke(on, "reloadTsl", new Object[0], new String[0]);
        Mockito.verify(mock).download();
        assertThat(result).contains("\"status\":\"OK\"");
    }
}
```

**Alternatives considered**:
- **Quarkus Micrometer management endpoint**: Provides metrics (counters, gauges), not on-demand action invocation (e.g. `reloadTsl()`). Rejected for management operations; Micrometer is still used for observability metrics.
- **Custom REST management endpoints**: Would require an additional security layer, URL routing, and documentation separate from Hawtio. Rejected — Hawtio already provides a JMX browser UI with authentication; duplicating this via REST adds surface area.
- **CDI events / observer methods**: Fire-and-forget; no return value from the action; no Hawtio UI for invocation. Rejected.
- **Dynamic MBean (`DynamicMBean`)**: Would avoid the `XxxMBean` compile-time interface contract. Rejected — standard MBeans provide compile-time type safety and cleaner code; the number of MBeans here is finite and well-defined.
- **Quarkus-specific `@ManagedBean` or SmallRye JMX**: No stable Quarkus extension provides JMX management bean lifecycle management that does not ultimately delegate to `ManagementFactory.getPlatformMBeanServer()`. The direct approach is simpler and avoids an unnecessary abstraction layer.

**Package placement** (FR-005 and FR-220): Each MBean's Java package is `de.servicehealtherx.<module-suffix>.jmx` where `<module-suffix>` follows the FR-005 derivation rule. Example: `crypto-lib` → `de.servicehealtherx.crypto.jmx`; `quarkus-sicct-extension` runtime → `de.servicehealtherx.quarkus.sicct.runtime.jmx`.

---

## Resolution of NEEDS CLARIFICATION Items

None — the spec had no `[NEEDS CLARIFICATION]` markers. All design decisions above are consistent with the spec's Assumptions section.
