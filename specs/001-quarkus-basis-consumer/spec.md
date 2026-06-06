# Feature Specification: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Feature Branch**: `001-quarkus-basis-consumer`

**Created**: 2026-06-05

**Status**: Draft

**Normative Sources**:
- gemSpec_Basis_Consumer_V1.12.1 (Stand: 18.02.2026)
- gemSpec_Kon_V5.27.0 (Stand: 21.01.2026)
- gemSpec_KT_V3.17.0 (Stand: 12.02.2024) — eHealth-Kartenterminal: EHEALTH TERMINAL AUTHENTICATE protocol, SICCT TLS state machine, terminal security requirements
- SICCT-Spezifikation-1.3.0 (TeleTrusT, 2025)
- gemSpec_DS_Hersteller_V1.7.0
- gemKPT_Test_V3.6.0
- [gematik/api-telematik @ ebk_6.0.3](https://github.com/gematik/api-telematik/tree/ebk_6.0.3) — canonical WSDL and XSD definitions for `consumer/` and `conn/` service interfaces

**Architectural Mandates** (user-specified, non-negotiable):
- Runtime: Quarkus 3.x on Java 21, multi-tenant via one Kubernetes namespace per tenant
- SICCT card terminal integration MUST be packaged as a Quarkus CDI extension (deployment + runtime Maven modules) managing TCP/IP client connections via Netty
- All cryptographic signing and decryption operations MUST be routed through a unified JCE provider abstraction (`CryptoProvider` CDI interface) that simultaneously supports four key source types: P12 software keystores, PKCS#11 HSMs (e.g. Utimaco CryptoServer), PC/SC USB card readers, and SICCT networked card terminals
- KOM-LE client module MUST be fulfilled by a fork of the openkim open-source library (https://github.com/sberg-net/openkim), integrated as a git submodule
- SOAP web services MUST be implemented using Apache CXF (Quarkus CXF extension)
- Frontend/management console: Hawtio
- Persistence: JPA beans with JavaDB
- ASN.1 code generation for the SICCT schema MUST use the [beanit jASN1 library](https://www.beanit.com/asn1/)

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Tenant Onboarding & Isolation (Priority: P1)

A healthcare organization (Leistungserbringer or institution) is registered as a new tenant. Their identity (SMC-B) is provisioned in the system and all cryptographic operations performed in their name are fully isolated from every other tenant. The tenant's identity materials, configuration, and audit logs are never visible to or accessible by other tenants.

**Why this priority**: Without tenant isolation, regulated healthcare data may cross organizational boundaries, violating legal obligations and gematik security requirements. This is the foundational capability for a shared-infrastructure deployment.

**Independent Test**: Provision two tenants. Sign a document using Tenant A's identity, then attempt to use or observe that signing key from Tenant B's context. All cross-tenant access attempts must fail with an authorization error.

**Acceptance Scenarios**:

1. **Given** an administrator creates a new tenant with a valid SMC-B identity, **When** that tenant's namespace is provisioned in Kubernetes, **Then** the tenant can immediately perform all TI cryptographic operations using their own identity, and no other tenant's namespace can reach their resources.
2. **Given** two tenants A and B are active, **When** Tenant A submits an `EncryptDocument` request, **Then** the resulting encrypted payload is associated exclusively with Tenant A's CardHandle and cannot be decrypted using Tenant B's private key.
3. **Given** a tenant's identity is revoked or deprovisioned, **When** any subsequent SOAP call arrives for that tenant, **Then** the system returns a defined error code and logs the attempt without affecting other tenants.

---

### User Story 2 — Pluggable Cryptographic Key Source (Priority: P1)

An operator configures which physical or virtual key source backs each cryptographic identity for a tenant. In a development environment, a P12 file provides keys with no hardware dependency. In production, an HSM holds keys with FIPS 140-2 Level 3 protection. Where physical cards are co-located with the server, a PC/SC USB reader provides card access. Where physical cards are remote, a networked SICCT card terminal provides card access. All four source types can be active simultaneously in a single deployment, with routing determined entirely by configuration — no code change is required to switch or mix sources.

**Why this priority**: The pluggable key source architecture is foundational to all signing, decryption, and authentication operations. Without it, the crypto services have no key material to operate on. It also dramatically simplifies development (P12 in dev/CI, HSM in production) and enables mixed hardware/software deployments.

**Independent Test**: Configure one P12 key source, one SoftHSM2-backed PKCS#11 source, one virtual PC/SC reader, and one mock SICCT terminal — all simultaneously. Submit one signing request per source in parallel. All four complete successfully and produce distinct, verifiable signatures. Removing one source from configuration takes effect within 60 seconds without restarting the application; the other three continue serving requests.

**Acceptance Scenarios**:

1. **Given** a P12 keystore is configured for an alias, **When** a signing or decryption request is submitted for that alias, **Then** the operation completes using the P12-backed key; no hardware is required.
2. **Given** a PKCS#11 HSM is configured for an alias, **When** a signing or decryption request is submitted for that alias, **Then** the operation executes inside the HSM; no private key bytes appear in application memory, logs, or network traces.
3. **Given** a smart card is inserted into a PC/SC USB reader, **When** the system detects the insertion, **Then** within 10 seconds the card's key alias is available for signing requests; the operation executes on the card chip.
4. **Given** a card is inserted in a networked SICCT card terminal, **When** a signing request is submitted for that alias, **Then** the SICCT extension routes the request via TCP/IP to the terminal; the APDU executes on the card; the private key never leaves the card.
5. **Given** P12, HSM, PC/SC, and SICCT key sources are all configured and active, **When** four concurrent signing requests arrive (one per alias type), **Then** all four complete in parallel; no cross-source routing occurs.
6. **Given** a key alias is removed from configuration, **When** the next config reload cycle completes (within 60 seconds), **Then** further requests for that alias return "key not found"; all other aliases continue operating.

---

### User Story 3 — Hybrid Document Encryption & Decryption (Priority: P1)

A client application (Primärsystem or Fachmodul) submits a plaintext document to the Basis Consumer over the SOAP Encryption Service. The Basis Consumer encrypts it for one or more recipients using hybrid encryption (asymmetric key wrapping + symmetric bulk encryption) and returns the ciphertext. A corresponding operation decrypts a previously encrypted document using the private key identified by the provided CardHandle. The private key always remains within its configured key source boundary (HSM, card, or P12).

**Why this priority**: Encrypted data exchange is the primary security function of the TI and the core obligation of a Basis Consumer under A_17477 and A_17510-05.

**Independent Test**: Submit a test XML document to `EncryptDocument` with a known recipient certificate, then submit the ciphertext to `DecryptDocument` referencing the corresponding CardHandle. The decrypted output must be byte-identical to the original input.

**Acceptance Scenarios**:

1. **Given** a client provides a recipient's X.509 certificate and a document, **When** `EncryptDocument` is called, **Then** the service returns a hybrid-encrypted document (XMLEnc for XML, CMS for binary) with one encrypted key block per recipient certificate.
2. **Given** a client provides an encrypted document and a CardHandle referencing a key source, **When** `DecryptDocument` is called, **Then** the service returns the decrypted original document; the private key never leaves its configured key source boundary.
3. **Given** a certificate passed to `EncryptDocument` is expired or revoked, **When** the validity check runs (per PL_TUC_PKI_VERIFY_CERTIFICATE), **Then** the operation is rejected with an appropriate error and no encryption is performed.
4. **Given** both ECC and RSA certificates are available for a recipient and ECC-preferred mode is active, **When** `EncryptDocument` is called, **Then** the ECC certificate is selected exclusively (RSA MUST NOT be used).

---

### User Story 4 — Non-QES Document Signing (Priority: P1)

A client application submits one or more documents to the Basis Consumer's Signature Service. The Basis Consumer creates a non-qualified CMS electronic signature (nonQES) for each document using the private key of the tenant's configured key source. A corresponding verification operation checks a signed document and returns a binary verdict (VALID / INVALID / INCONCLUSIVE).

**Why this priority**: Non-QES document signatures are mandatory for KOM-LE message signing and TI interoperability. Required by A_17523 and A_17525-04.

**Independent Test**: Sign a test document via `SignDocument`, then verify via `VerifyDocument` without modification. Result must be VALID. Alter one byte; verify again — result must be INVALID.

**Acceptance Scenarios**:

1. **Given** a client provides a CardHandle and one or more `SignRequest` elements, **When** `SignDocument` is called, **Then** a CMS signature (RFC 5652, CAdES-BES) is returned for each `SignRequest`, associated via `RequestID`.
2. **Given** a signed document is submitted to `VerifyDocument`, **When** verification runs, **Then** the response contains `SIG:VerificationResult` with `HighLevelResult` equal to VALID, INVALID, or INCONCLUSIVE.
3. **Given** a binary hash is submitted to `SignPlain`, **When** the operation executes, **Then** an ECDSA signature is returned using `PrK.HCI.OSIG.E256` exclusively when ECC-preferred mode is active; the RSA key reference MUST NOT be used in that mode.
4. **Given** a binary challenge of at most 256 bits is submitted to `ExternalAuthenticate` with a SM-B CardHandle, **When** the operation executes, **Then** the ECDSA signature over the hash is returned using `PrK.HCI.AUT` in `DF.ESIGN`.

---

### User Story 5 — Certificate Lifecycle Management (Priority: P2)

A client application reads X.509 certificates from the tenant's key source identity (SM-B) or verifies the validity of an externally supplied certificate against the TI PKI (OCSP + CRL). The certificate service enables clients to obtain current public key material and to check whether a certificate should be trusted for use in encryption or signature operations.

**Why this priority**: Certificate validity checks gate all cryptographic operations. Expired or revoked certificates MUST be excluded per A_24782-02 and A_17429-02.

**Independent Test**: Call `ReadCertificate` for a provisioned SM-B CardHandle with `CertRef=C.AUT` and `Crypt=ECC`. The returned certificate must match the ECC authentication certificate on the configured key source.

**Acceptance Scenarios**:

1. **Given** a valid CardHandle and a `CertRefList` (C.AUT or C.OSIG), **When** `ReadCertificate` is called, **Then** the DER-encoded X.509 certificate matching the requested crypto algorithm (RSA or ECC, defaulting to ECC) is returned as a base64-encoded element.
2. **Given** an X.509 certificate is submitted to `VerifyCertificate`, **When** the TI PKI check runs (OCSP, `TOLERATE_OCSP_FAILURE=yes`), **Then** the response includes VALID, INVALID, or INCONCLUSIVE plus role OIDs extracted from the certificate.
3. **Given** a CardHandle referencing an eGK or HBAx is passed to `ReadCertificate`, **When** the request is processed, **Then** the operation is rejected with error 4090 — reading eGK or HBAx certificates is forbidden for client systems.

---

### User Story 6 — VZD Directory Access via LDAP Proxy (Priority: P2)

A client application (or internal module) performs directory lookups in the central TI Verzeichnisdienst (VZD) to find KIM addresses, certificates, and organizational data for healthcare participants. The Basis Consumer acts as an LDAP proxy, routing LDAPv3 queries through the TI network connection.

**Why this priority**: The LDAP proxy enables KOM-LE recipient discovery and PKI certificate retrieval, which are prerequisite capabilities for secure messaging and encryption.

**Independent Test**: Execute an LDAP Search for a known practitioner OID via the client interface. The result must contain the expected `komLeData` attribute. Attempt a Modify operation — the system must return `unwillingToPerform` (error 53).

**Acceptance Scenarios**:

1. **Given** a client issues an LDAPv3 Bind, Search, Unbind, or Abandon operation, **When** the request is processed, **Then** the Basis Consumer forwards it to the TI VZD and returns the result to the client unchanged.
2. **Given** a client issues any LDAPv3 operation other than the four permitted (Modify, Add, Delete, etc.), **When** the request arrives, **Then** the Basis Consumer returns LDAP error 53 (unwillingToPerform) immediately per A_17341-01.
3. **Given** the KOM-LE module is about to send a message, **When** it queries the VZD for the sender's `komLeData` attribute, **Then** if the installed KIM client-module version is lower than the version in the directory, the sender receives a human-readable warning email per A_22664.

---

### User Story 7 — KOM-LE Secure Messaging via openkim (Priority: P2)

Healthcare professionals and institutions exchange encrypted, signed electronic messages (KOM-LE / KIM) through the Basis Consumer's KOM-LE client module. The KOM-LE capability is fulfilled by the openkim open-source project, integrated as a git submodule from an organizational fork of https://github.com/sberg-net/openkim. All KOM-LE-specific customizations and TI compliance adaptations are made in the fork. Outgoing messages are encrypted for all recipients and the sender, signed, and transmitted via the tenant's mail transport agent. Incoming messages are decrypted and signature-verified before delivery.

**Why this priority**: KOM-LE is a mandatory TI capability for healthcare communication. Using openkim reduces implementation risk by building on a proven, community-maintained KIM library. The fork+submodule pattern ensures control over TI-specific adaptations while enabling upstream improvements.

**Independent Test**: Submit a test email via SMTP to the KOM-LE module. The outgoing payload intercepted at the MTA level must be a valid S/MIME encrypted+signed message. Deliver it back through POP3; the decrypted content must match the original.

**Acceptance Scenarios**:

1. **Given** a client mail application connects via SMTP and authenticates, **When** it submits a `MAIL`, `RCPT`, `DATA` sequence, **Then** the KOM-LE module encrypts the message for each RCPT and the sender using `C.HCI.ENC` or `C.HP.ENC` certificates, and forwards to the MTA.
2. **Given** both ECC and RSA encryption certificates are available for a recipient and ECC-preferred mode is active, **When** the KOM-LE module encrypts, **Then** it uses the ECC certificate exclusively per KOM-LE-A_2022-01.
3. **Given** a KIM message arrives via POP3, **When** it is delivered to the client application, **Then** signature verification has been performed and the result is optionally attached as a `Signaturpruefungsbericht_JJJJMMTT_hhmm` file per A_23999.
4. **Given** the SMTP session begins, **When** `HELO` or `EHLO` arrives before authentication, **Then** the module responds per Tab_SMTP_Ant_Init (SIZE ≥ 35882577, AUTH LOGIN PLAIN, 8BITMIME); unauthenticated MAIL/RCPT/DATA attempts receive `530 5.7.0`.

---

### User Story 8 — SICCT Card Terminal Management (Priority: P2)

An operator manages SICCT-compatible card terminals (for SMC-B and eHBA cards) entirely through the Hawtio management console. Terminal configurations — network address, port, and name — are stored in the database and survive application restarts without any config file changes. Adding a terminal via the console immediately triggers a connection attempt; no restart is required. The SICCT Quarkus extension loads all terminal records from the database at startup, maintains persistent TCP/IP client connections to each, discovers cards, tracks slot state per tenant, and routes card-based cryptographic operations. Disconnections are detected automatically and reconnected with exponential backoff.

**Why this priority**: SICCT terminal management is the mechanism for accessing physical cards (SMC-B, eHBA) in network-connected card terminals — the primary card access path in multi-tenant TI deployments. Database-driven configuration eliminates restart-requiring config file edits, reducing operational risk and service interruption.

**Independent Test**: Add a SICCT terminal via the management console. Within 15 seconds, the terminal appears as CONNECTED — no restart needed. Insert a test SMC-B; call `RequestCard`, then `ReadCertificate` for the resulting CardHandle. The certificate matches the physical card. Restart the application; the terminal reconnects automatically within 60 seconds. Simulate TCP disconnection; verify reconnection within 15 seconds.

**Acceptance Scenarios**:

1. **Given** an operator submits a new terminal record (name, host, port) via the management console, **When** the record is saved, **Then** it is persisted to the database, a connection attempt begins immediately, and the terminal's connection status is visible in the console within 5 seconds — without restarting the application.
2. **Given** terminal records exist in the database, **When** the application starts, **Then** the SICCT extension loads all terminal records and initiates connections; terminals that respond are CONNECTED within 60 seconds; unreachable terminals are DISCONNECTED and retried automatically.
3. **Given** a card is inserted in a connected SICCT terminal, **When** the terminal notifies the extension of the insertion event, **Then** within 5 seconds a CardHandle is allocated and the card is available for cryptographic operations.
4. **Given** a CardHandle references a physical eHBA in a SICCT terminal, **When** `SignDocument` is called, **Then** the signature uses the eHBA's private key via SICCT APDU forwarding; the private key never leaves the card.
5. **Given** an operator updates a terminal's host or port via the management console, **When** the change is saved, **Then** the existing connection is closed and a new connection to the updated address is attempted within 15 seconds — without a restart.
6. **Given** an operator deletes a terminal record via the management console, **When** the deletion is confirmed, **Then** the TCP/IP connection is gracefully closed (in-flight APDUs complete or time out), the record is removed from the database, and affected CardHandles are invalidated within 5 seconds.
7. **Given** a TCP/IP connection to a terminal is lost, **When** the extension detects the disconnection within 10 seconds, **Then** affected CardHandles are invalidated, in-flight operations return an appropriate error, the disconnection is logged, and automatic reconnection with exponential backoff begins.
8. **Given** multiple SICCT terminals are registered, **When** signing requests for cards on different terminals arrive concurrently, **Then** each request is routed to the correct terminal and processed in parallel.

---

### User Story 9 — Konnektor SOAP Compatibility Interface (Priority: P3)

An existing Primärsystem (primary healthcare IT system) that was previously configured to talk to a hardware Konnektor can connect to the Basis Consumer using the same SOAP endpoint configuration without modification. The Basis Consumer exposes the full Konnektor client-system interface as specified in gemSpec_Kon_V5.27.0, making it a drop-in cloud replacement for the on-premises Konnektor appliance.

**Why this priority**: Konnektor compatibility enables healthcare providers to migrate to the cloud Basis Consumer without modifying their existing certified practice software, dramatically reducing integration cost.

**Independent Test**: Connect a certified Primärsystem using its standard Konnektor connection parameters. Execute the full set of operations it normally invokes. All responses must be schema-valid per the Konnektor WSDLs and satisfy the Primärsystem's business logic.

**Acceptance Scenarios**:

1. **Given** a Primärsystem connects to the Basis Consumer's Konnektor compatibility endpoint, **When** it calls `SignDocument` (Konnektor WSDL namespace), **Then** the response is schema-valid and functionally equivalent to a hardware Konnektor response.
2. **Given** the Primärsystem subscribes to card insertion events via the Konnektor `EventService`, **When** a card is inserted into a SICCT terminal, **Then** the Basis Consumer delivers a `CARD/INSERTED` event notification matching the Konnektor event format.
3. **Given** the Primärsystem calls `GetCards` or `GetCardTerminals`, **When** the response is returned, **Then** only cards and terminals belonging to the requesting tenant's namespace are visible.

---

### User Story 10 — Developer Encodes and Decodes SICCT Protocol Messages (Priority: P2)

A developer implementing the SICCT Quarkus extension needs to construct and parse SICCT protocol messages — command APDUs, response APDUs, and event notifications — using type-safe Java objects rather than raw byte arrays. Instead of manually constructing byte sequences or writing ad-hoc TLV parsers, the developer works with generated Java classes that reflect the SICCT ASN.1 schema. Encoding to wire format and decoding from wire format are single-method calls.

**Why this priority**: Without type-safe encoding/decoding, every protocol message requires manual byte manipulation — error-prone, untestable, and a maintenance burden. Generated classes ensure schema correctness by construction and enable compiler-checked usage.

**Independent Test**: A developer instantiates a `CommandAPDU` Java object, sets its header (CLA=0x80, INS=GET_STATUS, P1=0x00, P2=0x00) and a `SicctDataObject` list, encodes it to a byte array using the beanit encoder, then decodes that byte array back and compares every field. The round-trip MUST produce identical values.

**Acceptance Scenarios**:

1. **Given** a developer creates a `CommandAPDU` object with `SicctInstruction.getStatus` and a single `ICCS-DO`, **When** the object is encoded to BER, **Then** the resulting byte array MUST match the expected wire format for a SICCT GET STATUS command, byte-for-byte.
2. **Given** a raw BER-encoded response from a SICCT terminal containing a `CTS-DO`, **When** the byte array is decoded using the generated `ResponseAPDU` class, **Then** all fields — including the nested `ICCS-DO` and `StatusWord` — MUST be populated with the correct values.
3. **Given** a raw event notification byte sequence containing a `cardInserted` event, **When** decoded as `EventNotification`, **Then** the `cardInserted` CHOICE alternative MUST be selected and the FU number MUST be correct.
4. **Given** the developer passes a malformed byte array (truncated at a TLV boundary), **When** decoding is attempted, **Then** the library MUST throw a typed exception identifying the decode failure; no partial or silently corrupted object MUST be returned.

---

### User Story 11 — Developer Builds EHEALTH TERMINAL AUTHENTICATE Commands (Priority: P2)

A developer implementing the EHEALTH TERMINAL AUTHENTICATE protocol (FR-130–FR-132) needs to construct all four command variants — CREATE, VALIDATE, ADD Phase 1, and ADD Phase 2 — and parse their corresponding responses, using type-safe generated classes. Size constraints (shared secret exactly 16 bytes, hash exactly 32 bytes) are enforced at the API level by the generated encoder.

**Why this priority**: EHEALTH TERMINAL AUTHENTICATE is a security-critical protocol. Manual byte construction of shared secrets, challenge/response hashes, and signature verification creates high risk of subtle encoding errors that would either break authentication or introduce a security vulnerability.

**Independent Test**: A developer builds an `EhealthAuthCreateRequest` with a 16-byte shared secret and a display label, encodes it to BER, and verifies the encoded form contains the shared secret DO and the application label DO with correct tags. A CREATE request with a shared secret of any size other than 16 bytes MUST be rejected during encoding with a clear constraint-violation error.

**Acceptance Scenarios**:

1. **Given** a developer creates an `EhealthAuthCreateRequest` with a 16-byte `SharedSecretDO` and a non-empty `APPL-DO` display label, **When** encoded to BER and wrapped in a `CommandAPDU` with INS=`ehealth-terminal-authenticate` and P2=`create`, **Then** the resulting bytes MUST contain both DOs in the correct order with the correct tags.
2. **Given** a developer creates a `SharedSecretDO` with fewer or more than exactly 16 bytes, **When** encoding is attempted, **Then** the encoder MUST reject it with a size-constraint violation before any bytes are written to the output.
3. **Given** a CREATE response containing a 64-byte ECDSA or 100-byte RSA signature, **When** decoded as `EhealthAuthCreateResponse`, **Then** the signature bytes are accessible as an OCTET STRING of the correct length; if neither 64 nor 100 bytes, decoding MUST fail with a constraint error.
4. **Given** an ADD Phase 1 response (terminal-generated random challenge of at least 16 bytes), **When** decoded as `EhealthAuthAddPhase1Response`, **Then** the challenge bytes MUST be accessible with the declared minimum length satisfied.

---

### User Story 12 — Developer Integrates Generated Classes into the SICCT Quarkus Extension (Priority: P3)

A developer adds the generated ASN.1 Java binding classes to the `sicct-lib` module. The Maven build for `sicct-lib` regenerates the classes from the ASN.1 source automatically so that any schema change is immediately reflected in the compiled library without requiring manual copying of generated sources.

**Why this priority**: Generated source files that are manually copied become stale. Tight build integration ensures schema and generated code are always in sync, which is essential for a protocol library where a single-byte encoding difference breaks the connection.

**Independent Test**: A developer modifies `SICCT.asn1` (e.g. adds a comment), runs the Maven build, and observes that the generated Java class is regenerated automatically without any manual step. The build MUST fail if the ASN.1 schema contains a syntax error.

**Acceptance Scenarios**:

1. **Given** the Maven build runs cleanly, **When** a developer inspects the output, **Then** all ASN.1 types defined in `SICCT.asn1` — including the EHEALTH AUTHENTICATE extension (TEIL E) — MUST have a corresponding generated Java class.
2. **Given** a syntax error is introduced into `SICCT.asn1`, **When** the Maven build runs, **Then** the build MUST fail at the code-generation phase with an error message that includes the ASN.1 file name and the line number of the error.
3. **Given** the generated sources directory is deleted, **When** the Maven build runs, **Then** all Java classes MUST be regenerated from scratch; no previously generated file MUST be required to be present.

---

### Edge Cases

- What happens when the HSM is temporarily unreachable during a signing operation?
- How does the system handle a SICCT terminal that disappears mid-session (network loss)?
- What happens when an ECC certificate is not available in the VZD but an RSA one exists, and ECC-preferred mode is active?
- How are simultaneous requests for the same CardHandle from multiple client sessions serialized?
- What happens if a tenant's namespace is deleted while active sessions exist?
- How does the system respond if the TI VPN connection drops during an LDAP Search operation?
- What happens when a PKCS#11 HSM library returns `CKR_TOKEN_NOT_PRESENT`?
- What happens when a PC/SC card is removed while a signing APDU is in flight?
- What happens when two configured key sources have the same alias?
- What error is returned when a document submitted for encryption exceeds 35882577 bytes?
- What happens when the database is unavailable at application startup (cannot load terminal records)?
- What happens if two operators for the same tenant concurrently attempt to delete the same terminal record?
- What happens when a terminal record is updated while a card in that terminal has an in-flight signing operation?
- What happens if an operator attempts to create a terminal with a name already used by another terminal in the same tenant?
- What happens when EHEALTH TERMINAL AUTHENTICATE (pairing) fails because the terminal's display shows the pairing text but the operator declines on the device?
- What happens when the gSMC-KT client certificate used for SICCT TLS is within 35 days of expiry?
- What happens when a terminal has an existing pairing block but its stored public key does not match the connector's current SAK.AUT key (e.g. after key rotation)?
- What happens when an `ExternalAuthenticate` request targets an eHBA rather than an SM-B — specifically, when the `userId` parameter is absent or does not match the card in the terminal?
- What happens when a SOAP 1.2 request arrives at an endpoint that expects SOAP 1.1?
- What happens when MTOM encoding is not used for a large binary payload in SignatureService or EncryptionService — does the server fall back to base64-inline or reject the request?
- What happens when the `workplaceId` in AufrufKontext exists in the system but does not belong to the `mandantId` in the same request?
- What happens when two concurrent SOAP calls compete for exclusive access to the same CardHandle?
- What happens when the WSDL import chain (e.g. `ConsumerCommon.xsd`) cannot be resolved at the `?wsdl` URL because of a proxy or load-balancer rewriting the host header?
- What happens when the ASN.1 parser encounters a SICCT tag collision (e.g. `CTM-DO` and `INTFC-DO` both using tag `[APPLICATION 6]`) — are both types generated correctly without conflict?
- What happens when a received SICCT event notification uses a tag not defined in the schema — does the decoder fail gracefully or silently skip the unknown data object?
- What happens when the beanit encoder is passed a `null` mandatory field — does it produce a clear `NullPointerException` with the field name, or an opaque encoding failure?
- What happens when a shared secret in a CREATE command contains an all-zero byte value — is it accepted (a valid, if weak, random value) or rejected?

---

## Requirements *(mandatory)*

### Functional Requirements

**FR-001 — FR-009: Multi-Tenant Infrastructure**

- **FR-001**: The system MUST isolate every tenant in a dedicated Kubernetes namespace such that no tenant's cryptographic keys, configuration, session state, or audit logs are accessible from any other tenant's context.
- **FR-002**: All SOAP operations MUST be authenticated and authorized against the requesting tenant's identity; cross-tenant access MUST be rejected with a defined error code.
- **FR-003**: The system MUST support dynamic tenant provisioning and deprovisioning without a full system restart.
- **FR-004**: CardHandle identifiers MUST be scoped to a single tenant; a CardHandle from Tenant A MUST NOT be usable in Tenant B's context.

**FR-010 — FR-039: Pluggable Cryptographic Provider**

- **FR-010**: The system MUST provide a unified `CryptoProvider` CDI interface through which all services (Encryption Service, Signature Service, Certificate Service) perform signing and decryption operations. No service MUST directly access key material; all access MUST be via this interface.
- **FR-011**: The `CryptoProvider` MUST route each operation to the correct key source based solely on the requested key alias; callers MUST NOT need to know which key source type backs a given alias.
- **FR-012**: The `CryptoProvider` MUST simultaneously support four key source types, all configurable via the application's standard configuration: P12 software keystores, PKCS#11 HSMs, PC/SC smart card readers, and SICCT networked card terminals.
- **FR-013**: Key aliases MUST be unique across all configured key sources within a single tenant instance; the system MUST detect and reject conflicting alias configurations at startup with a human-readable error identifying the conflict.
- **FR-014**: The `CryptoProvider` MUST report the availability status (AVAILABLE / UNAVAILABLE / ERROR) of each registered key alias; this status MUST be exposed via the SmallRye Health readiness endpoint.
- **FR-015**: Adding or removing a P12 or PC/SC key source MUST take effect within 60 seconds without restarting the application; adding, updating, or removing a SICCT terminal MUST take effect immediately via the management console (see FR-096–FR-099); PKCS#11 source changes require restart; in-flight operations at the time of any removal MUST either complete or fail with a clear error.
- **FR-016**: The system MUST emit a structured audit log entry for every cryptographic operation (alias, operation type, algorithm, caller identity, success/failure, duration); private key material MUST NOT appear in any log field.
- **FR-017**: P12 key sources MUST support password-protected PKCS#12 keystores with RSA and ECC keys; multiple P12 sources with different aliases MAY be configured simultaneously.
- **FR-018**: PKCS#11 HSM key sources MUST be integrated via the JDK SunPKCS11 provider; private keys stored in the HSM MUST NOT be extractable; the HSM vendor library MUST be configurable via file path (no code change required to switch vendors).
- **FR-019**: Multiple PKCS#11 token slots MAY be configured simultaneously; each slot is independently addressed by alias; PKCS#11 session loss (`CKR_TOKEN_NOT_PRESENT`, `CKR_SESSION_CLOSED`) MUST be detected and the key source marked UNAVAILABLE.
- **FR-020**: PC/SC key sources MUST integrate with PC/SC-compliant USB card readers via the JDK `javax.smartcardio` API; the system MUST monitor readers for card insertion and removal events, updating available aliases within 10 seconds of a physical event.
- **FR-021**: Signing operations on PC/SC-connected cards MUST execute on the card chip; private key bytes MUST NOT be loaded into application memory; card PIN MUST be configurable per alias.
- **FR-022**: The SICCT card terminal key source MUST be implemented as a Quarkus CDI extension (deployment + runtime Maven modules) managing TCP/IP client connections via Netty.
- **FR-023**: The SICCT extension MUST manage TCP/IP client connections to one or more configured terminal endpoints (host + port); each endpoint has an independently managed connection with its own health state and retry logic.
- **FR-024**: Cards in connected SICCT terminals MUST be automatically detected; their key aliases MUST be registered within 5 seconds of card insertion notification; signing MUST execute via SICCT APDU forwarding on the card.
- **FR-025**: SICCT TCP/IP disconnections MUST be detected within 10 seconds; the extension MUST begin automatic reconnection with exponential backoff; at maximum backoff interval, periodic retry continues indefinitely with alert-level logging.
- **FR-026**: The SICCT extension MUST expose per-terminal connection state via the application's health and management interface.
- **FR-027**: A configurable per-operation timeout MUST be enforced for SICCT APDU exchanges; timed-out operations MUST fail with a clear error without leaving the connection in an undefined state.
- **FR-028**: P12 paths, PKCS#11 library paths, PC/SC reader names, key aliases, PINs, and passwords MUST be expressible in the application's standard configuration format with secrets injection support. SICCT terminal host/port records MUST NOT be stored in the config file; they are managed exclusively via the database and management console (see FR-096–FR-099).

**FR-030 — FR-039: Encryption Service**

- **FR-030**: The system MUST expose an `EncryptionService` SOAP endpoint conforming to `consumer/EncryptionService.wsdl` (v3.0.1 from gematik/api-telematik@ebk_6.0.3, namespace prefix CRYPTW) implementing `EncryptDocument` and `DecryptDocument`. Every response MUST validate against `consumer/EncryptionService.xsd` and `consumer/ConsumerCommon.xsd`.
- **FR-031**: `EncryptDocument` MUST perform hybrid encryption: XMLEnc (`http://www.w3.org/TR/xmlenc-core/`) for XML documents; CMS (`urn:ietf:rfc:5652`) for binary documents; symmetric key wrapped asymmetrically per recipient certificate.
- **FR-032**: `EncryptDocument` MUST validate all recipient certificates via `PL_TUC_PKI_VERIFY_CERTIFICATE` before encrypting; expired or revoked certificates MUST be rejected.
- **FR-033**: When ECC-preferred mode is active, `EncryptDocument` MUST use ECC certificates exclusively (RSA MUST NOT be used when both exist for a recipient) per TIP1-A_6984-03.
- **FR-034**: `DecryptDocument` MUST determine the cryptographic algorithm from the hybrid key in the encrypted document; the decryption private key MUST be accessed exclusively via the `CryptoProvider` interface; the key MUST NOT leave its configured source boundary.

**FR-040 — FR-049: Signature Service**

- **FR-040**: The system MUST expose a `SignatureService` SOAP endpoint conforming to `consumer/SignatureService.wsdl` (v3.2.1 from gematik/api-telematik@ebk_6.0.3, namespace prefix SIGW) implementing `SignDocument`, `SignPlain`, `VerifyDocument`, and `ExternalAuthenticate`. Every response MUST validate against `consumer/SignatureService.xsd` and `consumer/ConsumerCommon.xsd`.
- **FR-041**: `SignDocument` MUST create CMS nonQES signatures per RFC 5652 (CAdES-BES); all signing MUST be performed via the `CryptoProvider` interface.
- **FR-042**: `SignDocument` MUST use exclusively ECC (`PrK.HCI.OSIG.E256`) when ECC-preferred mode is active per A_17525-04; RSA (`PrK.HCI.OSIG.R2048`) MUST NOT be selected in ECC-preferred mode.
- **FR-043**: `SignPlain` MUST create an ECDSA signature over a binary payload per BSI-TR-03111 §4.2.1; only `PrK.HCI.OSIG.E256` is permitted per A_27048-01.
- **FR-044**: `VerifyDocument` MUST verify CMS signatures and return `HighLevelResult` VALID, INVALID, or INCONCLUSIVE with the assumed signing timestamp type per A_17526-03.
- **FR-045**: `ExternalAuthenticate` MUST sign a binary hash (≤ 512 bits) using ECDSA or PKCS#1 as determined by `dss:SignatureType`; for SM-B usage, only `PrK.HCI.AUT` in `DF.ESIGN` is permitted per A_17578-04.
- **FR-046**: A signature verification report (`Signaturpruefungsbericht_JJJJMMTT_hhmm`) MAY be attached when verification is performed on KOM-LE messages per A_23999.

**FR-050 — FR-059: Certificate Service**

- **FR-050**: The system MUST expose a `CertificateService` SOAP endpoint conforming to `consumer/CertificateService.wsdl` (v3.0.1 from gematik/api-telematik@ebk_6.0.3, namespace prefix CERTW) implementing `ReadCertificate` and `VerifyCertificate`. Every response MUST validate against `consumer/CertificateService.xsd`, `consumer/CertificateServiceCommon.xsd`, and `consumer/ConsumerCommon.xsd`.
- **FR-051**: `ReadCertificate` MUST read X.509 certificates (C.AUT or C.OSIG) from the SM-B identity (SM-B/KTR or SM-B/ORG) via the `CryptoProvider` interface; reading eGK or HBAx certificates MUST be rejected with error 4090 per A_24782-02.
- **FR-052**: `ReadCertificate` MUST support `Crypt=RSA` and `Crypt=ECC` (default ECC); the corresponding card object (`EF.C.HCI.AUT`, `EF.C.HCI.AUT.E256`, `EF.C.HCI.OSIG.R2048`, `EF.C.HCI.OSIG.E256`) MUST be selected.
- **FR-053**: `VerifyCertificate` MUST check X.509 certificates against the TI PKI via OCSP (`TOLERATE_OCSP_FAILURE=yes`) and return VALID, INVALID, or INCONCLUSIVE with role OIDs per A_17429-02.

**FR-060 — FR-069: LDAP Proxy**

- **FR-060**: The system MUST expose an LDAPv3 interface supporting Bind, Unbind, Search, and Abandon operations per RFC 4511 per A_17343.
- **FR-061**: Any LDAPv3 operation other than the four permitted (Bind, Unbind, Search, Abandon) MUST be rejected with error 53 (`unwillingToPerform`) per A_17341-01.
- **FR-062**: The VZD endpoint MUST be resolved at runtime via DNS-SD (`_ldap._tcp.vzd.<TI_DNS_TOP_LEVEL_DOMAIN>`) over the TI network; LDAP requests MUST be forwarded to the resolved VZD address over LDAPS (port 636, TLS mandatory) per TUC_KON_290 (TIP1-A_5517-03). LDAP port 389 MUST only be offered to clients when the system's TLS-mandatory flag is disabled.
- **FR-063**: The outbound LDAPS connection to VZD MUST validate the server certificate via `TUC_PKI_018` (policy `oid_zd_tls_s`, ExtKeyUsage `serverAuth`, OCSP); a certificate that fails validation MUST cause the LDAP session to be torn down and the client to receive an error; offline OCSP responses MUST NOT be accepted per TIP1-A_5517-03.

**FR-070 — FR-089: KOM-LE Client Module**

- **FR-070**: The KOM-LE client module MUST be based on a fork of the openkim open-source library (https://github.com/sberg-net/openkim). The project MUST maintain an organizational fork and integrate it as a git submodule; all TI-specific adaptations MUST be made in the fork.
- **FR-071**: The git submodule reference MUST be pinned to a specific commit in the organizational fork; updates MUST follow the standard release process and be auditable in git history.
- **FR-072**: The organizational fork MUST be synchronized with upstream openkim releases; a process MUST exist to review and merge upstream changes within 60 days of each upstream release.
- **FR-073**: The KOM-LE module MUST expose SMTP and POP3 interfaces to client mail applications.
- **FR-074**: The KOM-LE module MUST synchronize time via `PL_TUC_NET_SYNC_TIME` with the TI platform time service per A_17298.
- **FR-075**: Outgoing messages MUST be encrypted for all RCPT recipients and the sender using `C.HCI.ENC` (institution) or `C.HP.ENC` (practitioner) certificates; encryption MUST be performed via the `CryptoProvider` interface.
- **FR-076**: When ECC-preferred mode is active per A_26447-01, only ECC encryption certificates MUST be used; RSA MUST NOT be selected if both exist for a recipient (KOM-LE-A_2022-01).
- **FR-077**: The SMTP client interface MUST respond per Tab_SMTP_Ant_Init; unauthenticated MAIL/RCPT/DATA attempts MUST receive `530 5.7.0`; maximum message size MUST be at least 35882577 bytes.
- **FR-078**: The ECC-preferred mode MUST be switchable on or off at runtime, including by remote instruction from gematik per A_26448.
- **FR-079**: The KOM-LE module MUST be configurable with `TLS_AUTH_KONNEKTOR`, `KONNEKTOR_TIMEOUT`, and `KONNEKTOR_URI` parameters per A_17299-02.
- **FR-080**: Before sending a KOM-LE message, the module MUST query the VZD for the sender's `komLeData` attribute and warn the sender if the installed client-module version is lower than the directory version per A_22664.

**FR-090 — FR-099: SICCT Card Terminal Integration**

- **FR-090**: The SICCT integration MUST implement the full SICCT V1.3.0 command set over TCP/IP (Ethernet) as a Quarkus CDI extension; per FR-022 to FR-027 above.
- **FR-091**: The extension MUST support SICCT service discovery per SICCT §6.2.3 to automatically register network-visible card terminals.
- **FR-092**: The extension MUST manage CT sessions (INIT CT SESSION, CLOSE CT SESSION) with authentication per the three SICCT user roles (CT Admin, CT Control, CC/CT User). Each terminal MUST track a CORRELATION state (`bekannt` → `zugewiesen` → `gepairt` → `aktiv`) per TUC_KON_053/TUC_KON_050; only terminals in state `aktiv` MUST accept card operations.
- **FR-093**: Card slot states MUST be tracked in real time (empty / card inserted / card ready / card activated / card ejected) and reflected in CardHandle availability.
- **FR-094**: Physical card operations (signing, PIN verification, certificate reading) MUST be performed via SICCT APDU commands; private keys MUST NOT be extracted from the card.
- **FR-095**: Both SMC-B (KTR and ORG variants) and eHBA cards MUST be supported via the SICCT interface.
- **FR-096**: SICCT card terminal configurations (name, host, port, per-terminal timeouts) MUST be stored in the database as persistent records scoped to a tenant; individual terminal entries MUST NOT be read from the application config file. Global SICCT extension settings (worker threads, reconnect watchdog interval) MAY remain in the config file.
- **FR-097**: On application startup, the SICCT extension MUST load all terminal records from the database for each tenant and initiate connections; failure to connect to one terminal MUST NOT prevent startup or connections to other terminals.
- **FR-098**: The management console MUST provide a Card Terminals panel per tenant with: a list of all terminals and their current connection status; the ability to create a terminal record (name, host, port); the ability to update host or port; and the ability to delete a terminal record. Terminal names MUST be unique within a tenant; the system MUST reject duplicates with a human-readable error.
- **FR-099**: All terminal management operations (create, update, delete) MUST take effect immediately without restarting the application; each operation MUST be recorded in the tenant's audit log with the operator identity, timestamp, and changed fields.

**FR-100 — FR-109: Konnektor SOAP Compatibility Interface**

- **FR-100**: The system MUST expose a Konnektor-compatible SOAP interface implementing the operations defined in gemSpec_Kon_V5.27.0 §3.6 via seven services, each pinned to the following WSDL version from gematik/api-telematik@ebk_6.0.3: `AuthSignatureService` (conn/AuthSignatureService_v7_4_1.wsdl, v7.4.1) — `ExternalAuthenticate`; `CardService` (conn/CardService_v8_2_1.wsdl, v8.2.1) — `VerifyPin`, `ChangePin`, `UnblockPin`, `GetPinStatus`, `EnablePin`, `DisablePin`, `SecureSendAPDU`, `StartCardSession`, `StopCardSession`; `CardTerminalService` (conn/CardTerminalService.wsdl, v1.1.0) — `RequestCard`, `EjectCard`; `CertificateService` (conn/CertificateService_v6_0_3.wsdl, v6.0.3) — `CheckCertificateExpiration`, `ReadCardCertificate`, `VerifyCertificate`; `EncryptionService` (conn/EncryptionService_v6_1_1.wsdl, v6.1.1) — `EncryptDocument`, `DecryptDocument`; `EventService` (conn/EventService.wsdl, v7.2.0) — `Subscribe`, `Unsubscribe`, `GetSubscription`, `GetResourceInformation`, `GetCardTerminals`, `GetCards`, `RenewSubscriptions`; `SignatureService` (conn/SignatureService_V7_5_7.wsdl, v7.5.7) — `VerifyDocument`, `SignDocument`, `GetJobNumber`, `StopSignature`, `ActivateComfortSignature`, `DeactivateComfortSignature`, `GetSignatureMode`.
- **FR-101**: All Konnektor SOAP responses MUST be schema-valid against the published Konnektor WSDLs and XSDs.
- **FR-102**: The Konnektor event service (`Subscribe`/notification) MUST deliver card insertion, card removal, and terminal status events in the Konnektor-defined event format.
- **FR-103**: Tenant isolation MUST extend to the Konnektor compatibility interface: `GetCards`, `GetCardTerminals`, and event subscriptions MUST only return resources belonging to the requesting tenant.

**FR-110 — FR-119: HSM & Identity Management**

- **FR-110**: All private key operations (signing, decryption) performed via PKCS#11 MUST execute inside an HSM meeting FIPS 140-2 Level 3 or CC EAL4; private key material MUST NOT leave the HSM except for authorized backup purposes per A_17598.
- **FR-111**: The system MUST manage `CardHandle` identifiers per tenant, mapping handles to key source aliases (P12, PKCS#11 slot, PC/SC card, or SICCT card slot).
- **FR-112**: Both SM-B-ORG and SM-B-KTR identity types MUST be supported.

**FR-120 — FR-129: Network & TI Connectivity**

- **FR-120**: The system MUST establish and maintain a VPN connection to the TI central network.
- **FR-121**: The system MUST operate a recursive caching DNS resolver with a TI DNS forwarder and stub resolver for TI-internal name resolution.
- **FR-122**: Network segmentation MUST be enforced with a default-deny firewall; dynamic routing MUST NOT be used on TI-facing interfaces.

**FR-130 — FR-139: SICCT Terminal Authentication & TLS Security** *(derived from gemSpec_KT_V3.17.0 and gemSpec_Kon_V5.27.0)*

- **FR-130**: Before a SICCT terminal can be used for card operations, the Basis Consumer MUST complete a pairing procedure using the EHEALTH TERMINAL AUTHENTICATE command with variant `CREATE` (P2=01): generate 16 bytes of cryptographically strong randomness as a shared secret (`ShS.KT.AUT`), open a SICCT INIT CT SESSION in Admin role, and send the command with the secret and a human-readable pairing label. The terminal prompts the operator on its display; the operator must confirm. On confirmation, the terminal returns a signature over the secret using its SM-KT identity; the Basis Consumer MUST verify this signature. On success, the terminal state advances to `gepairt → aktiv` per TUC_KON_053 (TIP1-A_4553-02).
- **FR-131**: Every CT session establishment MUST perform a mutual-authentication validation step using EHEALTH TERMINAL AUTHENTICATE variant `VALIDATE` (P2=02): the Basis Consumer generates a random challenge (≥16 bytes), sends it to the terminal, and verifies that the terminal's response equals `SHA-256(challenge ‖ ShS.KT.AUT)`. A validation failure MUST immediately close the TLS connection and mark the terminal DISCONNECTED; error code 4029 MUST be returned per TUC_KON_050 (TIP1-A_4545-04).
- **FR-132**: The Basis Consumer MUST support maintenance pairing using EHEALTH TERMINAL AUTHENTICATE variants `ADD Phase 1` (P2=03) and `ADD Phase 2` (P2=04) when a terminal is reachable over TLS but its stored pairing block references an outdated public key (e.g. after SAK.AUT key rotation). The maintenance pairing MUST be initiated automatically when reconnection fails due to a pairing mismatch per TIP1-A_5011.
- **FR-133**: All TCP/IP connections from the Basis Consumer to SICCT terminals MUST use mutual TLS. The Basis Consumer MUST present a SAK.AUT client certificate (policy OID `1.2.276.0.76.4.113`, KeyUsage `digitalSignature`, ExtKeyUsage `tlsClientAuthentication`) to the terminal per A_22887/A_22890. Only cipher suites approved by gemSpec_Krypt (ECC+AES-GCM or RSA+AES-CBC; DES MUST NOT be used) are permitted per A_22450.
- **FR-134**: The SICCT Quarkus extension MUST enforce the SICCT TLS state machine per A_22456. The four states — `NoSicctTls`, `InvalidClient`, `ClientWithoutPairing`, `ClientWithPairing` — determine which SICCT commands may execute: in `NoSicctTls` no commands are permitted (A_22461); in `InvalidClient` only INIT/CLOSE CT SESSION, GET/SET STATUS, and CT DOWNLOAD commands are permitted (CMD_KT_0004, TIP1-A_3136-01); in `ClientWithoutPairing` EHEALTH TERMINAL AUTHENTICATE is additionally permitted (CMD_KT_0005, TIP1-A_3096-01); full command access requires `ClientWithPairing`.
- **FR-135**: Shared secrets (`ShS.KT.AUT`) MUST be unique per terminal and per tenant; the Basis Consumer MUST not reuse a shared secret across terminals or across tenants. All pairing information (shared secrets and SAK.AUT public keys) MUST be stored in tamper-resistant form; they MUST be deleted when a terminal is decommissioned per TIP1-A_3244. If the terminal reports a duplicate-secret error (SICCT SW `6900`), the system MUST generate a new secret and retry.
- **FR-136**: Loss of TLS connectivity to a terminal MUST be detected within 30 seconds; upon detection, the extension MUST discard all session keys for that terminal and mark it DISCONNECTED before initiating reconnection. No SICCT commands MUST be dispatched to a terminal after TLS loss is detected, per A_19431.
- **FR-137**: The Basis Consumer MUST monitor the expiry date of the gSMC-KT certificate (`C.SMKT.AUT`) returned by each terminal during session establishment. If the certificate expires within 35 days, the system MUST publish an `EC_CardTerminal_gSMC_KT_Certificate_Expires_Soon` event and log a warning containing the terminal name and remaining days per TUC_KON_050.
- **FR-138**: The pairing initiation label sent in the EHEALTH TERMINAL AUTHENTICATE CREATE command MUST follow the format `KT:<MAC> MIT KON:<hostname> PAIREN OK?` as specified in TIP1-A_4553-02, where `<MAC>` is the terminal MAC address and `<hostname>` is the Basis Consumer's configured identity hostname. The terminal displays this text and waits up to 10 minutes for operator confirmation; a decline (operator cancels) or timeout MUST cause the pairing to be aborted and error 4041 reported.

**FR-140 — FR-149: Card-to-Card Authentication & Advanced Card Operations**

- **FR-140**: The Basis Consumer MUST implement card-to-card authentication (TUC_KON_005) to support scenarios requiring a trusted channel between two cards (e.g. remote-PIN). The `authMode` parameter selects between unilateral (`einseitig`), mutual (`gegenseitig`), and mutual with trusted channel (`gegenseitig+TC`). The source card (gSMC-KT or SM-B) MUST have its PIN verified before a mutual authentication is initiated (TIP1-A_4572).
- **FR-141**: The Basis Consumer MUST support remote-PIN entry per TIP1-A_5012 for HBA and SMC-B cards in SICCT terminals, using the gSMC-KT in the terminal as an intermediary. Remote-PIN uses TUC_KON_005 with `authMode=gegenseitig+TC` to establish a Trusted Channel (session key) between the gSMC-KT and the target card; the PIN is encrypted by the gSMC-KT with the session key and transmitted in the PIN command on the same logical channel as the session-key agreement. Remote-PIN display on the terminal MUST indicate the PIN entry is in progress using the terminal's trusted display (TIP1-A_2979). Remote-PIN is supported for HBA.PIN.CH/PUK.CH, HBA.PIN.QES/PUK.QES, and SMC-B.PIN.SMC/PUK.SMC.
- **FR-142**: The Basis Consumer MUST support secure card sessions (TUC_KON_223 "Starte Kartensitzung") enabling integrity- and authenticity-protected APDU sequences to a card. A secure session MUST use a sequence counter; APDUs with expected status words differing from received status words MUST abort the session. The card MUST be exclusively locked for the session duration (A_25895).
- **FR-143**: `SecureSendAPDU` (TUC_KON_208) MUST transmit protected APDUs within an established secure session; the Basis Consumer MUST validate each response APDU's status word and integrity before returning it to the caller per A_26069-01.
- **FR-144**: `VerifyPin` MUST determine whether to use local or remote-PIN based on the target card's type and the terminal's capabilities. Results MUST be one of: OK, ERROR, REJECTED (with remaining tries), BLOCKED. The operation MUST emit `VERIFY_STARTED` and `VERIFY_FINISHED` events per TIP1-A_4567. PIN material MUST NOT appear in any log or audit entry.
- **FR-145**: `ChangePin`, `UnblockPin`, `GetPinStatus`, `EnablePin`, and `DisablePin` MUST be exposed on the Konnektor SOAP interface and backed by the corresponding TUCs (TUC_KON_019, TUC_KON_021, TUC_KON_022, TUC_KON_027). PIN material MUST NOT appear in any log or network trace.

**FR-150 — FR-159: SOAP Interface Compliance**

- **FR-150**: Every SOAP operation MUST accept and validate the Konnektor `AufrufKontext` context parameters as defined in gemSpec_Kon_V5.27.0 TAB_KON_511-01: `mandantId`, `clientSystemId`, and `workplaceId` are mandatory for all operations; `userId` is mandatory for HBA-based operations (signing with eHBA, ExternalAuthenticate with HBAx) and optional for SM-B operations. A request missing a mandatory context parameter MUST be rejected with error code 4021 per TUC_KON_000 (TIP1-A_4524-03).
- **FR-151**: SOAP endpoints for `SignatureService` and `EncryptionService` MUST support MTOM (Message Transmission Optimization Mechanism) for efficient binary payload transfer, mandatory from PTV5 per TIP1-A_5694-03 and A_15786. Non-MTOM requests for these services MUST still be accepted (graceful fallback).
- **FR-152**: `ExternalAuthenticate` MUST accept a binary hash of at most 512 bits (64 bytes). The algorithm (PKCS#1 v1.5, RSASSA-PSS, or ECDSA) MUST be determined by the `dss:SignatureType` parameter; the hash algorithm MUST be inferred from the hash byte length (32 bytes → SHA-256, 48 bytes → SHA-384, 64 bytes → SHA-512). For SM-B usage, `userId` MUST be ignored; for HBAx usage, `userId` is mandatory and must match a card with an active session. Only `PrK.HCI.AUT` in `DF.ESIGN` is permitted for SM-B per A_17578-04 (TIP1-A_5439-02).
- **FR-153**: All SOAP error responses MUST be returned as a gematik `SOAP-Fault` containing a `Status` element with `Result=Fatal` or `Result=Error` and a `Trace` chain; the innermost (last) `Trace` element MUST be a gematik-specified error code; the outermost MUST provide the caller-facing message per TIP1-A_5058-01 (A_14159). The technical fallback error code is 4001.
- **FR-154**: The SOAP interface MUST support parallel invocation by multiple client systems within the same tenant without serialization of unrelated requests per TIP1-A_5401. Request-level card locking (CardHandle exclusivity) MUST be enforced only for operations that actually access the physical card, not for the entire endpoint.
- **FR-155**: Client-system authentication to the SOAP interface MUST be enforced via either HTTP Basic Auth over TLS (credential must be in the tenant's configured allow-list `ANCL_CUP_LIST`) or X.509 client certificate (must be in `ANCL_CCERT_LIST`, mathematically valid, not expired) per TIP1-A_4516. Unauthenticated connections MUST be rejected with error 4204. TLS is mandatory for all SOAP endpoints; non-TLS connections MUST be rejected when `ANCL_TLS_MANDATORY=Enabled` per TIP1-A_4515.

- **FR-156**: All ten SOAP service endpoints — three consumer interface services (FR-030, FR-040, FR-050) and seven konnektor compatibility services (FR-100) — MUST expose their complete WSDL document (including all imported XSD schemas) at a discoverable URL by appending `?wsdl` to the service endpoint address. The returned WSDL MUST contain the correct live endpoint address matching the server's own hostname and port; a client fetching `?wsdl` MUST receive a self-consistent, resolvable import chain.

- **FR-157**: Konnektor compatibility operations that are deferred to a future release version MUST return a gematik SOAP fault with error code 7200 (service not implemented) rather than returning a malformed response or failing silently. The deferred operation set for v1 is: `ActivateComfortSignature`, `DeactivateComfortSignature`, `GetSignatureMode` (SignatureService v7.5.7); `Subscribe`, `Unsubscribe`, `GetSubscription`, `RenewSubscriptions` (EventService v7.2.0 — synchronous `GetCards`, `GetCardTerminals`, `GetResourceInformation` are in scope). All other operations listed in FR-100 MUST be fully implemented.

**FR-160 — FR-177: SICCT ASN.1 Schema Extension and Code Generation**

- **FR-160**: The `SICCT.asn1` file MUST be extended to add `ehealth-terminal-authenticate` (INS value `AA` hex, decimal 170) to the `SicctInstruction` ENUMERATED type, making it a valid instruction alongside the existing SICCT command set.

- **FR-161**: The extended schema MUST define an `EhealthAuthP2` ENUMERATED type with four named values: `create` (P2=01), `validate` (P2=02), `add-phase-1` (P2=03), `add-phase-2` (P2=04), per gemSpec_KT_V3.17.0 §3.7.2 (TIP1-A_3125-03, TIP1-A_3126, TIP1-A_3127, TIP1-A_3128).

- **FR-162**: The extended schema MUST define a `SharedSecretDO` type (OCTET STRING, size constraint: exactly 16 bytes) representing the connector-generated random shared secret `ShS.KT.AUT` sent in the CREATE variant command data.

- **FR-163**: The extended schema MUST define a `SharedSecretChallengeDO` type (OCTET STRING, minimum 16 bytes) representing the connector-generated random challenge sent in the VALIDATE variant command data.

- **FR-164**: The extended schema MUST define a `SharedSecretResponseDO` type (OCTET STRING, exactly 32 bytes) representing the SHA-256 hash response sent in the ADD Phase 2 variant command data and received as response to VALIDATE.

- **FR-165**: The extended schema MUST define an `EhealthAuthCreateRequest` type (SEQUENCE) containing a `SharedSecretDO` (mandatory) and an `APPL-DO` (mandatory) — the shared secret and the display label for operator confirmation.

- **FR-166**: The extended schema MUST define an `EhealthAuthValidateRequest` type (SEQUENCE) containing a `SharedSecretChallengeDO` (mandatory).

- **FR-167**: The extended schema MUST define an `EhealthAuthAddPhase2Request` type (SEQUENCE) containing a `SharedSecretResponseDO` (mandatory).

- **FR-168**: The extended schema MUST define an `EhealthAuthCreateResponse` type representing the terminal's SM-KT signature over the shared secret: an OCTET STRING constrained to 64 bytes (ECDSA P-256) or 100 bytes (RSA-2048), per gemSpec_Krypt#A_17090.

- **FR-169**: The extended schema MUST define an `EhealthAuthAddPhase1Response` type (OCTET STRING, minimum 16 bytes) representing the terminal-generated random challenge returned in the ADD Phase 1 response.

- **FR-170**: Java binding classes MUST be generated from the complete `SICCT.asn1` schema (including the EHEALTH AUTHENTICATE extension) using the beanit jASN1 library code generator. One Java class MUST be generated for each named ASN.1 type in the module.

- **FR-171**: All ASN.1 size and value constraints (e.g. `SIZE(16)` for `SharedSecretDO`, `SIZE(2)` for `FuNumber`) MUST be enforced during encoding; a constraint violation MUST cause the encoder to throw a typed exception before writing any bytes to the output stream.

- **FR-172**: The generated classes MUST support BER (Basic Encoding Rules) encoding and decoding. DER encoding MAY additionally be supported; CER is not required.

- **FR-173**: The code generation step MUST be integrated into the Maven build lifecycle of the `sicct-lib` module so that classes are (re)generated automatically during the `generate-sources` phase. Generated source files MUST NOT be committed to version control.

- **FR-174**: The generated Java package name MUST be `de.gematik.basisconsumer.sicct.asn1`; all generated classes MUST reside in this package or a sub-package.

- **FR-175**: Every new ASN.1 type added for EHEALTH AUTHENTICATE MUST include an inline comment referencing the normative source (gematik Afo ID or gemSpec_KT section) from which it is derived.

- **FR-176**: The extended `SICCT.asn1` MUST remain parseable by the beanit parser with zero errors. Existing type definitions in the file (TEIL A–D) MUST NOT be modified in any way that breaks backward compatibility with the existing SICCT command set.

- **FR-177**: The EHEALTH AUTHENTICATE types MUST be placed in a clearly delimited section designated `TEIL E: EHEALTH TERMINAL AUTHENTICATE (gemSpec_KT_V3.17.0)`, matching the style of the existing TEIL A/B/C/D section headers.

---

### Key Entities

- **Tenant**: An organizational unit (healthcare institution or practice) with its own Kubernetes namespace, one or more SM-B identities, isolated configuration, and isolated audit logs.
- **CardHandle**: A runtime reference to a specific cryptographic identity — either an HSM-backed virtual card or a physical card in a SICCT terminal slot or PC/SC reader. Scoped to a tenant. Expires with the session or on card removal.
- **KeyAlias**: A unique string in format `<source-type>/<user-defined-name>` (e.g., `pkcs11/utimaco-slot0`, `sicct/terminal-1-slot0`) identifying a private key within the unified CryptoProvider. Globally unique per tenant instance.
- **Identity (HSM or Card)**: A cryptographic identity represented by X.509 certificates and private keys (SM-B-ORG, SM-B-KTR, eHBA). Private keys reside exclusively in their configured key source.
- **CardTerminalRecord**: A persisted SICCT terminal configuration. Fields: unique name (per tenant), host, port, optional per-terminal timeouts, tenant association, creation and update timestamps. Stored in the database; managed exclusively via the management console.
- **SICCT Terminal**: The runtime representation of a connected SICCT card terminal. Backed by a CardTerminalRecord. Managed by the SICCT Quarkus extension. Has one or more card slots and a connection state (CONNECTING / CONNECTED / DISCONNECTED / FAILED).
- **CT Session**: An authenticated TCP/IP connection between the SICCT extension and a SICCT terminal. Has a role (CT Admin, CT Control), session identifier, and lifecycle (CONNECTING → CONNECTED → RECONNECTING → DISCONNECTED). Session establishment always includes EHEALTH TERMINAL AUTHENTICATE VALIDATE to prove possession of the shared secret.
- **CT Pairing Block**: Persistent storage entry on the terminal side holding the connector's SAK.AUT public key and the shared secret hash. Created during EHEALTH TERMINAL AUTHENTICATE CREATE. Referenced by the terminal during VALIDATE to compute the expected SHA-256 response. Unique per connector identity; deleted on terminal decommission.
- **SAK.AUT Certificate**: The Basis Consumer's SICCT TLS client certificate. Policy OID `1.2.276.0.76.4.113`. Presented to the terminal during mutual TLS handshake. The corresponding public key is stored in the terminal's CT Pairing Block during pairing.
- **KOM-LE Message**: An S/MIME-encrypted, CMS-signed email message transmitted via the openkim-based KOM-LE client module. Encrypted for all recipients and the sender using TI certificates.
- **SOAP Endpoint**: A versioned Apache CXF web service endpoint. One set of endpoints per tenant namespace.
- **Audit Log**: An append-only, tamper-evident log of all security-relevant events (cryptographic operations, authentication attempts, configuration changes) per tenant.
- **Consumer Interface**: The three SOAP services sourced from `consumer/` WSDLs (SignatureService v3.2.1, EncryptionService v3.0.1, CertificateService v3.0.1). The canonical, forward-looking interface for new client development.
- **Konnektor Compatibility Interface**: The seven SOAP services sourced from `conn/` WSDLs. A backward-compatible facade that enables existing Konnektor clients to use the Basis Consumer as a drop-in replacement.
- **SharedSecretDO**: A 16-byte random value generated by the Basis Consumer. Used as the shared secret (`ShS.KT.AUT`) in terminal pairing (FR-162). Exactly 16 bytes — never fewer, never more.
- **SharedSecretChallengeDO**: A random value of at least 16 bytes generated by the Basis Consumer for the VALIDATE command (FR-163). The terminal appends the stored `ShS.KT.AUT` and returns `SHA-256(challenge ‖ ShS.KT.AUT)`.
- **SharedSecretResponseDO**: The 32-byte SHA-256 hash returned by the terminal (VALIDATE response) or sent by the Basis Consumer (ADD Phase 2 request). Always exactly 32 bytes (FR-164).
- **EhealthAuthCreateRequest**: The command data for the CREATE variant: `SharedSecretDO` + `APPL-DO` operator display label (FR-165).
- **EhealthAuthCreateResponse**: The terminal's SM-KT signature over the shared secret — 64 bytes ECDSA or 100 bytes RSA — proving the terminal's identity (FR-168).
- **Generated Java Class**: A beanit-generated Java class with typed fields, `encode(OutputStream)`, and static `decode(InputStream)` methods representing one ASN.1 type from `SICCT.asn1`.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new tenant is fully operational (all SOAP services responding, key source provisioned, TI network connectivity established) within 10 minutes of namespace provisioning initiation.
- **SC-002**: All Basis Consumer SOAP operations complete within 2 seconds under normal operating conditions for documents up to 1 MB (excludes external hardware latency).
- **SC-003**: The system handles at least 50 concurrent tenants on a single deployment cluster without measurable latency degradation between tenants.
- **SC-004**: Zero private key material ever appears outside its configured key source boundary (HSM hardware, card chip, or JVM-local P12 instance); verifiable by automated security test confirming no key bytes in logs, heap dumps, or network traces.
- **SC-005**: Cross-tenant isolation: 100% of cross-tenant access attempts are rejected at the authorization layer with no data leakage, verifiable by automated penetration tests.
- **SC-006**: All four key source types (P12, PKCS#11 HSM, PC/SC, SICCT) can be active and serving signing requests concurrently in a single integration test run without any request failing due to provider conflicts.
- **SC-007**: A SICCT terminal TCP/IP disconnection is detected within 10 seconds; reconnection is established within 15 seconds; operations resume without manual intervention.
- **SC-008**: A certified Primärsystem connects to the Konnektor compatibility endpoint and completes its standard workflow without configuration changes; all SOAP responses pass schema validation.
- **SC-009**: KOM-LE messages up to 35 MB are encrypted and submitted to the MTA within 30 seconds of receipt from the SMTP client.
- **SC-010**: System availability is at least 99.9% per tenant namespace per calendar month, excluding scheduled maintenance.
- **SC-011**: All normative gematik Afos from gemSpec_Basis_Consumer_V1.12.1 have a corresponding unit test (named `test_<AFO-ID>_...`) and a dedicated git commit (`[TICKET] [AFO-ID] <summary>`), verifiable via automated traceability reporting.
- **SC-012**: A new SICCT terminal added via the management console reaches CONNECTED status within 15 seconds without any application restart.
- **SC-013**: All SICCT terminals that were registered before an application restart are automatically reconnected within 60 seconds of startup, with no operator intervention.
- **SC-014**: 100% of ASN.1 types defined in `SICCT.asn1` (including the EHEALTH AUTHENTICATE extension in TEIL E) have a corresponding generated Java class; verified by automated build output comparison against the type list in the schema.
- **SC-015**: A round-trip test (encode → decode) for every generated class passes with identical field values for at least one representative valid value per type, verified by the `sicct-lib` unit test suite.
- **SC-016**: All size-constraint violations (e.g. `SharedSecretDO` ≠ 16 bytes, `SharedSecretResponseDO` ≠ 32 bytes) are caught during encoding and reported as typed exceptions; 100% of such violation cases are verified by unit tests.
- **SC-017**: The Maven `generate-sources` phase for `sicct-lib` completes (regenerating all Java classes from scratch) in under 30 seconds on the CI environment.
- **SC-018**: The extended `SICCT.asn1` produces zero parser errors when processed by the beanit code generator, verified by the `generate-sources` phase exit code.
- **SC-019**: All four EHEALTH AUTHENTICATE command variants (CREATE, VALIDATE, ADD Phase 1, ADD Phase 2) can be encoded from Java objects and decoded back with byte-level verification against reference encodings derived from gemSpec_KT_V3.17.0 §3.7.2.
- **SC-020**: All consumer interface operation responses (FR-030, FR-040, FR-050) validate against the corresponding gematik XSD schemas from gematik/api-telematik@ebk_6.0.3 with zero schema validation errors in the integration test suite.
- **SC-021**: All supported konnektor compatibility service responses (FR-100, excluding deferred operations per FR-157) validate against the corresponding `conn/` XSD schemas with zero schema validation errors.
- **SC-022**: Both the consumer and konnektor compatibility interfaces sustain 200 concurrent SOAP requests per tenant without request loss or cross-tenant data leakage, verified by load test.
- **SC-023**: WSDL documents are returned at `?wsdl` URLs for all 10 SOAP endpoints (3 consumer + 7 konnektor) and each returned WSDL passes XML schema validation and contains the correct live endpoint address.

---

## Assumptions

- Each tenant is pre-assigned a unique Kubernetes namespace before the Basis Consumer is deployed; namespace creation is handled by external cluster administration tooling.
- The HSM infrastructure is available as a shared service accessible from all tenant namespaces via authenticated PKCS#11 API; HSM provisioning per tenant is an administrative prerequisite.
- Physical SICCT card terminals are on the same IP-reachable network segment as the tenant's Basis Consumer namespace, or are routed through a dedicated terminal aggregator.
- SICCT card terminal configurations (host, port, name) are managed exclusively through the Hawtio management console and persisted in the database; the application config file MUST NOT contain individual terminal host/port entries. The `quarkus.sicct.terminals[*]` config entries from prior contracts are retired.
- Per-terminal timeout values fall back to global defaults defined in the config file if not explicitly set per-record; individual DB overrides take precedence.
- The system must support at least 20 SICCT terminals per tenant without degradation; historical terminal records are not retained after deletion.
- The TI VPN access point (VPN-Zugangsdienst) is pre-configured and reachable before the Basis Consumer starts.
- The KOM-LE client module uses an existing MTA (Mail Transfer Agent) in the tenant's environment; the Basis Consumer does not operate its own MTA.
- The openkim fork must be created and the submodule reference established as a prerequisite before KOM-LE implementation begins.
- eHBA cards are accessed exclusively through SICCT card terminals; direct HSM-virtualization of eHBA keys is out of scope for v1.
- The Konnektor compatibility interface targets gemSpec_Kon_V5.27.0 (Stand: 21.01.2026); backward compatibility with older Konnektor versions is not guaranteed.
- QES (Qualified Electronic Signatures) requiring a qualified trust service provider are out of scope; only nonQES signatures are implemented in this version.
- PC/SC support requires the host OS to provide a PC/SC middleware layer (pcscd on Linux); the Basis Consumer does not bundle or manage PC/SC middleware.
- SoftHSM2 (software PKCS#11 emulator) is acceptable as a CI substitute for a physical HSM; production deployments MUST use a FIPS 140-2 Level 3 or CC EAL4 certified HSM.
- Remote-PIN entry (PIN entered by the healthcare professional on the SICCT terminal's secure PIN pad, transmitted encrypted via gSMC-KT trusted channel) IS in scope for HBA and SMC-B cards in SICCT terminals per TIP1-A_5012 and FR-141. Injection-via-config PIN is supported only for non-interactive automated scenarios (e.g. SM-B in CI). Direct PIN pad entry on locally attached PC/SC readers (without terminal mediation) is out of scope for v1.
- The administrative Hawtio web console is used exclusively by authorized operators; it does not expose any SOAP or TI cryptographic services directly to tenants.
- The `CryptoProvider` is an internal CDI interface; it has no direct external API surface exposed to clients.
- SOAP interfaces use SOAP 1.1 exclusively, matching the gematik WSDL definitions; SOAP 1.2 requests receive HTTP 400.
- Consumer interface endpoints are served under a distinct URL path from konnektor compatibility endpoints; the path convention is an implementation detail documented in the plan.
- WSDL version pinning: the consumer interface uses WSDLs exactly as found at gematik/api-telematik@ebk_6.0.3; earlier versions of the same services are not exposed.
- MTOM encoding for binary payloads is optional for clients but recommended; the server MUST accept both MTOM and base64-inline document encoding.
- If two SOAP operations request exclusive access to the same card simultaneously, the second request blocks up to 30 seconds rather than failing immediately; if the card remains locked after 30 seconds, error code 4019 is returned.
- The beanit jASN1 library supports `IMPLICIT TAGS` at the module level, the `CHOICE` construct, and application-class and context-class tags as used in `SICCT.asn1`; any unsupported construct will require a workaround noted in the research phase.
- The beanit library generates Java source code during the Maven build (not at runtime); no reflection or runtime schema loading is required.
- The EHEALTH AUTHENTICATE signature in the CREATE response is a raw OCTET STRING (not a DER-encoded SEQUENCE): ECDSA uses plain r‖s encoding per BSI TR-03111 §5.2.1; RSA uses modular-exponentiation output per gemSpec_Krypt#A_17090.
- Exact tag byte values for `SharedSecretDO`, `SharedSecretChallengeDO`, and `SharedSecretResponseDO` will be determined during the research phase from the full SICCT V1.3.0 specification and gemSpec_KT_V3.17.0; the spec uses descriptive type names rather than tag hex values.
- The existing `SICCT.asn1` structure (TEIL A/B/C/D) is preserved unchanged; all EHEALTH AUTHENTICATE additions are placed in a new TEIL E section appended to the file.
- Generated Java classes target Java 21 and are compatible with the Quarkus JVM (non-native) build target of `sicct-lib`.
