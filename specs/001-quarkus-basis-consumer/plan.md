# Implementation Plan: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Branch**: `001-quarkus-basis-consumer` | **Date**: 2026-06-05 | **Spec**: [spec.md](spec.md)

## Summary

A multi-tenant, cloud-native TI platform product providing encryption, signature, certificate, LDAP-proxy, KOM-LE, SICCT card terminal, and Konnektor SOAP compatibility services. The system is structured as 9 independent Maven modules: `api-telematik` and `openkim-server` as git submodules; `crypto-lib` (gemLibPki@4.0.2, TSL management, cert parsing, CDI beans); `sicct-lib` (beanit jASN1 code generation from SICCT.asn1, EHEALTH AUTHENTICATE, JCE KeyStoreSPI for SMC-B/eHBA, JPA terminal registry, CDI beans); `quarkus-sicct-extension` (UDP service discovery, Netty TCP client pool, Quarkus lifecycle); `crypto-services-lib` (SignatureService, EncryptionService, CertificateService backed by crypto-lib — no middleware dependency); `quarkus-ldap-proxy-server-extension` (Netty LDAPv3 VZD proxy). Two Apache CXF SOAP servers expose these services: `consumer-soap-server` (URL `/consumer`, 3 services from `consumer/` WSDLs) and `konnektor-soap-server` (URL `/conn`, 7 services from `conn/` WSDLs + connector.sds).

## Technical Context

**Language/Version**: Java 21 LTS

**Primary Dependencies**:

| Dependency                                                                                        | Purpose                                                                                                                                                     |
| ------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Quarkus 3.x                                                                                       | Application framework (CDI, Health, Config, REST, Scheduler)                                                                                                |
| quarkus-cxf                                                                                       | Apache CXF integration — all SOAP services (EncryptionService, SignatureService, CertificateService, Konnektor compat)                                      |
| Netty 4.x                                                                                         | SICCT TCP/IP client connections (managed by SICCT Quarkus extension)                                                                                        |
| SunPKCS11 (JDK built-in)                                                                          | PKCS#11 HSM integration; configured via `Security.getProvider("SunPKCS11").configure(cfg)`                                                                  |
| javax.management (JDK built-in)                                                                   | JMX MBeans for operator management via Hawtio; standard MBean pattern per JSR 3; registered on `ManagementFactory.getPlatformMBeanServer()` (FR-220–FR-234) |
| javax.smartcardio (JDK built-in)                                                                  | PC/SC card reader integration; monitoring via `CardTerminals.waitForChange()`                                                                               |
| gemLibPki 4.0.2 (`de.gematik.pki:gemLibPki`)                                                      | TI PKI: TSL download + caching, X.509 cert validation, OCSP, RegNr extraction; includes Bouncy Castle                                                       |
| Bouncy Castle 1.7x (transitively via gemLibPki; BC FIPS variant for direct P12 ops in production) | P12 parsing, ECC/RSA JCA operations where gemLibPki does not directly cover                                                                                 |
| openkim (git submodule)                                                                           | KOM-LE / KIM client module (SMTP/POP3 interfaces, S/MIME encrypt/sign)                                                                                      |
| JPA + H2                                                                                          | Tenant configuration persistence, audit log, `AppConfigProperty` table for DB-backed MicroProfile Config                                                    |
| configsource-db (`org.microprofile-ext.config-ext:configsource-db`)                               | MicroProfile ConfigSource backed by JavaDB `AppConfigProperty` table; all production config read from DB (FR-028)                                           |
| Hawtio                                                                                            | Operator management web console (embedded Quarkus)                                                                                                          |
| SoftHSM2                                                                                          | CI-only PKCS#11 emulator (installed on CI runner; not bundled)                                                                                              |
| JUnit 5 + Quarkus Test                                                                            | Unit and integration testing                                                                                                                                |
| Testcontainers                                                                                    | Container-isolated integration tests (DB, VPN stub)                                                                                                         |

**Storage**: JPA + H2. JPA entities: `CardTerminal` (terminal registration + pairing + connection timeouts, managed via Hawtio); `AppConfigProperty` (MicroProfile Config property store — columns `PROPNAME` / `PROPVALUE` — read by `configsource-db` so all production configuration lives in the DB and a DB dump is a full backup). Crypto key material is NEVER stored in the database; it resides exclusively in HSM / card / P12 file. Runtime state (key source availability, SICCT connection state, PC/SC slot state) is held in `@ApplicationScoped` CDI beans.

**Testing**:
- Unit: JUnit 5; direct bean construction with mocked dependencies
- Integration: `@QuarkusTest`; SoftHSM2 via `QuarkusTestResourceLifecycleManager` for PKCS#11; Netty `EmbeddedChannel`-based mock for SICCT; `jnasmartcardio` virtual reader for PC/SC; embedded H2 for DB; WireMock/CXF test server for SOAP
- CI: All hardware-dependent tests use software emulators; no physical HSM, card terminal, or USB reader required in CI
- Afo traceability: one named test per Afo ID (`test_A_XXXXX_...`); one commit per Afo

**Target Platform**: Linux server, JVM mode; Kubernetes pod. Quarkus native mode is explicitly excluded from v1 scope (SunPKCS11 and javax.smartcardio are not native-image compatible without significant GraalVM configuration).

**Project Type**: Multi-module Maven application — 9 modules (2 git submodules + 7 Maven modules):

| #   | Module                                | Role                                                                                                                |
| --- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| 1   | `api-telematik`                       | git submodule (med-united/api-telematik fork) — WSDL/XSD source for all SOAP servers                                |
| 2   | `openkim-server`                      | git submodule (sberg-net/openkim) — KOM-LE/KIM SMTP+POP3                                                            |
| 3   | `crypto-lib`                          | Library — gemLibPki@4.0.2, TSL management, CertificateParser; CDI beans                                             |
| 4   | `sicct-lib`                           | Library — beanit jASN1 codegen, EHEALTH AUTHENTICATE, JCE KeyStoreSPI, JPA terminal registry; CDI beans             |
| 5   | `quarkus-sicct-extension`             | Quarkus extension (deployment+runtime) — UDP discovery, Netty TCP pool                                              |
| 6   | `crypto-services-lib`                 | Library — SignatureService, EncryptionService, CertificateService (crypto-lib only; no sicct dependency); CDI beans |
| 7   | `quarkus-ldap-proxy-server-extension` | Quarkus extension (deployment+runtime) — Netty LDAPv3 proxy to TI VZD                                               |
| 8   | `consumer-soap-server`                | Apache CXF SOAP server — URL `/consumer`; 3 services from `consumer/` WSDLs                                         |
| 9   | `konnektor-soap-server`               | Apache CXF SOAP server — URL `/conn`; 7 services from `conn/` WSDLs + connector.sds                                 |

Maven dependency chain: `crypto-lib` ← `sicct-lib` ← `quarkus-sicct-extension`; `crypto-lib` ← `crypto-services-lib` ← `consumer-soap-server`; `crypto-services-lib` + `quarkus-sicct-extension` ← `konnektor-soap-server`.

**Performance Goals**:
- All SOAP operations: ≤ 2 seconds (system-controlled path; external hardware latency excluded)
- SICCT disconnect detection: ≤ 10 seconds
- SICCT reconnection: ≤ 15 seconds
- PC/SC / SICCT card alias update: ≤ 10 seconds / ≤ 5 seconds after card event
- Tenant provisioning: fully operational within 10 minutes

**Constraints**:
- 150 MB resident memory ceiling (constitution Principle IV); Netty SICCT channels ~100 KB each; 10 terminals ≈ 1 MB overhead — acceptable
- Private key bytes MUST NOT appear in JVM heap, logs, or network traces (A_17598)
- Production HSM: FIPS 140-2 Level 3 or CC EAL4 certified (A_17598)
- SoftHSM2 restricted to CI environments
- All new Maven dependencies require SBOM entry + license + security review (A_27431)
- Interactive PIN entry required for SICCT (terminal trusted PIN pad via VERIFY PIN command) and PC/SC (FEATURE_VERIFY_PIN_DIRECT for class 2/3 readers) per FR-029; static PIN fallback for class-1 readers and CI only

**Scale/Scope**: 50 concurrent tenants; 1–10 key sources per tenant; up to 200 concurrent SOAP requests under load.

## Constitution Check

| Gate                                                   | Status | Notes                                                                                                                                                                                                                                                                            |
| ------------------------------------------------------ | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Code Quality: single-responsibility modules            | ✅ PASS | 9 Maven modules with clear ownership; each has one stated purpose                                                                                                                                                                                                                |
| Code Quality: no single-implementation interfaces      | ✅ PASS | No interfaces without multiple implementations; CXF-generated WSDL interfaces are exempt (externally mandated)                                                                                                                                                                   |
| Code Quality: no Impl suffix in class names            | ✅ PASS | Service impl classes named by role: ConsumerSignatureService, KonnektorCardService, SicctKeyStoreSpi, etc.                                                                                                                                                                       |
| Testing: 80% coverage floor                            | ✅ PASS | All adapters have unit tests; hardware deps replaced by emulators in CI                                                                                                                                                                                                          |
| Testing: test-first mandate                            | ✅ PASS | CDI contracts defined before implementation; SOAP contracts from published WSDLs                                                                                                                                                                                                 |
| Testing: no hardware-dependent CI tests                | ✅ PASS | SoftHSM2, EmbeddedChannel, virtual PC/SC, embedded H2                                                                                                                                                                                                                            |
| Security: key material never extracted                 | ✅ PASS | PKCS#11/PC/SC/SICCT: on-device; P12: JVM-local Signature object, never serialized                                                                                                                                                                                                |
| Security: production HSM FIPS 140-2 L3                 | ✅ PASS | Enforced by Assumption; SoftHSM2 CI-only                                                                                                                                                                                                                                         |
| Security: SAST/SCA in CI                               | ✅ PASS | Existing pipeline; new deps (Bouncy Castle, Netty, openkim submodule) added to SBOM                                                                                                                                                                                              |
| Security: OWASP Top 10                                 | ✅ PASS | No user input parsing in crypto path; config values typed/validated at startup                                                                                                                                                                                                   |
| Performance: 150 MB memory ceiling                     | ✅ PASS | ~1 MB Netty overhead for 10 SICCT connections; well within budget                                                                                                                                                                                                                |
| Performance: 2s SOAP operation budget                  | ✅ PASS | System path <10 ms; budget dominated by HSM/card hardware latency                                                                                                                                                                                                                |
| Dependency hygiene: new deps justified                 | ✅ PASS | Each dependency in Technical Context has a stated capability gap                                                                                                                                                                                                                 |
| Principle VII: external library evaluation documented  | ✅ PASS | gemLibPki@4.0.2 evaluated in research.md (Topic 11); chosen over custom TSL/cert impl. beanit jASN1 evaluated (Topic 12); security reviews documented                                                                                                                            |
| Principle VIII: standard interface adoption documented | ✅ PASS | JMX MBeans implement `javax.management.MBeanServer` standard MBean pattern (JSR 3); `KeyStoreAdapter` extends `java.security.KeyStoreSpi` (JCA); `KeyReference` extends `java.security.Key`; `CryptoProviderHealthCheck` implements `HealthCheck`; all documented in research.md |
| Afo traceability                                       | ✅ PASS | Per-Afo commits and named tests mandatory; traceability report in CI                                                                                                                                                                                                             |
| UX: Hawtio console                                     | ✅ PASS | Operator console uses Hawtio (existing, design-system-compliant within its own UI)                                                                                                                                                                                               |
| Accessibility                                          | ✅ N/A  | No patient-facing UI; Hawtio is operator-only tooling                                                                                                                                                                                                                            |

**Complexity justification** (multi-module):

| Extra module                                     | Why needed                                                                         | Simpler alternative rejected because                                                                           |
| ------------------------------------------------ | ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `sicct-quarkus-extension` (deployment + runtime) | User-mandated Quarkus extension; build-time config validation via `SicctProcessor` | Single module prevents build-time validation and forces full Quarkus bootstrap in unit tests of SICCT adapters |
| `crypto-provider`                                | Independent testability of JCE adapters without Quarkus bootstrap                  | Merging into `app` couples unit tests to the full application context                                          |

## Project Structure

### Documentation (this feature)

```text
specs/001-quarkus-basis-consumer/
├── spec.md              # Feature specification (this document's source)
├── plan.md              # This file
├── research.md          # Phase 0 research findings
├── data-model.md        # Entity definitions + state machines
├── quickstart.md        # Runnable validation scenarios
├── contracts/
│   ├── crypto-provider-interface.md   # CryptoProvider CDI interface contract
│   └── sicct-extension-config.md      # SICCT extension configuration schema
└── checklists/
    └── requirements.md
```

### Source Code (repository root)

```text
pom.xml                                              # parent POM (manages all module versions)

api-telematik/                                       # git submodule → med-united/api-telematik
├── consumer/                                        # WSDL/XSD for consumer-soap-server
│   ├── CertificateService.wsdl (v3.0.1)
│   ├── EncryptionService.wsdl  (v3.0.1)
│   └── SignatureService.wsdl   (v3.2.1)
└── conn/                                            # WSDL/XSD for konnektor-soap-server
    ├── AuthSignatureService_v7_4_1.wsdl
    ├── CardService_v8_2_1.wsdl
    ├── CardTerminalService_v1_1_0.wsdl
    ├── CertificateService_v6_0_3.wsdl
    ├── EncryptionService_v6_1_1.wsdl
    ├── EventService_v7_2_0.wsdl
    ├── SignatureService_V7_5_7.wsdl
    └── ServiceDirectory.xsd

openkim-server/                                      # git submodule → sberg-net/openkim

crypto-lib/
├── pom.xml                                          # depends on de.gematik.pki:gemLibPki:4.0.2
└── src/
    ├── main/java/de/servicehealtherx/crypto/
    │   ├── TslDownloader.java                       # downloads + caches TI TSL; @ApplicationScoped
    │   ├── CertificateParser.java                   # extracts RegNr, card type, EKU from X.509
    │   ├── TrustService.java                        # @ApplicationScoped CDI bean; validates certs
    │   └── jmx/
    │       ├── TslManagementMBean.java              # JMX MBean interface (FR-223)
    │       ├── TslManagement.java                   # @ApplicationScoped; registers with PlatformMBeanServer @PostConstruct
    │       ├── CryptoProviderManagementMBean.java   # JMX MBean interface (FR-226)
    │       ├── CryptoProviderManagement.java        # lists KeyStoreAdapters + KeyReferences + availabilities
    │       ├── KeyStoreReloadManagementMBean.java   # JMX MBean interface (FR-227)
    │       └── KeyStoreReloadManagement.java        # triggers engineLoad() on KeyStoreAdapter instances
    └── test/java/de/servicehealtherx/crypto/
        ├── TslDownloaderTest.java
        ├── CertificateParserTest.java
        └── jmx/
            ├── TslManagementTest.java               # registers MBean on test MBeanServer; mocks TslDownloader (FR-234)
            ├── CryptoProviderManagementTest.java
            └── KeyStoreReloadManagementTest.java

sicct-lib/
├── pom.xml                                          # depends on crypto-lib, javax.smartcardio (JDK)
└── src/
    ├── main/
    │   ├── asn1/SICCT.asn1                          # committed ASN.1 schema source
    │   └── java/de/servicehealtherx/sicct/
    │       ├── asn1/                                # generated by beanit jASN1 (target/generated-sources — NOT committed)
    │       ├── EhealthAuthenticator.java            # EHEALTH AUTHENTICATE CREATE/VALIDATE/ADD Phase1/2
    │       ├── SicctKeyStoreSpi.java                # JCE KeyStoreSPI for SMC-B and eHBA (APDU generation)
    │       ├── jpa/
    │       │   └── CardTerminal.java                # @Entity — persisted terminal registration
    │       └── jmx/
    │           ├── BackupRestoreManagementMBean.java # JMX MBean interface (FR-228)
    │           └── BackupRestoreManagement.java      # exportBackup() / importBackup() / getBackupStatus(); delegates to TPM+JPA backup logic
    └── test/java/de/servicehealtherx/sicct/
        ├── EhealthAuthenticatorTest.java
        ├── SicctKeyStoreSpiTest.java
        ├── asn1/SicctAsn1RoundTripTest.java         # encode→decode round-trip per SC-015
        └── jmx/
            └── BackupRestoreManagementTest.java      # registers MBean on test MBeanServer; mocks JPA + TPM (FR-234)

quarkus-sicct-extension/
├── pom.xml                                          # parent; depends on sicct-lib
├── deployment/
│   ├── pom.xml
│   └── src/main/java/de/servicehealtherx/quarkus/sicct/deployment/
│       └── SicctExtensionProcessor.java             # @BuildStep CDI registration
└── runtime/
    ├── pom.xml
    └── src/
        ├── main/java/de/servicehealtherx/quarkus/sicct/runtime/
        │   ├── SicctTerminalManager.java            # @ApplicationScoped; Netty NioDatagramChannel UDP discovery + Netty TCP pool (shared NioEventLoopGroup)
        │   ├── SicctDiscoveryHandler.java           # SimpleChannelInboundHandler<DatagramPacket>; parses SICCT UDP announcements → CardTerminal JPA
        │   ├── SicctTerminalConnection.java         # per-terminal Netty channel + state machine
        │   ├── CardSlotTracker.java                 # @Scheduled watchdog
        │   ├── SicctHealthCheck.java               # @Readiness per terminal
        │   └── jmx/
        │       ├── SicctTerminalDiscoveryManagementMBean.java   # JMX MBean interface (FR-222)
        │       ├── SicctTerminalDiscoveryManagement.java        # triggerDiscovery() / getLastDiscoveryResult(); delegates to SicctTerminalManager
        │       ├── SicctTerminalConnectionManagementMBean.java  # JMX MBean interface (FR-224)
        │       ├── SicctTerminalConnectionManagement.java       # connect() / disconnect() / getTerminalStatus() / listAllTerminals()
        │       ├── CardPinManagementMBean.java                  # JMX MBean interface (FR-225)
        │       └── CardPinManagement.java                       # verifyPin() / getPinStatus(); routes SICCT VERIFY PIN APDU via SicctTerminalConnection
        └── test/java/de/servicehealtherx/quarkus/sicct/runtime/
            ├── SicctTerminalManagerTest.java        # EmbeddedChannel mock for UDP announcements + TCP server
            ├── CardSlotTrackerTest.java
            └── jmx/
                ├── SicctTerminalDiscoveryManagementTest.java    # registers MBean on test MBeanServer; mocks SicctTerminalManager (FR-234)
                ├── SicctTerminalConnectionManagementTest.java
                └── CardPinManagementTest.java

crypto-services-lib/
├── pom.xml                                          # depends on crypto-lib ONLY (no sicct-lib)
└── src/
    ├── main/java/de/servicehealtherx/crypto/services/
    │   ├── SignatureService.java                    # @ApplicationScoped (sign, ExternalAuthenticate)
    │   ├── EncryptionService.java                   # @ApplicationScoped (encrypt, decrypt)
    │   ├── CertificateService.java                 # @ApplicationScoped (read, verify)
    │   └── jmx/
    │       ├── SignatureServiceManagementMBean.java     # JMX MBean interface (FR-229)
    │       ├── SignatureServiceManagement.java          # sign() / verify() / externalAuthenticate() / getSignatureMode(); Base64 I/O
    │       ├── EncryptionServiceManagementMBean.java    # JMX MBean interface (FR-230)
    │       ├── EncryptionServiceManagement.java         # encryptDocument() / decryptDocument(); Base64 I/O
    │       ├── CertificateServiceManagementMBean.java   # JMX MBean interface (FR-231)
    │       └── CertificateServiceManagement.java        # readCertificate() / verifyCertificate(); Base64 I/O
    └── test/java/de/servicehealtherx/crypto/services/
        ├── SignatureServiceTest.java
        ├── EncryptionServiceTest.java
        ├── CertificateServiceTest.java
        └── jmx/
            ├── SignatureServiceManagementTest.java      # registers MBean on test MBeanServer; mocks CryptoProvider (FR-234)
            ├── EncryptionServiceManagementTest.java
            └── CertificateServiceManagementTest.java

quarkus-ldap-proxy-server-extension/
├── pom.xml                                          # depends on Quarkus + Netty
├── deployment/
│   ├── pom.xml
│   └── src/main/java/de/servicehealtherx/quarkus/ldap/proxy/server/deployment/
│       └── LdapProxyExtensionProcessor.java
└── runtime/
    ├── pom.xml
    └── src/
        ├── main/java/de/servicehealtherx/quarkus/ldap/proxy/server/runtime/
        │   ├── LdapServerCodec.java                     # Netty LDAPv3 server pipeline (port 389/636)
        │   ├── OperationFilter.java                     # allowlist: Bind, Unbind, Search, Abandon only
        │   ├── VzdLdapClient.java                       # LDAPS client → TI VZD
        │   └── jmx/
        │       ├── DnsServiceDiscoveryManagementMBean.java  # JMX MBean interface (FR-221)
        │       └── DnsServiceDiscoveryManagement.java        # triggerServiceDiscovery() / getDiscoveredServices(); executes PTR/SRV DNS-SD queries
        └── test/java/de/servicehealtherx/quarkus/ldap/proxy/server/runtime/
            └── jmx/
                └── DnsServiceDiscoveryManagementTest.java    # registers MBean on test MBeanServer; mocks DNS stub resolver (FR-234)

consumer-soap-server/
├── pom.xml                                          # depends on crypto-services-lib; CXF from consumer/ WSDLs
└── src/
    ├── main/java/de/servicehealtherx/consumer/soap/
    │   ├── ConsumerCertificateService.java          # CXF @WebService impl (consumer/CertificateService.wsdl)
    │   ├── ConsumerEncryptionService.java           # CXF @WebService impl (consumer/EncryptionService.wsdl)
    │   └── ConsumerSignatureService.java            # CXF @WebService impl (consumer/SignatureService.wsdl)
    └── test/java/de/servicehealtherx/consumer/soap/
        ├── ConsumerCertificateServiceTest.java
        ├── ConsumerEncryptionServiceTest.java
        └── ConsumerSignatureServiceTest.java

konnektor-soap-server/
├── pom.xml                                          # depends on crypto-services-lib + quarkus-sicct-extension; CXF from conn/ WSDLs
└── src/
    ├── main/java/de/servicehealtherx/konnektor/soap/
    │   ├── KonnektorAuthSignatureService.java       # CXF @WebService (conn/AuthSignatureService_v7_4_1.wsdl)
    │   ├── KonnektorCardService.java                # CXF @WebService (conn/CardService_v8_2_1.wsdl)
    │   ├── KonnektorCardTerminalService.java        # CXF @WebService (conn/CardTerminalService_v1_1_0.wsdl)
    │   ├── KonnektorCertificateService.java         # CXF @WebService (conn/CertificateService_v6_0_3.wsdl)
    │   ├── KonnektorEncryptionService.java          # CXF @WebService (conn/EncryptionService_v6_1_1.wsdl)
    │   ├── KonnektorEventService.java               # CXF @WebService (conn/EventService_v7_2_0.wsdl) — deferred ops return error 7200
    │   ├── KonnektorSignatureService.java           # CXF @WebService (conn/SignatureService_V7_5_7.wsdl)
    │   ├── ConnectorSdsResource.java                # serves connector.sds (generated from ServiceDirectory.xsd)
    │   └── AufrufKontextInterceptor.java
    └── test/java/de/servicehealtherx/konnektor/soap/
        ├── KonnektorSignatureServiceTest.java
        ├── KonnektorCardServiceTest.java
        └── ConnectorSdsResourceTest.java
```

**Structure Decision**: 9 Maven modules. Two git submodules (`api-telematik`, `openkim-server`) are checked out at the repository root. The `crypto-lib` / `sicct-lib` / `crypto-services-lib` chain creates independently testable library layers without Quarkus bootstrap. The two Quarkus extension modules (`quarkus-sicct-extension`, `quarkus-ldap-proxy-server-extension`) follow the standard Quarkus deployment+runtime split. The two SOAP server modules are thin CXF servers that delegate all business logic to the library layer. JPA entities live in `sicct-lib` so the terminal registry is accessible from both the extension (runtime) and any management tooling without coupling SOAP server modules to persistence.
