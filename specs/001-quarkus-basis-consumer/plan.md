# Implementation Plan: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Branch**: `001-quarkus-basis-consumer` | **Date**: 2026-06-05 | **Spec**: [spec.md](spec.md)

## Summary

A multi-tenant, cloud-native TI platform product providing encryption, signature, certificate, LDAP-proxy, KOM-LE, SICCT card terminal, and Konnektor SOAP compatibility services. Each tenant runs in an isolated Kubernetes namespace. Cryptographic operations are routed through a unified JCE provider abstraction (`CryptoProvider` CDI interface) supporting four simultaneous key source types: P12, PKCS#11 HSM, PC/SC, and SICCT. The SICCT integration is a proper Quarkus extension. KOM-LE is fulfilled by the openkim git submodule. SOAP services are implemented with Apache CXF. Management console uses Hawtio.

## Technical Context

**Language/Version**: Java 21 LTS

**Primary Dependencies**:

| Dependency | Purpose |
|-----------|---------|
| Quarkus 3.x | Application framework (CDI, Health, Config, REST, Scheduler) |
| quarkus-cxf | Apache CXF integration — all SOAP services (EncryptionService, SignatureService, CertificateService, Konnektor compat) |
| Netty 4.x | SICCT TCP/IP client connections (managed by SICCT Quarkus extension) |
| SunPKCS11 (JDK built-in) | PKCS#11 HSM integration; configured via `Security.getProvider("SunPKCS11").configure(cfg)` |
| javax.smartcardio (JDK built-in) | PC/SC card reader integration; monitoring via `CardTerminals.waitForChange()` |
| Bouncy Castle 1.7x (BC FIPS variant for production) | P12 parsing, ECC/RSA JCA operations; FIPS-certified variant used in production HSM configurations |
| openkim (git submodule) | KOM-LE / KIM client module (SMTP/POP3 interfaces, S/MIME encrypt/sign) |
| JPA + JavaDB (Apache Derby) | Tenant configuration persistence, audit log |
| Hawtio | Operator management web console (embedded Quarkus) |
| SoftHSM2 | CI-only PKCS#11 emulator (installed on CI runner; not bundled) |
| JUnit 5 + Quarkus Test | Unit and integration testing |
| Testcontainers | Container-isolated integration tests (DB, VPN stub) |

**Storage**: JPA + JavaDB (Apache Derby) for: tenant configuration, CardHandle registry, audit logs, SICCT terminal registry. Crypto key material is NEVER stored in the database; it resides exclusively in HSM / card / P12 file. Runtime state (key source availability, SICCT connection state, PC/SC slot state) is held in `@ApplicationScoped` CDI beans.

**Testing**:
- Unit: JUnit 5; direct bean construction with mocked dependencies
- Integration: `@QuarkusTest`; SoftHSM2 via `QuarkusTestResourceLifecycleManager` for PKCS#11; Netty `EmbeddedChannel`-based mock for SICCT; `jnasmartcardio` virtual reader for PC/SC; embedded Derby for DB; WireMock/CXF test server for SOAP
- CI: All hardware-dependent tests use software emulators; no physical HSM, card terminal, or USB reader required in CI
- Afo traceability: one named test per Afo ID (`test_A_XXXXX_...`); one commit per Afo

**Target Platform**: Linux server, JVM mode; Kubernetes pod. Quarkus native mode is explicitly excluded from v1 scope (SunPKCS11 and javax.smartcardio are not native-image compatible without significant GraalVM configuration).

**Project Type**: Multi-module Maven application:
1. `sicct-quarkus-extension` — proper Quarkus extension (deployment + runtime) for SICCT TCP/IP client
2. `crypto-provider` — JCE routing layer + key source adapters (P12, PKCS#11, PC/SC, SICCT delegate)
3. `app` — main Quarkus application (all SOAP services, LDAP proxy, KOM-LE via openkim, Hawtio, JPA)

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
- Interactive PIN entry out of scope; all secrets via config/secrets manager

**Scale/Scope**: 50 concurrent tenants; 1–10 key sources per tenant; up to 200 concurrent SOAP requests under load.

## Constitution Check

| Gate | Status | Notes |
|------|--------|-------|
| Code Quality: single-responsibility modules | ✅ PASS | 3 Maven modules with clear ownership; each key source adapter is an isolated class |
| Testing: 80% coverage floor | ✅ PASS | All adapters have unit tests; hardware deps replaced by emulators in CI |
| Testing: test-first mandate | ✅ PASS | CDI contracts defined before implementation; SOAP contracts from published WSDLs |
| Testing: no hardware-dependent CI tests | ✅ PASS | SoftHSM2, EmbeddedChannel, virtual PC/SC, embedded Derby |
| Security: key material never extracted | ✅ PASS | PKCS#11/PC/SC/SICCT: on-device; P12: JVM-local Signature object, never serialized |
| Security: production HSM FIPS 140-2 L3 | ✅ PASS | Enforced by Assumption; SoftHSM2 CI-only |
| Security: SAST/SCA in CI | ✅ PASS | Existing pipeline; new deps (Bouncy Castle, Netty, openkim submodule) added to SBOM |
| Security: OWASP Top 10 | ✅ PASS | No user input parsing in crypto path; config values typed/validated at startup |
| Performance: 150 MB memory ceiling | ✅ PASS | ~1 MB Netty overhead for 10 SICCT connections; well within budget |
| Performance: 2s SOAP operation budget | ✅ PASS | System path <10 ms; budget dominated by HSM/card hardware latency |
| Dependency hygiene: new deps justified | ✅ PASS | Each dependency in Technical Context has a stated capability gap |
| Afo traceability | ✅ PASS | Per-Afo commits and named tests mandatory; traceability report in CI |
| UX: Hawtio console | ✅ PASS | Operator console uses Hawtio (existing, design-system-compliant within its own UI) |
| Accessibility | ✅ N/A | No patient-facing UI; Hawtio is operator-only tooling |

**Complexity justification** (multi-module):

| Extra module | Why needed | Simpler alternative rejected because |
|-------------|-----------|--------------------------------------|
| `sicct-quarkus-extension` (deployment + runtime) | User-mandated Quarkus extension; build-time config validation via `SicctProcessor` | Single module prevents build-time validation and forces full Quarkus bootstrap in unit tests of SICCT adapters |
| `crypto-provider` | Independent testability of JCE adapters without Quarkus bootstrap | Merging into `app` couples unit tests to the full application context |

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
pom.xml                                           # parent Maven POM (manages versions)

sicct-quarkus-extension/                          # Quarkus extension (SICCT TCP/IP client)
├── pom.xml                                       # parent
├── deployment/
│   ├── pom.xml
│   └── src/main/java/health/basis/consumer/sicct/deployment/
│       ├── SicctProcessor.java                   # @BuildStep config validation
│       └── SicctBuildTimeConfig.java
└── runtime/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/health/basis/consumer/sicct/runtime/
        │   │   ├── SicctTerminalManager.java     # @Startup @ApplicationScoped
        │   │   ├── SicctTerminalConnection.java  # Single connection state machine (Netty)
        │   │   ├── SicctCardSlotTracker.java     # Card insertion/removal tracking
        │   │   ├── SicctKeySource.java           # Registers card keys as CryptoProvider aliases
        │   │   ├── SicctHealthCheck.java          # @Readiness per terminal
        │   │   └── config/SicctConfig.java        # @ConfigMapping interface
        │   └── resources/META-INF/quarkus-extension.properties
        └── test/java/health/basis/consumer/sicct/
            ├── SicctTerminalConnectionTest.java   # Netty EmbeddedChannel
            ├── SicctTerminalManagerTest.java      # Mock TCP server
            └── SicctCardSlotTrackerTest.java

crypto-provider/                                  # JCE routing layer + adapters
├── pom.xml
└── src/
    ├── main/java/health/basis/consumer/crypto/
    │   ├── CryptoProvider.java                   # CDI interface (sign, decrypt, listAliases, isAvailable)
    │   ├── CryptoOperationRequest.java           # Value record
    │   ├── KeyAlias.java                         # Value type with namespace validation
    │   ├── KeySourceAvailability.java            # Enum: AVAILABLE, UNAVAILABLE, ERROR
    │   ├── RoutingCryptoProvider.java            # @ApplicationScoped; routes by alias prefix
    │   ├── provider/
    │   │   ├── P12CryptoProvider.java            # KeyStore.getInstance("PKCS12")
    │   │   ├── Pkcs11CryptoProvider.java         # SunPKCS11 provider
    │   │   ├── PcscCryptoProvider.java           # javax.smartcardio
    │   │   └── SicctCryptoProvider.java          # delegates to sicct-quarkus-extension
    │   └── health/CryptoProviderHealthCheck.java # @Readiness
    └── test/java/health/basis/consumer/crypto/
        ├── RoutingCryptoProviderTest.java
        ├── provider/
        │   ├── P12CryptoProviderTest.java
        │   ├── Pkcs11CryptoProviderTest.java     # SoftHSM2 via QuarkusTestResourceLifecycleManager
        │   ├── PcscCryptoProviderTest.java       # jnasmartcardio virtual reader
        │   └── SicctCryptoProviderTest.java
        └── CryptoProviderIntegrationTest.java   # All four active simultaneously

app/                                              # Main Quarkus application
├── pom.xml                                       # depends on crypto-provider + sicct-quarkus-extension
├── openkim/                                      # git submodule → organizational fork of openkim
└── src/
    ├── main/
    │   ├── java/health/basis/consumer/
    │   │   ├── tenant/
    │   │   │   ├── TenantRegistry.java           # @ApplicationScoped tenant management
    │   │   │   └── TenantNamespaceIsolator.java  # CDI interceptor — enforces tenant scope
    │   │   ├── soap/
    │   │   │   ├── EncryptionServiceImpl.java    # CXF @WebService
    │   │   │   ├── SignatureServiceImpl.java
    │   │   │   ├── CertificateServiceImpl.java
    │   │   │   └── KonnektorCompatService.java   # gemSpec_Kon_V5.27.0 compatibility facade
    │   │   ├── ldap/
    │   │   │   └── LdapProxyServer.java          # LDAPv3 proxy (Bind/Unbind/Search/Abandon)
    │   │   ├── komle/
    │   │   │   └── KomLeAdapter.java             # Bridges openkim submodule to CDI
    │   │   ├── cardhandle/
    │   │   │   └── CardHandleRegistry.java       # Maps CardHandle → KeyAlias, per tenant
    │   │   └── audit/
    │   │       └── AuditLogService.java          # Append-only structured audit log
    │   └── resources/
    │       └── application.properties            # All config (SICCT, crypto, LDAP, SOAP, Hawtio)
    └── test/java/health/basis/consumer/
        ├── soap/
        │   ├── EncryptionServiceSoapTest.java
        │   ├── SignatureServiceSoapTest.java
        │   ├── CertificateServiceSoapTest.java
        │   └── KonnektorCompatSoapTest.java
        ├── ldap/LdapProxyTest.java
        ├── komle/KomLeIntegrationTest.java
        └── tenant/TenantIsolationTest.java
```

**Structure Decision**: Three Maven modules. The SICCT component is a proper Quarkus extension (deployment + runtime) per user mandate. The `crypto-provider` module enables independent unit testing of JCE adapters without full Quarkus bootstrap. The `app` module consumes both and hosts all SOAP, LDAP, KOM-LE, and tenant management logic. The openkim fork is a git submodule inside `app/openkim/`.
