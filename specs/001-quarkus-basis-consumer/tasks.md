# Tasks: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Feature**: `001-quarkus-basis-consumer` | **Date**: 2026-06-06
**Input**: `specs/001-quarkus-basis-consumer/` — plan.md, spec.md, data-model.md, contracts/, research.md, quickstart.md

**Note**: User Story 1 (Tenant Isolation) is NOT implemented in this project — it is handled by the Kubernetes provider project (per spec.md). All other user stories are represented below.

---

## Format: `[ID] [P?] [Story?] Description with file path`

- **[P]**: Can run in parallel (different files, no blocking dependencies)
- **[Story]**: Maps to user story from spec.md (US2–US13)
- **No [Story]**: Setup or Foundational phase tasks

---

## Phase 1: Setup (Project Initialization)

**Purpose**: Initialize the 9-module Maven project structure so all modules can build independently.

- [X] T001 Create parent pom.xml at repo root declaring all 9 modules (api-telematik, openkim-server, crypto-lib, sicct-lib, quarkus-sicct-extension, crypto-services-lib, quarkus-ldap-proxy-server-extension, consumer-soap-server, konnektor-soap-server) with Java 21 and Quarkus 3.x BOM in pom.xml
- [X] T002 Initialize api-telematik git submodule from med-united/api-telematik@ebk_6.0.3 in api-telematik/
- [X] T003 [P] Initialize openkim-server git submodule from sberg-net/openkim in openkim-server/
- [X] T004 [P] Create crypto-lib/pom.xml with dependencies: de.gematik.pki:gemLibPki:4.0.2, Bouncy Castle (BC FIPS variant), JUnit 5, Quarkus CDI, SmallRye Health
- [X] T005 [P] Create sicct-lib/pom.xml with dependencies: crypto-lib, beanit jASN1 (compile + maven-plugin), Jakarta Persistence, JUnit 5
- [X] T006 [P] Create quarkus-sicct-extension/pom.xml (parent) + quarkus-sicct-extension/deployment/pom.xml + quarkus-sicct-extension/runtime/pom.xml with dependencies: sicct-lib, Quarkus extension BOM, Netty 4.x
- [X] T007 [P] Create crypto-services-lib/pom.xml with dependency on crypto-lib ONLY (add Maven Enforcer rule confirming NO transitive dependency on sicct-lib per FR-022), JUnit 5, Quarkus CDI
- [X] T008 [P] Create quarkus-ldap-proxy-server-extension/pom.xml (parent) + deployment/pom.xml + runtime/pom.xml with dependencies: Quarkus extension BOM, Netty 4.x
- [X] T009 [P] Create consumer-soap-server/pom.xml with dependencies: crypto-services-lib, quarkus-cxf, CXF wsdl2java plugin generating stubs from api-telematik/consumer/ WSDLs, Hawtio, SmallRye Health, configsource-db, JPA + Derby
- [X] T010 [P] Create konnektor-soap-server/pom.xml with dependencies: crypto-services-lib, quarkus-sicct-extension, quarkus-cxf, CXF wsdl2java plugin generating stubs from api-telematik/conn/ WSDLs, JPA + Derby
- [X] T011 [P] Add Testcontainers (DB integration) and SoftHSM2 Maven CI profile (pkcs11-ci) to crypto-lib/pom.xml; add test dependencies for jnasmartcardio virtual PC/SC reader and Netty EmbeddedChannel across test scopes
- [X] T012 [P] Create .github/workflows/ci.yml: mvn verify with pkcs11-ci profile on linux runner; SoftHSM2 installed via apt; add .mvn/wrapper/maven-wrapper.properties

**Checkpoint**: All 9 module POMs created and `mvn validate` passes from repo root.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T013 Create AppConfigProperty JPA entity in sicct-lib/src/main/java/de/servicehealtherx/sicct/jpa/AppConfigProperty.java (PROPNAME PK, PROPVALUE NOT NULL per FR-028); configure configsource-db MicroProfile ConfigSource dependency in consumer-soap-server/pom.xml
- [X] T014 Create CardTerminal JPA entity in sicct-lib/src/main/java/de/servicehealtherx/sicct/jpa/CardTerminal.java with all fields from data-model.md (terminalId UNIQUE, host, port, pairingStatus, sealedSharedSecret, backupEncryptedSharedSecret, connectTimeoutMs, apduTimeoutMs, maxRetries, initialBackoffMs, maxBackoffMs per FR-096)
- [X] T015 [P] Configure Jakarta Persistence: create META-INF/persistence.xml in sicct-lib/src/main/resources/ for Apache Derby; set Quarkus datasource properties in consumer-soap-server/src/main/resources/application.properties
- [X] T016 Create CryptoProvider CDI interface in crypto-lib/src/main/java/de/servicehealtherx/crypto/CryptoProvider.java declaring sign(alias, params, data), verify(alias, params, data, signature), encrypt(alias, params, plaintext), decrypt(alias, params, ciphertext), listKeyStores(), getAvailability(alias), getAvailabilities() per FR-010
- [X] T017 [P] Create KeyAlias value type in crypto-lib/src/main/java/de/servicehealtherx/crypto/KeyAlias.java (pattern `^(p12|pkcs11|pcsc|sicct)/[a-z0-9\-_]+$`; max 128 chars; uniqueness contract per FR-013)
- [X] T018 [P] Create SourceType enum (P12, PKCS11, PCSC, SICCT) in crypto-lib/src/main/java/de/servicehealtherx/crypto/SourceType.java; create KeyStoreAvailability enum (AVAILABLE, UNAVAILABLE, ERROR) in crypto-lib/src/main/java/de/servicehealtherx/crypto/KeyStoreAvailability.java
- [X] T019 [P] Create KeyStoreDescriptor runtime model in crypto-lib/src/main/java/de/servicehealtherx/crypto/KeyStoreDescriptor.java (alias, sourceType, availability, errorMessage, lastUpdated — state machine per data-model.md)
- [X] T020 [P] Create CryptoOperationRequest and CryptoOperationResult in crypto-lib/src/main/java/de/servicehealtherx/crypto/model/ (all fields from data-model.md; data as byte[]; callerIdentity for audit)
- [X] T021 Create base KeyStoreAdapter abstract class extending java.security.KeyStoreSpi in crypto-lib/src/main/java/de/servicehealtherx/crypto/KeyStoreAdapter.java (Principle VIII — JCA standard interface; throw UnsupportedOperationException for every KeyStoreSpi method not required by this system)
- [X] T022 [P] Create TslDownloader @ApplicationScoped CDI bean in crypto-lib/src/main/java/de/servicehealtherx/crypto/TslDownloader.java (downloads + caches TI TSL via gemLibPki; URL from AppConfigProperty; used by TrustService for cert validation per FR-223)
- [X] T023 [P] Create CertificateParser @ApplicationScoped CDI bean in crypto-lib/src/main/java/de/servicehealtherx/crypto/CertificateParser.java (extracts RegNr, card type SMC-B/eHBA, EKU from X.509 via gemLibPki)
- [X] T024 [P] Create TrustService @ApplicationScoped CDI bean in crypto-lib/src/main/java/de/servicehealtherx/crypto/TrustService.java (PL_TUC_PKI_VERIFY_CERTIFICATE via gemLibPki; TOLERATE_OCSP_FAILURE parameter; error flags per FR-217; FR-053)
- [X] T025 Create AuditLogger @ApplicationScoped CDI bean in crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/AuditLogger.java (structured audit log per FR-016; alias + operationType + algorithm + callerIdentity + success + duration; private key material MUST NOT appear in any field)
- [X] T026 [P] Create CryptoProviderHealthCheck implementing org.eclipse.microprofile.health.HealthCheck in crypto-lib/src/main/java/de/servicehealtherx/crypto/CryptoProviderHealthCheck.java (Principle VIII — MicroProfile HealthCheck standard interface; reports totalAliases, availableAliases, unavailable list per FR-014)
- [X] T027 [P] Create SoapFaultBuilder utility in consumer-soap-server/src/main/java/de/servicehealtherx/consumer/soap/SoapFaultBuilder.java (builds gematik SOAP-Fault with Status/Result=Fatal|Error + Trace chain; fallback code 4001 per FR-153, FR-200)

**Checkpoint**: Foundation ready — all interfaces, base classes, and core entities exist. User story implementation can now begin.

---

## Phase 3: User Story 2 — Pluggable Cryptographic Key Source (Priority: P1) 🎯 MVP

**Goal**: All four key source types (P12, PKCS#11, PC/SC, SICCT) serve signing/decryption requests concurrently via unified CryptoProvider.

**Independent Test**: Configure P12 + SoftHSM2 PKCS#11 + virtual PC/SC + mock SICCT simultaneously; submit one signing request per source in parallel; all four complete with distinct verifiable signatures (quickstart Scenario 4).

- [X] T028 [US2] Implement P12KeyStoreAdapter extends KeyStoreAdapter in crypto-lib/src/main/java/de/servicehealtherx/crypto/adapter/P12KeyStoreAdapter.java (PKCS#12 parsing via Bouncy Castle; JVM-local key; keystorePassword + entryPassword independently configurable; passwords MUST NOT appear in logs per FR-017)
- [X] T029 [P] [US2] Implement Pkcs11KeyStoreAdapter extends KeyStoreAdapter in crypto-lib/src/main/java/de/servicehealtherx/crypto/adapter/Pkcs11KeyStoreAdapter.java (SunPKCS11 provider via Security.getProvider("SunPKCS11").configure(); non-extractable keys; CKR_TOKEN_NOT_PRESENT + CKR_SESSION_CLOSED → UNAVAILABLE per FR-018, FR-019)
- [X] T030 [P] [US2] Implement PcscKeyStoreAdapter extends KeyStoreAdapter in crypto-lib/src/main/java/de/servicehealtherx/crypto/adapter/PcscKeyStoreAdapter.java (javax.smartcardio API; monitors card insertion/removal via CardTerminals.waitForChange(); FEATURE_VERIFY_PIN_DIRECT for class-2/3 readers; static PIN fallback for class-1 readers only per FR-020, FR-021)
- [X] T031 [US2] Create SicctKeyStoreAdapter extends KeyStoreAdapter stub in crypto-lib/src/main/java/de/servicehealtherx/crypto/adapter/SicctKeyStoreAdapter.java (delegates APDU operations to SicctTerminalManager via CDI event; reports UNAVAILABLE until SICCT extension registers a live terminal — full implementation in US8 Phase 9)
- [X] T032 [US2] Implement CryptoProviderRouter @ApplicationScoped CDI bean in crypto-lib/src/main/java/de/servicehealtherx/crypto/CryptoProviderRouter.java (implements CryptoProvider; routes by KeyAlias prefix to correct KeyStoreAdapter; detects duplicate aliases at startup and fails with descriptive error per FR-011, FR-013)
- [X] T033 [P] [US2] Create P12KeyStoreConfig MicroProfile Config mapping in crypto-lib/src/main/java/de/servicehealtherx/crypto/config/P12KeyStoreConfig.java (quarkus.crypto.p12[*]: alias, path, entry-alias, keystore-password, entry-password per data-model.md FR-017)
- [X] T034 [P] [US2] Create Pkcs11KeyStoreConfig and PcscKeyStoreConfig in crypto-lib/src/main/java/de/servicehealtherx/crypto/config/ (quarkus.crypto.pkcs11[*] and quarkus.crypto.pcsc[*] from data-model.md)
- [X] T035 [US2] Wire CryptoProviderHealthCheck to CryptoProviderRouter.getAvailabilities(): reports per-alias availability in /q/health/ready response per FR-014; wire SicctHealthCheck (to be added in US8) into overall readiness
- [X] T036 [US2] Implement P12 hot-reload: detect config removal within 60 seconds via MicroProfile Config change event; mark alias UNAVAILABLE without restart; in-flight operations fail with clear error per FR-015
- [X] T037 [P] [US2] Implement PC/SC alias auto-update in PcscKeyStoreAdapter: alias registered within 10 seconds of card insertion event; alias removed within 10 seconds of card removal per FR-020

**Checkpoint**: US2 complete — CryptoProvider routes all four key source types; quickstart Scenarios 1–5 pass.

---

## Phase 4: User Story 3 — Hybrid Document Encryption & Decryption (Priority: P1)

**Goal**: EncryptDocument and DecryptDocument via consumer SOAP EncryptionService.

**Independent Test**: Encrypt test XML with known recipient cert → DecryptDocument with corresponding CardHandle → output byte-identical to input (quickstart Scenario 4 extended).

- [X] T038 [US3] Implement EncryptionService @ApplicationScoped CDI bean in crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/EncryptionService.java (PL_TUC_HYBRID_ENCIPHER: XMLEnc for XML docs, CMS/RFC 5652 for binary; one encrypted key block per recipient cert; delegates private key ops to CryptoProvider; emits audit log per FR-016; FR-031)
- [X] T039 [US3] Implement recipient cert validation in EncryptionService.encryptDocument: call TrustService.verify() per PL_TUC_PKI_VERIFY_CERTIFICATE before encrypting; reject expired or revoked certs with cert subject + reason in error per FR-032, FR-209
- [X] T040 [P] [US3] Implement ECC-preferred cert selection in EncryptionService: when recipient has both ECC and RSA certs and ECC-preferred mode is active, use ECC exclusively; RSA MUST NOT be selected per FR-033, TIP1-A_6984-03
- [X] T041 [US3] Implement EncryptionService.decryptDocument: PL_TUC_HYBRID_DECIPHER; determine algorithm from hybrid key structure in ciphertext; delegate private key unwrap to CryptoProvider; key never leaves key source boundary per FR-034
- [X] T042 [US3] Implement ConsumerEncryptionService CXF @WebService in consumer-soap-server/src/main/java/de/servicehealtherx/consumer/soap/ConsumerEncryptionService.java (EncryptDocument + DecryptDocument per consumer/EncryptionService.wsdl v3.0.1; validate responses against consumer/EncryptionService.xsd + ConsumerCommon.xsd per FR-030)
- [X] T043 [P] [US3] Map error codes in ConsumerEncryptionService per FR-205, FR-209: expired recipient cert → Trace with subject + reason; private key unavailable → 4001; TUC error codes → SOAP fault codes per FR-205 table

**Checkpoint**: US3 complete — EncryptDocument + DecryptDocument round-trip verified; consumer SOAP endpoint schema-valid.

---

## Phase 5: User Story 4 — Document Signing — nonQES and QES (Priority: P1)

**Goal**: SignDocument (CAdES/PAdES/XAdES), SignPlain, VerifyDocument, ExternalAuthenticate via consumer SOAP SignatureService.

**Independent Test**: SignDocument CAdES; VerifyDocument without modification → VALID; alter one byte → INVALID; repeat for PAdES and XAdES (quickstart independent test US4).

- [X] T044 [US4] Implement SignatureService @ApplicationScoped CDI bean in crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/SignatureService.java (PL_TUC_SIGN_DOCUMENT_nonQES + PL_TUC_SIGN_HASH_nonQES; delegates signing to CryptoProvider; emits audit log per FR-016)
- [X] T045 [US4] Implement CAdES signature creation in SignatureService: CMS-based, RFC 5652, CAdES-BES baseline; nonQES using PrK.HCI.OSIG; QES using PrK.HP.QES with PIN confirmation; ECC-preferred: use PrK.HCI.OSIG.E256 exclusively per FR-041, FR-042
- [X] T046 [P] [US4] Implement PAdES signature creation in SignatureService: PDF-embedded, ETSI EN 319 102-1, PAdES-B-B baseline; both nonQES and QES per FR-047
- [X] T047 [P] [US4] Implement XAdES signature creation in SignatureService: XML-embedded, ETSI EN 319 132-1, XAdES-B-B baseline; enveloped and detached modes; both nonQES and QES per FR-048
- [X] T048 [US4] Implement SignatureService.verifyDocument: PL_TUC_VERIFY_DOCUMENT_nonQES; CoreValidation + cert check via TrustService; return HighLevelResult VALID/INVALID/INCONCLUSIVE + assumed timestamp type per FR-044, FR-204
- [X] T049 [P] [US4] Implement SignatureService.externalAuthenticate: sign hash ≤ 512 bits; algorithm from dss:SignatureType (ECDSA or PKCS#1); hash algorithm inferred from byte length (32→SHA-256, 48→SHA-384, 64→SHA-512); SM-B → PrK.HCI.AUT in DF.ESIGN only per FR-045, FR-152
- [X] T050 [P] [US4] Implement SignatureService.signPlain: ECDSA over binary payload per BSI-TR-03111 §4.2.1; only PrK.HCI.OSIG.E256 in ECC-preferred mode per FR-043, A_27048-01
- [X] T051 [US4] Implement ConsumerSignatureService CXF @WebService in consumer-soap-server/src/main/java/de/servicehealtherx/consumer/soap/ConsumerSignatureService.java (SignDocument, SignPlain, VerifyDocument, ExternalAuthenticate per consumer/SignatureService.wsdl v3.2.1; FR-040)
- [X] T052 [P] [US4] Configure MTOM interceptor in konnektor-soap-server CXF configuration only (TIP1-A_5694-03 per FR-151); consumer-soap-server endpoints MUST NOT configure MTOM
- [X] T053 [P] [US4] Map error codes in ConsumerSignatureService per FR-201: 4111 for unrecognized SignatureType URI; 4000 for parameter validation failure; TUC error codes → SOAP fault codes per FR-205 table

**Checkpoint**: US4 complete — SignDocument CAdES/PAdES/XAdES + VerifyDocument + ExternalAuthenticate all verified; consumer SOAP schema-valid.

---

## Phase 6: User Story 10 — SICCT ASN.1 Code Generation (Priority: P2)

**Goal**: All SICCT protocol message types (including EHEALTH AUTHENTICATE) encoded/decoded as type-safe Java objects via beanit jASN1.

**Independent Test**: CommandAPDU round-trip: encode header + SicctDataObject → byte array → decode → all fields identical (SC-015); SharedSecretDO != 16 bytes → typed exception before any bytes written (SC-016).

- [X] T054 [US10] Extend sicct-lib/src/main/asn1/SICCT.asn1 with TEIL E section: add ehealth-terminal-authenticate (INS=0xAA, decimal 170) to SicctInstruction ENUMERATED with normative reference comment (FR-160, FR-175)
- [X] T055 [P] [US10] Add EhealthAuthP2 ENUMERATED type (create=01, validate=02, add-phase-1=03, add-phase-2=04) in SICCT.asn1 TEIL E with FR-161 reference comment
- [X] T056 [P] [US10] Add SharedSecretDO OCTET STRING SIZE(16), SharedSecretChallengeDO OCTET STRING SIZE(MINSIZE 16), SharedSecretResponseDO OCTET STRING SIZE(32) in SICCT.asn1 TEIL E per FR-162–FR-164
- [X] T057 [P] [US10] Add EhealthAuthCreateRequest SEQUENCE (SharedSecretDO + APPL-DO), EhealthAuthValidateRequest SEQUENCE (SharedSecretChallengeDO), EhealthAuthAddPhase2Request SEQUENCE (SharedSecretResponseDO) in SICCT.asn1 TEIL E per FR-165–FR-167
- [X] T058 [P] [US10] Add EhealthAuthCreateResponse OCTET STRING SIZE(64|100), EhealthAuthAddPhase1Response OCTET STRING SIZE(MINSIZE 16) in SICCT.asn1 TEIL E per FR-168–FR-169
- [X] T059 [US10] Configure beanit jASN1 Maven plugin in sicct-lib/pom.xml: bind to generate-sources lifecycle; input sicct-lib/src/main/asn1/SICCT.asn1; output package de.servicehealtherx.sicct.asn1; add target/generated-sources to .gitignore per FR-173, FR-174
- [X] T060 [US10] Write SicctAsn1RoundTripTest in sicct-lib/src/test/java/de/servicehealtherx/sicct/asn1/SicctAsn1RoundTripTest.java: encode→decode round-trip for every generated class with representative valid values; verify TEIL E types present; verify build zero parser errors (SC-014, SC-015, SC-018)
- [X] T061 [P] [US10] Write size-constraint violation tests in SicctAsn1RoundTripTest: SharedSecretDO with 15/17 bytes → typed exception before bytes written; SharedSecretResponseDO != 32 bytes → exception; verify 100% of constraint cases per SC-016, FR-171
- [X] T062 [P] [US10] Write malformed BER decode tests in SicctAsn1RoundTripTest: truncated byte array at TLV boundary → typed exception; null mandatory field → NullPointerException with field name; unknown tag → graceful failure per US10 acceptance scenario 4

**Checkpoint**: US10 complete — all SICCT.asn1 types generate Java classes; round-trip tests pass; constraint violations throw typed exceptions.

---

## Phase 7: User Story 11 — EHEALTH TERMINAL AUTHENTICATE Commands (Priority: P2)

**Goal**: All four EHEALTH TERMINAL AUTHENTICATE command variants (CREATE, VALIDATE, ADD Phase 1/2) buildable and parseable using generated classes.

**Independent Test**: EhealthAuthCreateRequest with 16-byte SharedSecretDO + APPL-DO → BER encode → both DOs present with correct tags; CREATE with 15-byte shared secret → rejected during encoding (US11 acceptance scenario 2).

- [X] T063 [US11] Implement EhealthAuthenticator @ApplicationScoped CDI bean in sicct-lib/src/main/java/de/servicehealtherx/sicct/EhealthAuthenticator.java exposing buildCreateRequest(), buildValidateRequest(), buildAddPhase1Request(), buildAddPhase2Request(), parseCreateResponse(), parseAddPhase1Response() using generated ASN.1 classes per FR-130–FR-132
- [X] T064 [US11] Implement EhealthAuthenticator.buildCreateRequest(): generate 16 bytes cryptographic randomness (SecureRandom); wrap SharedSecretDO + APPL-DO in CommandAPDU (INS=ehealth-terminal-authenticate, P2=create); SharedSecretDO size=16 enforced by encoder before encoding per FR-130, FR-162, FR-165
- [X] T065 [P] [US11] Implement EhealthAuthenticator.buildValidateRequest(): generate random challenge ≥16 bytes; compute expected response as SHA-256(challenge ‖ ShS.KT.AUT); wrap in CommandAPDU (P2=validate) per FR-131
- [X] T066 [P] [US11] Implement EhealthAuthenticator.parseCreateResponse(): decode EhealthAuthCreateResponse from terminal response APDU bytes; enforce ECDSA=64 bytes or RSA=100 bytes; reject other sizes per FR-168, US11 acceptance scenario 3
- [X] T067 [P] [US11] Implement EhealthAuthenticator.parseAddPhase1Response(): decode challenge from EhealthAuthAddPhase1Response; verify ≥16 bytes per FR-169, US11 acceptance scenario 4
- [X] T068 [US11] Write EhealthAuthenticatorTest in sicct-lib/src/test/java/de/servicehealtherx/sicct/EhealthAuthenticatorTest.java: encode all four command variants → byte-level verification against reference encodings from gemSpec_KT_V3.17.0 §3.7.2 (SC-019); SharedSecretDO != 16 bytes → exception before any bytes written (SC-016)

**Checkpoint**: US11 complete — all four EHEALTH AUTHENTICATE variants encode/parse correctly; constraint violations verified.

---

## Phase 8: User Story 12 — Maven Build Integration (Priority: P3)

**Goal**: sicct-lib Maven generate-sources regenerates all ASN.1 classes automatically; build fails on syntax error.

**Independent Test**: Delete target/generated-sources/; run mvn generate-sources -pl sicct-lib; all classes regenerated in < 30 seconds; no manual step required (SC-017).

- [X] T069 [US12] Verify beanit jASN1 Maven plugin regenerates all classes from scratch when target/generated-sources/ is deleted (SC-017); add .gitignore entry ensuring target/generated-sources/ never committed; validate SICCT.asn1 syntax error causes build failure at generate-sources with file name + line number in error message (US12 acceptance scenario 2, FR-173)
- [X] T070 [P] [US12] Add Maven Enforcer rule to sicct-lib/pom.xml asserting generated source directory is not present in version control; document regeneration procedure in sicct-lib/README.md per US12 acceptance scenario 3

**Checkpoint**: US12 complete — Maven build is single-command; any schema change auto-regenerates; syntax errors caught at build time.

---

## Phase 9: User Story 8 — SICCT Card Terminal Management (Priority: P2)

**Goal**: SICCT terminals managed via Hawtio; automatic UDP discovery, TCP connection management, pairing, TPM sealing, card slot tracking, and reconnect with backoff.

**Independent Test**: Add terminal via management console → CONNECTED within 15 seconds; insert SMC-B → ReadCertificate returns matching cert; restart → all terminals reconnected within 60 seconds (SC-012, SC-013).

- [X] T071 [US8] Implement SicctTerminalManager @ApplicationScoped CDI bean in quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/SicctTerminalManager.java (loads all CardTerminal JPA records at startup; initiates TCP connections via shared NioEventLoopGroup; failure to connect one terminal MUST NOT prevent others per FR-023, FR-097)
- [X] T072 [US8] Implement Netty TCP client pool in SicctTerminalManager: one NioSocketChannel per terminal; exponential backoff reconnect (initialBackoffMs, maxBackoffMs from CardTerminal entity); disconnect detected ≤ 10 seconds; log at ALERT level at max backoff per FR-025
- [X] T073 [P] [US8] Implement SicctDiscoveryHandler extends SimpleChannelInboundHandler<DatagramPacket> in quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/SicctDiscoveryHandler.java (parses SICCT UDP announcements per SICCT §6.2.3; persists new CardTerminal JPA entity; initiates TCP connection per FR-091)
- [X] T074 [P] [US8] Implement UDP broadcast sender in SicctTerminalManager via NioDatagramChannel; trigger on demand via SicctTerminalDiscoveryManagement MBean (Phase 13) per FR-091, FR-222
- [X] T075 [US8] Implement SicctTerminalConnection state machine in quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/SicctTerminalConnection.java (CONNECTING/CONNECTED/RECONNECTING/DISCONNECTED/FAILED transitions; configurable apduTimeoutMs timeout per FR-027; connection lost → discard session keys → DISCONNECTED per FR-136)
- [X] T076 [P] [US8] Implement mutual TLS in SicctTerminalManager Netty pipeline: present SAK.AUT client certificate (OID 1.2.276.0.76.4.113, KeyUsage digitalSignature, ExtKeyUsage tlsClientAuthentication) per FR-133; enforce ECC+AES-GCM or RSA+AES-CBC cipher suites; DES MUST NOT be used per A_22450
- [X] T077 [US8] Implement SICCT TLS state machine (NoSicctTls/InvalidClient/ClientWithoutPairing/ClientWithPairing) in SicctTerminalConnection: enforce command restrictions per state per FR-134, A_22461, CMD_KT_0004, CMD_KT_0005
- [X] T078 [US8] Implement SICCT INIT CT SESSION and CLOSE CT SESSION in SicctTerminalConnection per FR-092: manage CT Admin/Control/CC roles; track CORRELATION state (bekannt→zugewiesen→gepairt→aktiv per TUC_KON_053/050); only AKTIV terminals accept card operations
- [X] T079 [US8] Implement EHEALTH AUTHENTICATE CREATE pairing in SicctTerminalManager per FR-130: generate ShS.KT.AUT via EhealthAuthenticator; send CREATE command; operator confirms on terminal display (10-minute timeout); verify terminal SM-KT signature in CREATE response; advance terminal to PAIRED + AKTIV state
- [X] T080 [P] [US8] Implement pairing label format `KT:<MAC> MIT KON:<hostname> PAIREN OK?` in EhealthAuthenticator per FR-138; operator decline or timeout → abort pairing → error 4041; duplicate-secret SICCT SW 6900 → generate new secret and retry per FR-135
- [X] T081 [US8] Implement TpmSealer in sicct-lib/src/main/java/de/servicehealtherx/sicct/TpmSealer.java using tss.java (Microsoft TSS for Java; no tpm2-tools CLI): TPM 2.0 seal ShS.KT.AUT to PCR 0/1/7; zero raw secret from JVM heap immediately after sealing; TPM unavailable at startup → terminals with sealed secret stay FAILED per FR-139
- [X] T082 [US8] Implement EHEALTH AUTHENTICATE VALIDATE at each CT session establishment in SicctTerminalConnection per FR-131: unseal TPM blob; compute SHA-256(challenge ‖ ShS.KT.AUT); verify terminal response; validation failure → close TLS + DISCONNECTED + error 4029; PCR mismatch → FAILED + CRITICAL log with terminal name and PCR indices per FR-139
- [X] T083 [P] [US8] Implement maintenance pairing ADD Phase 1/2 in SicctTerminalManager per FR-132: triggered automatically when reconnection fails due to pairing mismatch (outdated SAK.AUT key); uses EhealthAuthenticator.buildAddPhase1Request() and buildAddPhase2Request() per TIP1-A_5011
- [X] T084 [US8] Implement CardSlotTracker @Scheduled watchdog in quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/CardSlotTracker.java (monitors card insertion/ejection events from SICCT terminal; registers KeyAlias within 5 seconds of insertion; allocates CardHandle per FR-024, FR-093)
- [X] T085 [P] [US8] Implement SicctKeyStoreSpi APDU forwarding (completing US2 stub): route COMPUTE DIGITAL SIGNATURE APDU via SicctTerminalConnection; per-operation timeout enforced by apduTimeoutMs; private key never extracted from card per FR-094
- [X] T086 [US8] Implement SICCT VERIFY PIN command routing in SicctTerminalConnection: CardPinManagement MBean delegates here (Phase 13); PIN entered on terminal trusted display via SICCT VERIFY PIN command; PIN MUST NOT pass through JVM memory per FR-029, FR-225
- [X] T087 [P] [US8] Implement gSMC-KT certificate expiry monitor in SicctTerminalConnection: read C.SMKT.AUT cert returned during session establishment; if expiry ≤ 35 days → publish EC_CardTerminal_gSMC_KT_Certificate_Expires_Soon event + WARNING log with terminal name and remaining days per FR-137
- [X] T088 [US8] Implement SicctExtensionProcessor @BuildStep in quarkus-sicct-extension/deployment/src/main/java/de/servicehealtherx/quarkus/sicct/deployment/SicctExtensionProcessor.java (CDI registration for SicctTerminalManager, CardSlotTracker, SicctHealthCheck)
- [X] T089 [P] [US8] Implement SicctHealthCheck @Readiness HealthCheck in quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/SicctHealthCheck.java (one check per terminal; CONNECTED/DISCONNECTED state reported; wired into /q/health/ready per FR-026)
- [X] T090 [US8] Write SicctTerminalManagerTest in quarkus-sicct-extension/runtime/src/test/java/de/servicehealtherx/quarkus/sicct/runtime/SicctTerminalManagerTest.java using Netty EmbeddedChannel mock: UDP announcement → CardTerminal persisted + TCP connection initiated; simulated card insertion → alias registered within 5 seconds; TCP disconnect → RECONNECTING within 10 seconds; restart → reconnect within 60 seconds (quickstart Scenario 3)

**Checkpoint**: US8 complete — SICCT terminals connect/disconnect automatically; cards detected; pairing + TPM sealing verified; quickstart Scenario 3 passes.

---

## Phase 10: User Story 5 — Certificate Lifecycle Management (Priority: P2)

**Goal**: ReadCertificate reads X.509 from SM-B identity; VerifyCertificate checks against TI PKI via OCSP.

**Independent Test**: ReadCertificate for SM-B CardHandle with CertRef=C.AUT, Crypt=ECC → cert matches configured key source cert; eGK/HBAx CardHandle → error 4090 (US5 acceptance scenario 3).

- [X] T091 [US5] Implement CertificateService @ApplicationScoped CDI bean in crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/CertificateService.java (readCertificate + verifyCertificate via TrustService + CryptoProvider; emits audit log per FR-016)
- [X] T092 [US5] Implement CertificateService.readCertificate(): route to correct KeyStoreAdapter via CryptoProvider by CardHandle→KeyAlias; reject eGK and HBAx references with error 4090 per FR-051, FR-202; support CertRef C.AUT/C.OSIG × Crypt RSA/ECC → correct card EF per FR-052
- [X] T093 [P] [US5] Implement CertificateService.verifyCertificate(): TOLERATE_OCSP_FAILURE=yes; return VALID/INVALID/INCONCLUSIVE + role OIDs; OCSP transient failure → INCONCLUSIVE (not INVALID); OCSP failure when TOLERATE=no → INVALID per FR-053, FR-203, FR-218
- [X] T094 [US5] Implement ConsumerCertificateService CXF @WebService in consumer-soap-server/src/main/java/de/servicehealtherx/consumer/soap/ConsumerCertificateService.java (ReadCertificate + VerifyCertificate per consumer/CertificateService.wsdl v3.0.1; validate against consumer/CertificateService.xsd + CertificateServiceCommon.xsd + ConsumerCommon.xsd per FR-050)
- [X] T095 [P] [US5] Map error codes in ConsumerCertificateService per FR-202: 4090 for eGK/HBAx, 4149 for unresolvable CertRef, 4258 for EF not found on identity; VerificationResult in response body, not as SOAP fault per FR-203

**Checkpoint**: US5 complete — ReadCertificate + VerifyCertificate verified; consumer SOAP endpoint schema-valid; eGK/HBAx rejection verified.

---

## Phase 11: User Story 6 — VZD Directory Access via LDAP Proxy (Priority: P2)

**Goal**: LDAPv3 Bind/Unbind/Search/Abandon proxied to TI VZD over LDAPS; all other operations rejected with error 53.

**Independent Test**: LDAP Search for known practitioner OID → komLeData attribute returned; Modify attempt → unwillingToPerform (RFC 4511 error 53) (quickstart independent test US6).

- [X] T096 [US6] Implement Netty LDAPv3 server pipeline in quarkus-ldap-proxy-server-extension/runtime/src/main/java/de/servicehealtherx/quarkus/ldap/proxy/server/runtime/LdapServerCodec.java (LDAPv3 encode/decode per RFC 4511; listen on port 636 with TLS mandatory when ANCL_TLS_MANDATORY=Enabled; port 389 offered only when TLS flag disabled per FR-060, FR-062)
- [X] T097 [US6] Implement OperationFilter ChannelHandler in quarkus-ldap-proxy-server-extension/runtime/src/main/java/de/servicehealtherx/quarkus/ldap/proxy/server/runtime/OperationFilter.java (allowlist: Bind, Unbind, Search, Abandon; all other LDAPv3 ops → result code 53 unwillingToPerform per FR-061, FR-210, A_17341-01)
- [X] T098 [US6] Implement VzdLdapClient in quarkus-ldap-proxy-server-extension/runtime/src/main/java/de/servicehealtherx/quarkus/ldap/proxy/server/runtime/VzdLdapClient.java (LDAPS client to TI VZD port 636; mutual TLS; validate server cert via TrustService TUC_PKI_018: oid_vzd_ti, serverAuth, OCSP not offline per FR-063; TLS failure → disconnect client + LDAP error + ERROR log with endpoint per FR-211)
- [X] T099 [P] [US6] Implement DNS-SD VZD endpoint discovery in VzdLdapClient: PTR query `_ldap._tcp.vzd.<TI_DNS_TOP_LEVEL_DOMAIN>` via internal Stub-Resolver; cache resolved SRV records per FR-062, FR-125; runtime update without restart
- [X] T1- [ ] T100  [US6] Implement LdapProxyExtensionProcessor @BuildStep in quarkus-ldap-proxy-server-extension/deployment/src/main/java/de/servicehealtherx/quarkus/ldap/proxy/server/deployment/LdapProxyExtensionProcessor.java (CDI registration; Quarkus lifecycle @PostConstruct/@PreDestroy for Netty event loop)
- [X] T1- [ ] T101  [P] [US6] Forward all RFC 4511 Appendix A error codes from VZD unchanged to LDAP client per FR-210; DNS resolution failure → abort immediately + ERROR log with FQDN per FR-219
- [X] T1- [ ] T102  [P] [US6] Write LdapProxyIntegrationTest using embedded WireMock LDAPS stub: Bind + Search → result forwarded; Modify → error 53; TLS cert failure → client disconnected

**Checkpoint**: US6 complete — LDAP proxy forwards Bind/Unbind/Search/Abandon; non-permitted ops rejected with error 53.

---

## Phase 12: User Story 7 — KOM-LE Secure Messaging via openkim (Priority: P2)

**Goal**: openkim submodule provides SMTP/POP3 KOM-LE interface; outgoing messages encrypted+signed; incoming decrypted+verified.

**Independent Test**: Submit SMTP test email → S/MIME encrypted+signed payload at MTA; deliver via POP3 → decrypted content matches original (quickstart independent test US7).

- [X] T1- [ ] T103  [US7] Create openkim organizational fork from sberg-net/openkim and pin openkim-server/ submodule to specific commit per FR-071; document upstream sync process (60-day review window per FR-072) in openkim-server/UPSTREAM-SYNC.md
- [X] T1- [ ] T104  [P] [US7] Configure openkim-server TI-specific parameters in openkim-server/src/main/resources/: TLS_AUTH_KONNEKTOR, KONNEKTOR_TIMEOUT, KONNEKTOR_URI per FR-079; configure SMTP/POP3 pod-local port for basis-consumer-server communication per FR-070
- [X] T1- [ ] T105  [P] [US7] Implement SMTP command response table in openkim fork per Tab_SMTP_Ant_Init (FR-077, FR-212): EHLO → 250 + capability list (SIZE ≥ 35882577, AUTH LOGIN PLAIN, 8BITMIME, ENHANCEDSTATUSCODES, DSN); MAIL/RCPT/DATA before AUTH → 530 5.7.0; unauthenticated AUTH with unsupported mechanism → 504 5.7.4
- [X] T1- [ ] T106  [P] [US7] Configure ECC-preferred mode switch in openkim fork (runtime-toggleable per FR-078, A_26448; KOM-LE-A_2022-01): ECC cert used exclusively for encryption when ECC+RSA both present for recipient per FR-076
- [X] T1- [ ] T107  [P] [US7] Implement pre-send VZD komLeData version check per FR-080, FR-215: if installed client-module version < directory version → send informational DSN email to sender (do not abort sending); incoming messages decrypted and PL_TUC_VERIFY_DOCUMENT_nonQES verification result optionally attached as Signaturpruefungsbericht per FR-081, A_23999

**Checkpoint**: US7 complete — KOM-LE SMTP/POP3 encrypts/decrypts messages; version check produces informational email; SMTP response table per spec.

---

## Phase 13: User Story 13 — JMX Management Interface for Operators (Priority: P2)

**Goal**: All 9 MBeans visible in Hawtio under de.servicehealtherx; TSL reload, terminal connect/disconnect, PIN verify, backup, and diagnostic crypto operations all invocable via JMX.

**Independent Test**: Hawtio JMX tree shows all MBeans FR-221–FR-231; TslManagement.reloadTsl() → download + health UP; SignatureServiceManagement.sign() → valid Base64 signature + audit entry (quickstart Scenario 7).

- [X] T10- [ ] T108  [US13] Implement TslManagementMBean interface and TslManagement @ApplicationScoped bean in crypto-lib/src/main/java/de/servicehealtherx/crypto/jmx/TslManagementMBean.java and TslManagement.java (reloadTsl returns JSON with status/sequenceNumber/expiry/downloadedAt/error; getTslStatus; TslUrl read-only attribute; ObjectName de.servicehealtherx:module=crypto-lib,name=TslManagement; @PostConstruct register + @PreDestroy deregister on ManagementFactory.getPlatformMBeanServer(); 60s timeout per FR-223, FR-233)
- [X] T10- [ ] T109  [P] [US13] Write TslManagementTest in crypto-lib/src/test/java/de/servicehealtherx/crypto/jmx/TslManagementTest.java: register on test MBeanServer; invoke via MBeanServer.invoke(); assert TslDownloader.download() called once; method named test_FR220_TslManagement_reloadTsl_invokes_tsl_downloader() per FR-234
- [X] T1- [ ] T110  [US13] Implement CryptoProviderManagementMBean interface and CryptoProviderManagement @ApplicationScoped bean in crypto-lib/src/main/java/de/servicehealtherx/crypto/jmx/ (listKeyStores → JSON array of KeyStoreAdapter instances with storeType/aliases/availability; listKeyReferences → all KeyReference objects; getAvailability(alias); getAvailabilities(); ObjectName de.servicehealtherx:module=crypto-lib,name=CryptoProviderManagement per FR-226)
- [X] T1- [ ] T111  [P] [US13] Write CryptoProviderManagementTest in crypto-lib/src/test/java/de/servicehealtherx/crypto/jmx/CryptoProviderManagementTest.java per FR-234 pattern
- [X] T1- [ ] T112  [US13] Implement KeyStoreReloadManagementMBean interface and KeyStoreReloadManagement @ApplicationScoped bean in crypto-lib/src/main/java/de/servicehealtherx/crypto/jmx/ (reloadKeyStore triggers engineLoad(null,null) on matching KeyStoreAdapter; SICCT → no-op; reloadAllKeyStores iterates all adapters; failed adapters marked UNAVAILABLE not removed; ObjectName de.servicehealtherx:module=crypto-lib,name=KeyStoreReloadManagement per FR-227)
- [X] T1- [ ] T113  [P] [US13] Write KeyStoreReloadManagementTest in crypto-lib/src/test/java/de/servicehealtherx/crypto/jmx/KeyStoreReloadManagementTest.java per FR-234 pattern
- [X] T1- [ ] T114  [US13] Implement BackupRestoreManagementMBean interface and BackupRestoreManagement @ApplicationScoped bean in sicct-lib/src/main/java/de/servicehealtherx/sicct/jmx/ (exportBackup: generate 256-bit random password; AES-256-GCM via HKDF-SHA-256(IKM=password, salt=terminalId UTF-8, info="ShS.KT.AUT backup"); persist backupEncryptedSharedSecret; return password exactly once — MUST NOT persist; retention window (default 1h); importBackup: decrypt+re-seal to current TPM + emit CRITICAL audit entry per terminal; ObjectName de.servicehealtherx:module=sicct-lib,name=BackupRestoreManagement per FR-228, FR-180, FR-181, FR-233)
- [X] T1- [ ] T115  [P] [US13] Write BackupRestoreManagementTest in sicct-lib/src/test/java/de/servicehealtherx/sicct/jmx/BackupRestoreManagementTest.java: mock TPM + JPA; exportBackup → backupEncryptedSharedSecret non-null per terminal; importBackup(password) → sealedSharedSecret updated + backupEncryptedSharedSecret cleared + CRITICAL audit entries emitted (quickstart Scenario 7e; FR-234)
- [X] T1- [ ] T116  [US13] Implement SicctTerminalDiscoveryManagementMBean interface and SicctTerminalDiscoveryManagement @ApplicationScoped bean in quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/jmx/ (triggerDiscovery: delegates to SicctTerminalManager UDP broadcast; getLastDiscoveryResult: JSON array of discovered terminals; ObjectName de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalDiscoveryManagement per FR-222)
- [X] T1- [ ] T117  [P] [US13] Write SicctTerminalDiscoveryManagementTest in quarkus-sicct-extension/runtime/src/test/java/de/servicehealtherx/quarkus/sicct/runtime/jmx/SicctTerminalDiscoveryManagementTest.java per FR-234 pattern
- [X] T1- [ ] T118  [US13] Implement SicctTerminalConnectionManagementMBean interface and SicctTerminalConnectionManagement @ApplicationScoped bean in quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/jmx/ (connect(terminalId): no-op if CONNECTED, else initiate TCP; disconnect(terminalId): graceful close within apduTimeoutMs, suspend auto-reconnect; getTerminalStatus: JSON with connectionState/host/port/pairingStatus/activeSlots; listAllTerminals: JSON array; connectTimeoutMs timeout per FR-233; ObjectName de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalConnectionManagement per FR-224)
- [X] T1- [ ] T119  [P] [US13] Write SicctTerminalConnectionManagementTest in quarkus-sicct-extension/runtime/src/test/java/de/servicehealtherx/quarkus/sicct/runtime/jmx/SicctTerminalConnectionManagementTest.java per FR-234 pattern
- [X] T1- [ ] T120  [US13] Implement CardPinManagementMBean interface and CardPinManagement @ApplicationScoped bean in quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/jmx/ (verifyPin(terminalId, slotId, pinType): routes SICCT VERIFY PIN APDU via SicctTerminalConnection; PIN MUST NOT pass through JVM memory; pinType ∈ {HBA.PIN.CH, HBA.PIN.QES, SMC-B.PIN.SMC}; returns JSON result/retriesRemaining; getPinStatus: reads status without PIN entry; ObjectName de.servicehealtherx:module=quarkus-sicct-extension,name=CardPinManagement per FR-225)
- [X] T1- [ ] T121  [P] [US13] Write CardPinManagementTest in quarkus-sicct-extension/runtime/src/test/java/de/servicehealtherx/quarkus/sicct/runtime/jmx/CardPinManagementTest.java per FR-234 pattern
- [X] T1- [ ] T122  [US13] Implement DnsServiceDiscoveryManagementMBean interface and DnsServiceDiscoveryManagement @ApplicationScoped bean in quarkus-ldap-proxy-server-extension/runtime/src/main/java/de/servicehealtherx/quarkus/ldap/proxy/server/runtime/jmx/ (triggerServiceDiscovery(domain): re-executes DNS-SD PTR query per FR-125; empty/null domain → rediscovers all configured zones; returns JSON array of SRV records; getDiscoveredServices: snapshot JSON; timeout per FR-233; ObjectName de.servicehealtherx:module=quarkus-ldap-proxy-server-extension,name=DnsServiceDiscoveryManagement per FR-221)
- [X] T1- [ ] T123  [P] [US13] Write DnsServiceDiscoveryManagementTest in quarkus-ldap-proxy-server-extension/runtime/src/test/java/de/servicehealtherx/quarkus/ldap/proxy/server/runtime/jmx/DnsServiceDiscoveryManagementTest.java per FR-234 pattern
- [X] T1- [ ] T124  [US13] Implement SignatureServiceManagementMBean interface and SignatureServiceManagement @ApplicationScoped bean in crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/jmx/ (sign/verify/externalAuthenticate/getSignatureMode; all binary I/O Base64-encoded; getSignatureMode returns "NOT_IMPLEMENTED" for deferred ops per FR-157; all invocations emit audit log identical to SOAP-layer per FR-016; ObjectName de.servicehealtherx:module=crypto-services-lib,name=SignatureServiceManagement per FR-229)
- [X] T1- [ ] T125  [P] [US13] Write SignatureServiceManagementTest in crypto-services-lib/src/test/java/de/servicehealtherx/crypto/services/jmx/SignatureServiceManagementTest.java: sign → non-empty Base64 result; returned signature verifiable; audit entry emitted; verify → true for matching signature, false after tampering (quickstart Scenario 7d; FR-234)
- [X] T1- [ ] T126  [US13] Implement EncryptionServiceManagementMBean interface and EncryptionServiceManagement @ApplicationScoped bean in crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/jmx/ (encryptDocument/decryptDocument; all I/O Base64-encoded; recipientCertBase64 accepts single cert or JSON array of certs for multi-recipient; emits audit log per FR-016; ObjectName de.servicehealtherx:module=crypto-services-lib,name=EncryptionServiceManagement per FR-230)
- [X] T1- [ ] T127  [P] [US13] Write EncryptionServiceManagementTest in crypto-services-lib/src/test/java/de/servicehealtherx/crypto/services/jmx/EncryptionServiceManagementTest.java per FR-234 pattern
- [X] T1- [ ] T128  [US13] Implement CertificateServiceManagementMBean interface and CertificateServiceManagement @ApplicationScoped bean in crypto-services-lib/src/main/java/de/servicehealtherx/crypto/services/jmx/ (readCertificate(alias, certRef, crypt) → DER cert as Base64; verifyCertificate(certificateBase64) → JSON result/detail; ObjectName de.servicehealtherx:module=crypto-services-lib,name=CertificateServiceManagement per FR-231)
- [X] T1- [ ] T129  [P] [US13] Write CertificateServiceManagementTest in crypto-services-lib/src/test/java/de/servicehealtherx/crypto/services/jmx/CertificateServiceManagementTest.java per FR-234 pattern
- [X] T1- [ ] T130  [P] [US13] Configure Hawtio RBAC in consumer-soap-server: all mutating JMX operations (reloadTsl, connect, disconnect, verifyPin, exportBackup, importBackup, reloadKeyStore, reloadAllKeyStores, triggerDiscovery, triggerServiceDiscovery) require admin role; disable remote JMX connector in production; JVM startup flag -Dcom.sun.jndi.rmi.object.trustURLCodebase=false; MBeans registered exclusively on ManagementFactory.getPlatformMBeanServer() per FR-232

**Checkpoint**: US13 complete — all 9 MBeans visible in Hawtio; all unit tests pass per FR-234; quickstart Scenario 7a–7e verified.

---

## Phase 14: User Story 9 — Konnektor SOAP Compatibility Interface (Priority: P3)

**Goal**: Existing Primärsystem connects to /conn endpoint and operates identically to a hardware Konnektor without reconfiguration.

**Independent Test**: Certified Primärsystem connects via standard Konnektor parameters; all standard operations return schema-valid responses per conn/ WSDLs (SC-008, SC-021).

- [X] T1- [ ] T131  [US9] Implement AufrufKontextInterceptor CXF interceptor in konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/AufrufKontextInterceptor.java (validates mandantId, clientSystemId, workplaceId mandatory for all ops; userId mandatory for HBA-based ops; missing mandatory → error 4021; workplaceId not belonging to mandantId → 4021 per FR-150, FR-206)
- [X] T1- [ ] T132  [P] [US9] Implement ConnectorSdsResource JAX-RS endpoint in konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/ConnectorSdsResource.java (serves connector.sds from ServiceDirectory.xsd; live endpoint address matching server hostname+port per FR-156, SC-023)
- [X] T1- [ ] T133  [US9] Implement KonnektorSignatureService CXF @WebService in konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorSignatureService.java (SignDocument, SignPlain, VerifyDocument, GetJobNumber, StopSignature; ActivateComfortSignature + DeactivateComfortSignature + GetSignatureMode → fault code 7200 per FR-157; delegates to SignatureService)
- [X] T1- [ ] T134  [P] [US9] Implement KonnektorEncryptionService CXF @WebService in konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorEncryptionService.java (EncryptDocument, DecryptDocument; MTOM mandatory from PTV5 per FR-151; delegates to EncryptionService)
- [X] T1- [ ] T135  [P] [US9] Implement KonnektorCertificateService CXF @WebService in konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorCertificateService.java (CheckCertificateExpiration, ReadCardCertificate, VerifyCertificate; delegates to CertificateService)
- [X] T1- [ ] T136  [P] [US9] Implement KonnektorAuthSignatureService CXF @WebService in konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorAuthSignatureService.java (ExternalAuthenticate per FR-045, FR-152; SM-B userId ignored; HBAx userId mandatory)
- [X] T1- [ ] T137  [P] [US9] Implement KonnektorCardService CXF @WebService in konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorCardService.java (VerifyPin, ChangePin, UnblockPin, GetPinStatus, EnablePin, DisablePin per PL_TUC_CARD_VERIFY_PIN, PL_TUC_CARD_CHANGE_PIN; SecureSendAPDU per FR-143; StartCardSession/StopCardSession per FR-142; PIN material MUST NOT appear in logs per FR-144, FR-145)
- [X] T1- [ ] T138  [P] [US9] Implement KonnektorCardTerminalService CXF @WebService in konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorCardTerminalService.java (RequestCard, EjectCard; allocates/releases CardHandle)
- [X] T1- [ ] T139  [P] [US9] Implement KonnektorEventService CXF @WebService in konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorEventService.java (GetCards, GetCardTerminals, GetResourceInformation — tenant-scoped results only per FR-103; Subscribe/Unsubscribe/GetSubscription/RenewSubscriptions → fault 7200 per FR-157; card insertion/removal events in Konnektor format per FR-102)
- [X] T1- [ ] T140  [P] [US9] Implement remote-PIN via gSMC-KT trusted channel in KonnektorCardService per FR-141: TUC_KON_005 authMode=gegenseitig+TC; encrypt PIN via session key; terminal display indicates PIN entry; supported PIN types: HBA.PIN.CH/PUK.CH, HBA.PIN.QES/PUK.QES, SMC-B.PIN.SMC/PUK.SMC
- [X] T1- [ ] T141  [P] [US9] Implement card-to-card authentication TUC_KON_005 in KonnektorCardService per FR-140; SecureSendAPDU integrity validation per sequence counter; response APDU status word check per FR-143
- [X] T1- [ ] T142  [P] [US9] Implement CardHandle exclusivity lock: second request blocks up to 30 seconds; fault 4019 after timeout; lock timeout configurable per alias; prevents parallel signing on same physical card per FR-208, Assumptions

**Checkpoint**: US9 complete — all 7 Konnektor SOAP services implemented; schema-valid responses for all non-deferred operations; Primärsystem integration verified.

---

## Phase 15: Polish & Cross-Cutting Concerns

**Purpose**: Security hardening, compliance, and performance verification across all user stories.

- [X] T1- [ ] T143  [P] Implement SOAP 1.1 enforcement: return HTTP 400 for SOAP 1.2 requests per Assumptions; enforce client-system authentication via HTTP Basic Auth over TLS or X.509 client certificate per FR-155; non-TLS rejection when ANCL_TLS_MANDATORY=Enabled
- [X] T1- [ ] T144  [P] Implement WSDL ?wsdl URLs for all 10 SOAP endpoints (3 consumer + 7 konnektor): returned WSDL contains correct live endpoint address matching server hostname+port; imported XSD chain fully resolvable per FR-156, SC-023
- [X] T1- [ ] T145  [P] Implement outbound TLS enforcement for all connections to TI services (VZD, KSR, SZZP): reject on cert validation failure (unresolvable FQDN, expired, OCSP-revoked, role OID mismatch, mutual-auth failure); ERROR log with endpoint + reason per FR-216
- [X] T1- [ ] T146  [P] Implement DNS infrastructure: Caching Nameserver startup before any TI-connected service per FR-127; three forwarding zones per FR-123 (TI namespace, Bestandsnetze.xml, deployment domain); internal Stub-Resolver only — no public internet resolvers per FR-124; application readiness probe → unhealthy if Caching Nameserver unavailable per FR-219
- [X] T1- [ ] T147  [P] Security validation: automated integration test asserting no private key bytes appear in JVM heap dumps, application logs, or network traces (SC-004); verify P12 passwords never logged; verify SharedSecretDO zeroed from heap after each use
- [X] T1- [ ] T148  [P] Add SBOM entries for all new dependencies (Bouncy Castle BC-FIPS, Netty, beanit jASN1, tss.java, gemLibPki, configsource-db, openkim submodule) with license and security review notes per A_27431; record in sbom.xml or equivalent
- [X] T1- [ ] T149  Performance validation: @QuarkusTest load test verifying SOAP operations complete ≤ 2 seconds (system-controlled path, excludes hardware latency) per SC-002; verify 200 concurrent SOAP requests per tenant without cross-tenant data leakage per SC-022
- [X] T1- [ ] T150  Run complete quickstart.md Scenarios 1–7 validation; fix all regressions; verify traceability: each normative gematik Afo from gemSpec_Basis_Consumer_V1.12.1 has a named test (test_AXXXXX_*) and dedicated git commit per SC-011

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Requires Setup complete — **BLOCKS all user stories**
- **US2 (Phase 3)**: Requires Foundational — MVP baseline
- **US3, US4 (Phases 4–5)**: Require US2 complete (CryptoProvider must be operational); can run in parallel with each other
- **US10 (Phase 6)**: Requires Foundational (sicct-lib infra); can start in parallel with US3/US4
- **US11 (Phase 7)**: Requires US10 (generated ASN.1 classes)
- **US12 (Phase 8)**: Requires US10 (formalizes Maven integration)
- **US8 (Phase 9)**: Requires US11 + US12 (EHEALTH AUTH commands + generated classes); also requires US2 (SicctKeyStoreAdapter full implementation)
- **US5 (Phase 10)**: Requires US2 complete (CertificateService uses CryptoProvider)
- **US6 (Phase 11)**: Requires Foundational; independent of crypto user stories
- **US7 (Phase 12)**: Requires Foundational + openkim submodule (T002/T003)
- **US13 (Phase 13)**: Requires US2 + US5 + US8 (manages their services); US6 partial (DnsServiceDiscoveryManagement)
- **US9 (Phase 14)**: Requires US2 + US3 + US4 + US5 + US8 (delegates to all)
- **Polish (Phase 15)**: Requires all desired user stories complete

### User Story Dependencies

| Story | Priority | Depends On | Can Parallelize With |
|-------|----------|-----------|---------------------|
| US2   | P1       | Foundational | — |
| US3   | P1       | US2 | US4, US10 |
| US4   | P1       | US2 | US3, US10 |
| US5   | P2       | US2 | US6, US7, US10 |
| US6   | P2       | Foundational | US2, US3, US4, US10 |
| US7   | P2       | Foundational | Most others |
| US10  | P2       | Foundational | US3, US4, US5, US6 |
| US11  | P2       | US10 | US3, US4, US5, US6, US7 |
| US12  | P3       | US10 | US11 |
| US8   | P2       | US11, US12, US2 | US5, US6, US7 |
| US13  | P2       | US2, US5, US8 | US9 |
| US9   | P3       | US2, US3, US4, US5, US8 | US13 |

### Within Each Phase

- All tasks marked [P] in the same phase can run in parallel
- Tests (where included) are written per FR-234 mandate — they follow implementation of the specific MBean

---

## Parallel Execution Examples

### US2 Parallel Sprint (after Foundational complete)

```bash
# Launch all four adapter implementations in parallel:
Task: "T028 P12KeyStoreAdapter in crypto-lib/.../adapter/P12KeyStoreAdapter.java"
Task: "T029 Pkcs11KeyStoreAdapter in crypto-lib/.../adapter/Pkcs11KeyStoreAdapter.java"
Task: "T030 PcscKeyStoreAdapter in crypto-lib/.../adapter/PcscKeyStoreAdapter.java"
Task: "T031 SicctKeyStoreAdapter stub in crypto-lib/.../adapter/SicctKeyStoreAdapter.java"
# Then:
Task: "T032 CryptoProviderRouter wiring all adapters"
```

### US13 JMX Parallel Sprint (after US2/US5/US8 complete)

```bash
# All 9 MBean pairs + tests can be implemented in parallel (different files):
Task: "T108–T109 TslManagement + test in crypto-lib/jmx/"
Task: "T110–T111 CryptoProviderManagement + test in crypto-lib/jmx/"
Task: "T112–T113 KeyStoreReloadManagement + test in crypto-lib/jmx/"
Task: "T114–T115 BackupRestoreManagement + test in sicct-lib/jmx/"
Task: "T116–T117 SicctTerminalDiscoveryManagement + test in quarkus-sicct-extension/.../jmx/"
Task: "T118–T119 SicctTerminalConnectionManagement + test in quarkus-sicct-extension/.../jmx/"
Task: "T120–T121 CardPinManagement + test in quarkus-sicct-extension/.../jmx/"
Task: "T122–T123 DnsServiceDiscoveryManagement + test in quarkus-ldap-proxy-server-extension/.../jmx/"
Task: "T124–T125 SignatureServiceManagement + test in crypto-services-lib/jmx/"
Task: "T126–T127 EncryptionServiceManagement + test in crypto-services-lib/jmx/"
Task: "T128–T129 CertificateServiceManagement + test in crypto-services-lib/jmx/"
```

---

## Implementation Strategy

### MVP First (US2 + US3 + US4 only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (**CRITICAL — blocks all stories**)
3. Complete Phase 3: US2 — four key source types operational
4. **STOP and VALIDATE**: quickstart Scenarios 1–5 pass
5. Complete Phase 4: US3 — EncryptDocument/DecryptDocument
6. Complete Phase 5: US4 — SignDocument/VerifyDocument/ExternalAuthenticate
7. **STOP and VALIDATE**: consumer SOAP endpoints schema-valid; round-trip encryption/signing verified
8. Deploy/demo MVP

### Incremental Delivery

After MVP:
1. Add US10 + US11 + US12 → ASN.1 infrastructure ready → test and demo
2. Add US8 → SICCT terminals fully operational → test and demo (SC-012/SC-013)
3. Add US5 → Certificate service + ReadCertificate/VerifyCertificate → test and demo
4. Add US6 → LDAP proxy operational → test and demo
5. Add US13 → JMX management operational → operator workflow testable end-to-end
6. Add US9 → Konnektor compatibility → Primärsystem integration
7. Add US7 → KOM-LE messaging → complete TI capability
8. Polish phase

### Parallel Team Strategy (5 developers)

After Foundational complete:
- **Developer A**: US2 (crypto adapters) → US5 (certificate service)
- **Developer B**: US3 (encryption) → US4 (signing)
- **Developer C**: US10 (ASN.1) → US11 (EHEALTH AUTH) → US12 (Maven integration)
- **Developer D**: US6 (LDAP proxy) → US7 (KOM-LE)
- **Developer E**: US8 (SICCT terminal management — waits for C)

After US8 complete, all developers converge on US13 (JMX MBeans), then US9 (Konnektor).

---

## Notes

- **[P]** = different files, no incomplete-task dependencies — safe to run in parallel
- **[USN]** label maps task to user story for traceability
- Each user story phase is independently testable before moving to the next
- JMX unit tests (FR-234) are mandatory — they are not optional
- ASN.1 round-trip tests (SC-014–SC-019) are mandatory — explicitly required by success criteria
- Private key material security constraints (FR-016, FR-029, FR-139, FR-225) apply throughout — do not defer to Polish phase
- All production config via AppConfigProperty/configsource-db (FR-028); no config files in production
- SICCT terminal config exclusively via CardTerminal JPA entity (FR-096); no quarkus.sicct.terminals[*] config keys
- Commit after each task or logical group; one commit per normative gematik Afo per SC-011
