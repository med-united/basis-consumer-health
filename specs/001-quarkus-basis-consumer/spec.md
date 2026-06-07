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
- [med-united/api-telematik @ ebk_6.0.3](https://github.com/med-united/api-telematik/tree/ebk_6.0.3) (fork of [gematik/api-telematik](https://github.com/gematik/api-telematik/tree/ebk_6.0.3)) — canonical WSDL and XSD definitions for `conn/` and `consumer/` service interfaces; integrated as `api-telematik` git submodule

**Architectural Mandates** (user-specified, non-negotiable):
- Runtime: Quarkus 3.x on Java 21, multi-tenant via one Kubernetes namespace per tenant
- Each component MUST be an independent Maven module with its own folder. The mandatory module structure is:
  - **`api-telematik`** — git submodule to https://github.com/med-united/api-telematik (fork of gematik/api-telematik); provides all canonical WSDL/XSD sources for both SOAP server modules
  - **`crypto-lib`** — depends on `de.gematik.pki/gemLibPki@4.0.2` (includes Bouncy Castle); downloads and manages the TI Trust Service List (TSL); parses certificates and extracts attributes (registration number, SMC-B/eHBA type); exposes CDI injectable beans
  - **`sicct-lib`** — depends on `crypto-lib` and `javax.smartcardio`; contains `SICCT.asn1` for jASN1 code generation (classes generated during Maven `generate-sources`); implements all EHEALTH AUTHENTICATE action variants (CREATE, VALIDATE, ADD Phase 1/2); implements JCE `KeyStoreSPI` for SMC-B and eHBA including required APDU generation; exposes CDI injectable beans. Contains JPA beans for lifecycle managemenet of card terminals.
  - **`quarkus-sicct-extension`** — depends on `sicct-lib`, Quarkus, and Netty; Quarkus extension that manages the lifecycle of SICCT card terminals including UDP-based service discovery and Netty TCP/IP client pool; binds to Quarkus start/stop lifecycle events; uses CDI
  - **`crypto-services-lib`** — depends on `crypto-lib` only (MUST NOT have a dependency on `sicct-lib`); implements `SignatureService`, `CertificateService`, and `EncryptionService` independent of any middleware; uses CDI
  - **`quarkus-ldap-proxy-server-extension`** — depends on Quarkus and Netty; Quarkus extension that proxies LDAPv3 queries to the TI Verzeichnisdienst (VZD)
  - **`openkim-server`** — git submodule to https://github.com/sberg-net/openkim; implements KOM-LE/KIM secure messaging (SMTP + POP3 interfaces); deployed as a standalone Spring Boot pod per tenant Kubernetes namespace — NOT bundled inside the Quarkus basis-consumer-server pod
  - **`consumer-soap-server`** — implements with Apache CXF under URL `/consumer` the `CertificateService`, `EncryptionService`, and `SignatureService` using WSDL definitions from `api-telematik/consumer/`
  - **`konnektor-soap-server`** — implements with Apache CXF under URL `/conn` the `AuthSignatureService`, `CardService`, `CardTerminalService`, `CertificateService`, `EncryptionService`, `EventService`, and `SignatureService` using WSDL definitions from `api-telematik/conn/`; also provides a `connector.sds` file based on `ServiceDirectory.xsd`
  - **`quarkus-server`** — this bundles all applications and it is the runnable quarkus application of the project
- ASN.1 code generation for the SICCT schema MUST use the [beanit jASN1 library](https://www.beanit.com/asn1/)
- SOAP web services MUST be implemented using Apache CXF
- Frontend/management console: Hawtio — embedded inside the Quarkus basis-consumer-server pod (NOT a shared sidecar or separate deployment); one Hawtio instance per tenant pod
- Health monitoring: SmallRye Health — embedded inside the Quarkus basis-consumer-server pod; exposes `/health/ready` and `/health/live` endpoints consumed by Kubernetes liveness/readiness probes
- Persistence: JPA beans with JavaDB

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Tenant Onboarding & Isolation (Priority: P1)

A healthcare organization (Leistungserbringer or institution) is registered as a new tenant. Their identity (SMC-B) is provisioned in the system and all cryptographic operations performed in their name are fully isolated from every other tenant. The tenant's identity materials, configuration, and audit logs are never visible to or accessible by other tenants.

This user story is not implemented in this project but in a provider project that contains kubernetes artifacts.

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

**Independent Test**: Configure one P12 key source, one SoftHSM2-backed PKCS#11 source, one virtual PC/SC reader, and one mock SICCT terminal — all simultaneously. Submit one signing request per source in parallel. All four complete successfully and produce distinct, verifiable signatures. Removing one source from configuration takes effect within 5 seconds without restarting the application; the other three continue serving requests.

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

### User Story 4 — Document Signing — nonQES and QES (Priority: P1)

A client application submits one or more documents to the Basis Consumer's Signature Service. The Basis Consumer creates electronic signatures for each document using the private key of the tenant's configured key source. Three signature container formats are supported: CAdES (CMS-based, for binary/generic documents), PAdES (PDF-integrated), and XAdES (XML-embedded). Both non-qualified (nonQES, using the SM-B identity key `PrK.HCI.OSIG`) and qualified (QES, using the HBA key `PrK.HP.QES`) signature levels are supported. A corresponding verification operation checks a signed document and returns a binary verdict (VALID / INVALID / INCONCLUSIVE).

**Why this priority**: Document signatures are mandatory for KOM-LE message signing, TI interoperability, and legally-binding QES use cases. Required by A_17523 and A_17525-04. QES is required for legally equivalent handwritten signatures under eIDAS Regulation (EU) No 910/2014.

**Independent Test**: Sign a test document via `SignDocument` with CAdES format, then verify via `VerifyDocument` without modification. Result must be VALID. Alter one byte; verify again — result must be INVALID. Repeat for PAdES and XAdES formats.

**Acceptance Scenarios**:

1. **Given** a client provides a CardHandle and one or more `SignRequest` elements with `SignatureType=CAdES`, **When** `SignDocument` is called, **Then** a CMS/CAdES signature (RFC 5652, CAdES-BES baseline) is returned for each `SignRequest`, associated via `RequestID`.
2. **Given** a client provides a CardHandle and a PDF `SignRequest` with `SignatureType=PAdES`, **When** `SignDocument` is called, **Then** a PDF with an embedded PAdES-B-B signature compliant with ETSI EN 319 102-1 is returned.
3. **Given** a client provides a CardHandle and an XML `SignRequest` with `SignatureType=XAdES`, **When** `SignDocument` is called, **Then** an XAdES-B-B signature (enveloped or detached per request) compliant with ETSI EN 319 132-1 is returned.
4. **Given** a signed document is submitted to `VerifyDocument`, **When** verification runs, **Then** the response contains `SIG:VerificationResult` with `HighLevelResult` equal to VALID, INVALID, or INCONCLUSIVE.
5. **Given** a binary hash is submitted to `SignPlain`, **When** the operation executes, **Then** an ECDSA signature is returned using `PrK.HCI.OSIG.E256` exclusively when ECC-preferred mode is active; the RSA key reference MUST NOT be used in that mode.
6. **Given** a binary challenge of at most 256 bits is submitted to `ExternalAuthenticate` with a SM-B CardHandle, **When** the operation executes, **Then** the ECDSA signature over the hash is returned using `PrK.HCI.AUT` in `DF.ESIGN`.
7. **Given** a client provides an HBA CardHandle and a `SignRequest` with QES flag set, **When** `SignDocument` is called, **Then** a qualified signature is created using `PrK.HP.QES` on the HBA; the signature meets eIDAS Qualified Electronic Signature requirements; a PIN confirmation from the practitioner is required before the signing key is used.

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

### User Story 13 — JMX Management Interface for Operators (Priority: P2)

An operator uses the embedded Hawtio management console to invoke JMX management operations against the running system without modifying configuration or restarting the application. The JMX interface provides operator-initiated control over: DNS service discovery refresh, SICCT card terminal UDP discovery, TSL reload, individual terminal connect/disconnect, card PIN verification via the terminal trusted PIN pad, key reference enumeration, KeyStore reload, database backup export/import, and direct invocation of SignatureService, EncryptionService, and CertificateService operations for diagnostic purposes. All operations are accessible as JMX attributes and operations via Hawtio's JMX browser panel, without any external SOAP client or CLI tool.

**Why this priority**: Many operational recovery scenarios — stale TSL, disconnected terminal, expired DNS cache, need for ad-hoc key listing — require immediate operator action without a full restart. JMX via Hawtio is the JDK standard management interface (`javax.management`), directly applying Principle VIII. Exposing diagnostic crypto operations through JMX enables end-to-end smoke tests without an external SOAP client.

**Independent Test**: Open Hawtio; navigate to `de.servicehealtherx` in the JMX tree. All MBeans from FR-221 through FR-231 must appear. Invoke `TslManagement.reloadTsl()` — a new TSL download completes and the health endpoint returns UP. Invoke `SicctTerminalConnectionManagement.connect("terminal-1")` for a DISCONNECTED terminal — the terminal transitions to CONNECTED within 15 seconds. Invoke `SignatureServiceManagement.sign(alias, "SHA256withECDSA", base64Data)` — a valid base64-encoded signature is returned and an audit log entry is emitted.

**Acceptance Scenarios**:

1. **Given** Hawtio is open, **When** the operator navigates the JMX tree, **Then** all MBeans listed in FR-221 through FR-231 appear under the `de.servicehealtherx` domain with their documented operations and attributes visible and invocable.
2. **Given** the TSL has not been refreshed for over 24 hours, **When** the operator invokes `TslManagement.reloadTsl()`, **Then** a synchronous TSL download completes within 60 seconds, the sequence number and expiry update, and the SmallRye Health readiness endpoint reflects the new state.
3. **Given** a SICCT terminal is DISCONNECTED, **When** the operator invokes `SicctTerminalConnectionManagement.connect("terminal-1")`, **Then** a TCP connection attempt begins immediately and the terminal status changes to CONNECTED (or RECONNECTING on failure) within 15 seconds without a restart.
4. **Given** a card is in a SICCT terminal slot, **When** the operator invokes `CardPinManagement.verifyPin("terminal-1", 0, "SMC-B.PIN.SMC")`, **Then** the terminal's PIN pad prompts for entry; after the operator enters the PIN on the trusted display, the result (OK / REJECTED / BLOCKED with remaining-tries count) is returned to the JMX caller; the PIN MUST NOT pass through JVM memory.
5. **Given** backup preparation has been triggered via `BackupRestoreManagement.exportBackup()`, **When** the operator copies the returned backup password and triggers a restore on a new host via `importBackup(password)`, **Then** all paired terminals have their `sealedSharedSecret` re-sealed to the new host's TPM and a CRITICAL-level audit entry is emitted per terminal.

---

### Edge Cases

- What happens when the HSM is temporarily unreachable during a signing operation?
- How does the system handle a SICCT terminal that disappears mid-session (network loss)?
- What happens when an ECC certificate is not available in the VZD but an RSA one exists, and ECC-preferred mode is active?
- How are simultaneous requests for the same CardHandle from multiple client sessions serialized?
- What happens if a tenant's namespace is deleted while active sessions exist?
- How does the system respond if the SZZP connection to TI is interrupted during an LDAP Search operation?
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
- **FR-005**: All Java packages produced by this project MUST begin with `de.servicehealtherx`. The root package for each Maven module is derived mechanically from the module's folder name: split the folder name by `-`, discard the last segment, then join the remaining segments with `.` and prepend `de.servicehealtherx.`. Examples: `crypto-lib` → `de.servicehealtherx.crypto`; `sicct-lib` → `de.servicehealtherx.sicct`; `crypto-services-lib` → `de.servicehealtherx.crypto.services`; `quarkus-sicct-extension` → `de.servicehealtherx.quarkus.sicct`; `quarkus-ldap-proxy-server-extension` → `de.servicehealtherx.quarkus.ldap.proxy.server`; `consumer-soap-server` → `de.servicehealtherx.consumer.soap`; `konnektor-soap-server` → `de.servicehealtherx.konnektor.soap`. No class MUST reside outside this naming scheme. Third-party library namespaces (e.g. `de.gematik.pki`) are not affected.

**FR-010 — FR-039: Pluggable Cryptographic Provider**

- **FR-010**: The system MUST provide a unified `CryptoProvider` CDI interface through which all services (Encryption Service, Signature Service, Certificate Service) perform signing, verification, encryption, and decryption operations. The interface MUST expose: `sign(alias, params, data)`, `verify(alias, params, data, signature)`, `encrypt(alias, params, plaintext)`, `decrypt(alias, params, ciphertext)`, `listKeyStores()`, `getAvailability(alias)`, and `getAvailabilities()`. No service MUST directly access key material; all access MUST be via this interface.
- **FR-011**: The `CryptoProvider` MUST route each operation to the correct key source based solely on the requested key alias; callers MUST NOT need to know which key source type backs a given alias.
- **FR-012**: The `CryptoProvider` MUST simultaneously support four key source types, all configurable via the application's standard configuration: P12 software keystores, PKCS#11 HSMs, PC/SC smart card readers, and SICCT networked card terminals.
- **FR-013**: Key aliases MUST be unique across all configured key sources within a single tenant instance; the system MUST detect and reject conflicting alias configurations at startup with a human-readable error identifying the conflict.
- **FR-014**: The `CryptoProvider` MUST report the availability status (AVAILABLE / UNAVAILABLE / ERROR) of each registered key alias; this status MUST be exposed via the SmallRye Health readiness endpoint.
- **FR-015**: Adding or removing a P12 or PC/SC key source MUST take effect within 60 seconds without restarting the application; adding, updating, or removing a SICCT terminal MUST take effect immediately via the management console (see FR-096–FR-099); PKCS#11 source changes require restart; in-flight operations at the time of any removal MUST either complete or fail with a clear error.
- **FR-016**: The system MUST emit a structured audit log entry for every cryptographic operation (alias, operation type, algorithm, caller identity, success/failure, duration); private key material MUST NOT appear in any log field.
- **FR-017**: P12 key sources MUST support password-protected PKCS#12 keystores with RSA and ECC keys; multiple P12 sources with different aliases MAY be configured simultaneously. Each P12 source configuration MUST specify: the path to the PKCS#12 file, the keystore password (to open the file), the entry alias (to select the private key entry within the keystore), and the entry password (to unlock the private key entry, which MAY differ from the keystore password). Both passwords MUST be stored as secrets per FR-028 and MUST NOT appear in logs.
- **FR-018**: PKCS#11 HSM key sources MUST be integrated via the JDK SunPKCS11 provider; private keys stored in the HSM MUST NOT be extractable; the HSM vendor library MUST be configurable via file path (no code change required to switch vendors).
- **FR-019**: Multiple PKCS#11 token slots MAY be configured simultaneously; each slot is independently addressed by alias; PKCS#11 session loss (`CKR_TOKEN_NOT_PRESENT`, `CKR_SESSION_CLOSED`) MUST be detected and the key source marked UNAVAILABLE.
- **FR-020**: PC/SC key sources MUST integrate with PC/SC-compliant USB card readers via the JDK `javax.smartcardio` API; the system MUST monitor readers for card insertion and removal events, updating available aliases within 10 seconds of a physical event.
- **FR-021**: Signing operations on PC/SC-connected cards MUST execute on the card chip; private key bytes MUST NOT be loaded into application memory. When the host PC/SC reader exposes an integrated PIN pad (class 2 or class 3 reader, detectable via `CM_IOCTL_GET_FEATURE_REQUEST` returning `FEATURE_VERIFY_PIN_DIRECT`), the PC/SC key source MUST use `FEATURE_VERIFY_PIN_DIRECT` for all PIN verification steps; the PIN MUST NOT transit JVM memory. For class-1 readers without a PIN pad, a static PIN MAY be configured per alias via the application's standard secrets mechanism; static PIN is also acceptable for non-interactive automated scenarios (e.g. SM-B in CI).
- **FR-022**: SICCT card terminal management MUST be implemented in the `quarkus-sicct-extension` Maven module as a Quarkus CDI extension managing TCP/IP client connections via Netty.
- **FR-023**: The SICCT extension MUST manage TCP/IP client connections to one or more configured terminal endpoints (host + port); each endpoint has an independently managed connection with its own health state and retry logic.
- **FR-024**: Cards in connected SICCT terminals MUST be automatically detected; their key aliases MUST be registered within 5 seconds of card insertion notification; signing MUST execute via SICCT APDU forwarding on the card.
- **FR-025**: SICCT TCP/IP disconnections MUST be detected within 10 seconds; the extension MUST begin automatic reconnection with exponential backoff; at maximum backoff interval, periodic retry continues indefinitely with alert-level logging.
- **FR-026**: The SICCT extension MUST expose per-terminal connection state via the application's health and management interface.
- **FR-027**: A configurable per-operation timeout MUST be enforced for SICCT APDU exchanges; timed-out operations MUST fail with a clear error without leaving the connection in an undefined state.
- **FR-028**: In production, ALL MicroProfile Config properties (including P12 paths, PKCS#11 library paths, PC/SC reader names, key aliases, PINs, passwords, and global SICCT extension settings) MUST be stored in the JavaDB `AppConfigProperty` table and read at runtime via the `configsource-db` ConfigSource extension (`microprofile-extensions/config-ext`). No production-relevant configuration MUST reside in config files on the filesystem; this ensures a full JavaDB dump constitutes a complete backup of both data and configuration. Local file-based config (e.g. `application.properties`) is acceptable in development and CI environments only. SICCT terminal host/port/timeout records MUST NOT use MicroProfile Config at all — they are managed exclusively as `CardTerminal` JPA entities via the database and management console (see FR-096–FR-099).
- **FR-029**: Both the SICCT and PC/SC key source implementations MUST support interactive PIN collection as the primary PIN mechanism. For SICCT-backed aliases, when a `CryptoProvider` signing or decryption operation requires card PIN authentication (card in PIN-locked state, no session PIN cached), the SICCT extension MUST request interactive PIN entry via the terminal's trusted PIN pad using the SICCT VERIFY PIN command before issuing the APDU; the PIN MUST NOT be read from configuration or JVM memory. For PC/SC-backed aliases, the key source MUST detect whether the reader supports `FEATURE_VERIFY_PIN_DIRECT` (per FR-021) and use that path if available; falling back to a statically configured PIN only for readers without a PIN pad. In both cases the `CryptoProvider` interface MUST propagate the PIN-required signal to the caller (e.g. event or exception) if no interactive mechanism is available, so the SOAP layer can return a structured error rather than silently failing.

**FR-030 — FR-039: Encryption Service**

- **FR-030**: The system MUST expose an `EncryptionService` SOAP endpoint conforming to `consumer/EncryptionService.wsdl` (v3.0.1 from gematik/api-telematik@ebk_6.0.3, namespace prefix CRYPTW) implementing `EncryptDocument` and `DecryptDocument`. Every response MUST validate against `consumer/EncryptionService.xsd` and `consumer/ConsumerCommon.xsd`.
- **FR-031**: `EncryptDocument` MUST perform hybrid encryption: XMLEnc (`http://www.w3.org/TR/xmlenc-core/`) for XML documents; CMS (`urn:ietf:rfc:5652`) for binary documents; symmetric key wrapped asymmetrically per recipient certificate. `[TUC: PL_TUC_HYBRID_ENCIPHER (A_17466, §6.1.1)]`
- **FR-032**: `EncryptDocument` MUST validate all recipient certificates via `PL_TUC_PKI_VERIFY_CERTIFICATE` before encrypting; expired or revoked certificates MUST be rejected.
- **FR-033**: When ECC-preferred mode is active, `EncryptDocument` MUST use ECC certificates exclusively (RSA MUST NOT be used when both exist for a recipient) per TIP1-A_6984-03.
- **FR-034**: `DecryptDocument` MUST determine the cryptographic algorithm from the hybrid key in the encrypted document; the decryption private key MUST be accessed exclusively via the `CryptoProvider` interface; the key MUST NOT leave its configured source boundary. `[TUC: PL_TUC_HYBRID_DECIPHER (A_17467, §6.1.1)]`

**FR-040 — FR-049: Signature Service**

- **FR-040**: The system MUST expose a `SignatureService` SOAP endpoint conforming to `consumer/SignatureService.wsdl` (v3.2.1 from gematik/api-telematik@ebk_6.0.3, namespace prefix SIGW) implementing `SignDocument`, `SignPlain`, `VerifyDocument`, and `ExternalAuthenticate`. Every response MUST validate against `consumer/SignatureService.xsd` and `consumer/ConsumerCommon.xsd`.
- **FR-041**: `SignDocument` MUST support CAdES signature creation (CMS-based, RFC 5652, CAdES-BES baseline) for both nonQES (using `PrK.HCI.OSIG`) and QES (using `PrK.HP.QES` on HBA with PIN confirmation) signing levels; all signing MUST be performed via the `CryptoProvider` interface. `[TUC: PL_TUC_SIGN_DOCUMENT_nonQES (A_17517, §6.2.1); PL_TUC_SIGN_HASH_nonQES (A_17518) is called internally per SignRequest]`
- **FR-042**: `SignDocument` MUST use exclusively ECC (`PrK.HCI.OSIG.E256`) when ECC-preferred mode is active per A_17525-04; RSA (`PrK.HCI.OSIG.R2048`) MUST NOT be selected in ECC-preferred mode.
- **FR-043**: `SignPlain` MUST create an ECDSA signature over a binary payload per BSI-TR-03111 §4.2.1; only `PrK.HCI.OSIG.E256` is permitted per A_27048-01. `[TUC: PL_TUC_SIGN_HASH_nonQES (A_17518, §6.2.2.2; IDENTIFIKATOR=CardHandle, SIGNATURVERFAHREN=ECDSA mit SHA-256, HASHWERT=SIG:BinaryString)]`
- **FR-044**: `VerifyDocument` MUST verify CMS signatures and return `HighLevelResult` VALID, INVALID, or INCONCLUSIVE with the assumed signing timestamp type per A_17526-03. `[TUC: PL_TUC_VERIFY_DOCUMENT_nonQES (A_17577, §6.2.1; SIGNED_DOCUMENT=SIG:Document, CERTIFICATE=extracted from document, SIGNATURE=dss:SignatureObject, TIME_REFERENCE=SigningTime)]`
- **FR-045**: `ExternalAuthenticate` MUST sign a binary hash (≤ 512 bits) using ECDSA or PKCS#1 as determined by `dss:SignatureType`; for SM-B usage, only `PrK.HCI.AUT` in `DF.ESIGN` is permitted per A_17578-04. `[TUC: PL_TUC_SIGN_HASH_nonQES (A_17578-04, §6.2.4; IDENTIFIKATOR=CardHandle, SIGNATURVERFAHREN=SIG:SignatureSchemes, HASHWERT=SIG:BinaryString)]`
- **FR-046**: A signature verification report (`Signaturpruefungsbericht_JJJJMMTT_hhmm`) MAY be attached when verification is performed on KOM-LE messages per A_23999.
- **FR-047**: `SignDocument` MUST support PAdES signature creation (PDF-embedded, ETSI EN 319 102-1, PAdES-B-B baseline) for PDF documents; the signature MUST be embedded into the PDF structure (not detached); both nonQES and QES signing levels MUST be supported.
- **FR-048**: `SignDocument` MUST support XAdES signature creation (XML-embedded, ETSI EN 319 132-1, XAdES-B-B baseline) for XML documents; enveloped (signature within the signed XML) and detached (separate signature file with reference) modes MUST both be supported; both nonQES and QES signing levels MUST be supported.
- **FR-049**: For QES (Qualified Electronic Signature) operations, the system MUST use the HBA's qualified signature key (`PrK.HP.QES`) exclusively; the HBA MUST be accessed via the `CryptoProvider` interface through a SICCT terminal or PC/SC reader; the practitioner MUST confirm the operation via PIN entry (remote-PIN per FR-141 or local PIN pad) before the QES key is used; the resulting signature MUST comply with eIDAS Regulation (EU) No 910/2014 Annex I requirements for qualified electronic signatures. `[TUC: PL_TUC_CARD_VERIFY_PIN (A_18130, §6.6.2 — mandatory PIN verification before QES key activation); signing executed on HBA via card APDU using PrK.HP.QES; no dedicated QES TUC exists in gemSpec_Systemprozesse_dezTI — the Basis Consumer bridges nonQES infrastructure to QES key material per eIDAS Annex I]`

**FR-050 — FR-059: Certificate Service**

- **FR-050**: The system MUST expose a `CertificateService` SOAP endpoint conforming to `consumer/CertificateService.wsdl` (v3.0.1 from gematik/api-telematik@ebk_6.0.3, namespace prefix CERTW) implementing `ReadCertificate` and `VerifyCertificate`. Every response MUST validate against `consumer/CertificateService.xsd`, `consumer/CertificateServiceCommon.xsd`, and `consumer/ConsumerCommon.xsd`.
- **FR-051**: `ReadCertificate` MUST read X.509 certificates (C.AUT or C.OSIG) from the SM-B identity (SM-B/KTR or SM-B/ORG) via the `CryptoProvider` interface; reading eGK or HBAx certificates MUST be rejected with error 4090 per A_24782-02.
- **FR-052**: `ReadCertificate` MUST support `Crypt=RSA` and `Crypt=ECC` (default ECC); the corresponding card object (`EF.C.HCI.AUT`, `EF.C.HCI.AUT.E256`, `EF.C.HCI.OSIG.R2048`, `EF.C.HCI.OSIG.E256`) MUST be selected.
- **FR-053**: `VerifyCertificate` MUST check X.509 certificates against the TI PKI via OCSP (`TOLERATE_OCSP_FAILURE=yes`) and return VALID, INVALID, or INCONCLUSIVE with role OIDs per A_17429-02. `[TUC: PL_TUC_PKI_VERIFY_CERTIFICATE (A_17401, §6.3.1; Certificate=CERTCMN:X509Certificate, Referenzzeitpunkt=CERT:VerificationTime, PolicyList=none, KeyUsage=empty, ExtendedKeyUsage=empty, TOLERATE_OCSP_FAILURE=yes)]`

**FR-060 — FR-069: LDAP Proxy**

- **FR-060**: The system MUST expose an LDAPv3 interface supporting Bind, Unbind, Search, and Abandon operations per RFC 4511 per A_17343. `[TUC: PL_TUC_VZD_BIND (Bind→connect LDAPS, validate C.ZD.TLS-S cert), PL_TUC_VZD_SEARCH (Search→RFC-4511 §4.5.1), PL_TUC_VZD_UNBIND (Unbind), PL_TUC_VZD_ABANDON (Abandon); mapping per Tab_Ldap_TUC_Mapping §6.4.1]`
- **FR-061**: Any LDAPv3 operation other than the four permitted (Bind, Unbind, Search, Abandon) MUST be rejected with error 53 (`unwillingToPerform`) per A_17341-01.
- **FR-062**: The VZD endpoint MUST be resolved at runtime via DNS-SD (`_ldap._tcp.vzd.<TI_DNS_TOP_LEVEL_DOMAIN>`) over the TI network; LDAP requests MUST be forwarded to the resolved VZD address over LDAPS (port 636, TLS mandatory) per TUC_KON_290 (TIP1-A_5517-03). LDAP port 389 MUST only be offered to clients when the system's TLS-mandatory flag is disabled.
- **FR-063**: The outbound LDAPS connection to VZD MUST validate the server certificate via `TUC_PKI_018` (policy `oid_zd_tls_s`, ExtKeyUsage `serverAuth`, OCSP); a certificate that fails validation MUST cause the LDAP session to be torn down and the client to receive an error; offline OCSP responses MUST NOT be accepted per TIP1-A_5517-03.

**FR-070 — FR-089: KOM-LE Client Module**

- **FR-070**: The KOM-LE capability MUST be provided by the openkim open-source library (https://github.com/sberg-net/openkim), maintained as an organizational fork integrated as a git submodule; all TI-specific adaptations MUST be made in the fork. The `openkim-server` MUST be deployed as a standalone Spring Boot application pod within each tenant's Kubernetes namespace; it MUST NOT be bundled inside the Quarkus `basis-consumer-server` pod. The `basis-consumer-server` communicates with `openkim-server` over SMTP/POP3 on the pod-local network. Mail clients connect to `openkim-server` directly on its SMTP/POP3 ports. `openkim-server` connects to the TI `KIM-Fachdienst` (the gematik KIM mail service) via the SZZP.
- **FR-071**: The git submodule reference MUST be pinned to a specific commit in the organizational fork; updates MUST follow the standard release process and be auditable in git history.
- **FR-072**: The organizational fork MUST be synchronized with upstream openkim releases; a process MUST exist to review and merge upstream changes within 60 days of each upstream release.
- **FR-073**: The KOM-LE module MUST expose SMTP and POP3 interfaces to client mail applications.
- **FR-074**: The KOM-LE module MUST synchronize time via `PL_TUC_NET_SYNC_TIME` with the TI platform time service per A_17298.
- **FR-075**: Outgoing messages MUST be encrypted for all RCPT recipients and the sender using `C.HCI.ENC` (institution) or `C.HP.ENC` (practitioner) certificates; encryption MUST be performed via the `CryptoProvider` interface. `[TUC: PL_TUC_SIGN_DOCUMENT_nonQES (A_17305, §6.5.2 — sign CMS container per KOM-LE S/MIME profile); PL_TUC_HYBRID_ENCIPHER (A_17305, §6.5.2 — hybrid-encrypt signed message for all recipients and sender)]`
- **FR-076**: When ECC-preferred mode is active per A_26447-01, only ECC encryption certificates MUST be used; RSA MUST NOT be selected if both exist for a recipient (KOM-LE-A_2022-01).
- **FR-077**: The SMTP client interface MUST respond per Tab_SMTP_Ant_Init; unauthenticated MAIL/RCPT/DATA attempts MUST receive `530 5.7.0`; maximum message size MUST be at least 35882577 bytes.
- **FR-078**: The ECC-preferred mode MUST be switchable on or off at runtime, including by remote instruction from gematik per A_26448.
- **FR-079**: The KOM-LE module MUST be configurable with `TLS_AUTH_KONNEKTOR`, `KONNEKTOR_TIMEOUT`, and `KONNEKTOR_URI` parameters per A_17299-02.
- **FR-080**: Before sending a KOM-LE message, the module MUST query the VZD for the sender's `komLeData` attribute and warn the sender if the installed client-module version is lower than the directory version per A_22664.
- **FR-081**: Incoming KOM-LE messages MUST be decrypted and their signatures verified before delivery to the client mail application; decryption fails if the required SM-B identity is not available or cannot be unlocked per A_17337 and A_17338. `[TUC: PL_TUC_VERIFY_DOCUMENT_nonQES (A_17504, §6.5.3 — verify CMS signature of incoming KOM-LE message); PL_TUC_HYBRID_DECIPHER (A_17504, §6.5.3 — decrypt incoming message using SM-B private decryption key)]`

**FR-090 — FR-099: SICCT Card Terminal Integration**

- **FR-090**: The SICCT integration MUST implement the full SICCT V1.3.0 command set over TCP/IP (Ethernet) as a Quarkus CDI extension; per FR-022 to FR-027 above. For all smart-card access the Basis Consumer MUST use the PL_TUC_CARD_* system processes listed in Appendix C of gemSpec_Basis_Consumer_V1.12.1 per A_18130. `[TUC: PL_TUC_CARD_INFORMATION, PL_TUC_CARD_RESET, PL_TUC_CARD_GET_CHALLENGE, PL_TUC_CARD_ACTIVATE_APPLICATION, PL_TUC_CARD_DEACTIVATE_APPLICATION (A_18130, §6.6.1)]`
- **FR-091**: The extension MUST support SICCT service discovery per SICCT §6.2.3 to automatically register network-visible card terminals.
- **FR-092**: The extension MUST manage CT sessions (INIT CT SESSION, CLOSE CT SESSION) with authentication per the three SICCT user roles (CT Admin, CT Control, CC/CT User). Each terminal MUST track a CORRELATION state (`bekannt` → `zugewiesen` → `gepairt` → `aktiv`) per TUC_KON_053/TUC_KON_050; only terminals in state `aktiv` MUST accept card operations.
- **FR-093**: Card slot states MUST be tracked in real time (empty / card inserted / card ready / card activated / card ejected) and reflected in CardHandle availability.
- **FR-094**: Physical card operations (signing, PIN verification, certificate reading) MUST be performed via SICCT APDU commands; private keys MUST NOT be extracted from the card.
- **FR-095**: Both SMC-B (KTR and ORG variants) and eHBA cards MUST be supported via the SICCT interface.
- **FR-096**: All SICCT card terminal configuration MUST be stored exclusively as `CardTerminal` JPA entity fields in the H2/JavaDB database; no `quarkus.sicct.terminals[*]` config keys exist. The `CardTerminal` entity MUST have the following fields: `ctid` (UUID, PK, auto-generated), `physical` (boolean — true=eHealth KT, false=HSM-B), `macAddress` (String, 17, unique), `hostname` (String, 128 — SICCT terminal name / FriendlyName), `ipAddress` (String, 45), `tcpPort` (int), `slotCount` (int), `slotsUsed` (String), `productInformation` (String, 4096 — per gemSpec_OM), `ehealthInterfaceVersion` (String, 32 — from GET STATUS VER), `validVersion` (boolean), `displayCapabilities` (String, 1024 — per SICCT §5.5.10.17), `smktAutCertificate` (byte[] — X.509 C.SMKT.AUT from pairing), `sealedSharedSecret` (byte[] — ShS.KT.AUT sealed via TPM 2.0, FR-139), `correlation` (String, 32 — BEKANNT / ZUGEWIESEN / GEPAIRT / AKTIV / AKTUALISIEREND, per TUC_KON_053), `connected` (boolean), `activeRole` (String, 16 — USER or ADMIN), `adminUsername` (String, 256), `adminPassword` (String, 256), `backupEncryptedSharedSecret` (byte[] — AES-256-GCM encrypted backup, FR-180). Global SICCT extension settings (worker thread pool size, reconnect watchdog interval) MUST be stored in the `AppConfigProperty` table and read via the `configsource-db` ConfigSource per FR-028.
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

- **FR-120**: The system MUST connect to the Telematikinfrastruktur via a Sicherer Zugangspunkt zur Telematikinfrastruktur (SZZP). No VPN-Zugangsdienst (traditional TI VPN) is required; the SZZP provides direct secure access to TI services using mutual TLS with TI certificates per gemSpec_Net §3.1.1. `[TUC: PL_TUC_TLS_SECURE_CHANNEL (gemSpec_Systemprozesse_dezTI §2.3 — mutual TLS; server cert validated via PL_TUC_PKI_VERIFY_CERTIFICATE with PolicyList: oid_fd_tls_s; client auth via PL_TUC_SIGN_HASH_nonQES with PrK.HCI.AUT); PL_TUC_NET_NAME_RESOLUTION (gemSpec_Systemprozesse_dezTI §2.3 — TI DNS resolution); PL_TUC_NET_SYNC_TIME (gemSpec_Systemprozesse_dezTI §2.3 — NTP sync with TI time server)]`
- **FR-121**: The Basis Consumer MUST provide a recursive caching Nameserver per A_17498 that is reachable by all client systems in the local network of the deployment environment; the Caching Nameserver MUST apply a sensible query timeout and MUST abort resolution if a DNS query cannot be completed.
- **FR-122**: Network segmentation MUST be enforced with a default-deny firewall; dynamic routing MUST NOT be used on TI-facing interfaces.

**FR-123 — FR-129: DNS Name Service & Service Localization (Namensdienst und Dienstlokalisierung)** *(§5.1.3, gemSpec_Basis_Consumer_V1.12.1)*

- **FR-123**: The Caching Nameserver MUST implement three forwarding zones per A_17499 (TAB_CONS_687): (1) TI namespace `*.DNS_TOP_LEVEL_DOMAIN_TI` forwarded to `DNS_SERVERS_TI` — resolves all TI-internal names; (2) domain names of each connected healthcare network with WANDA Basic (per `Bestandsnetze.xml`, parameter `NLW_AKTIVE_BESTANDSNETZE`) forwarded to the corresponding `DNS_SERVERS_BESTANDSNETZ`; (3) local deployment namespace `DNS_DOMAIN_CONSUMER` forwarded to `DNS_SERVERS_CONSUMER`. Queries matching none of these zones MUST NOT be forwarded to public internet resolvers.
- **FR-124**: All internal Basis Consumer services (crypto-lib, sicct-lib, openkim-server, LDAP proxy, KOM-LE module) MUST resolve DNS names exclusively through the internal Caching Nameserver's Stub-Resolver per A_17500; the Stub-Resolver MUST always query the Caching Nameserver; direct use of external resolvers is prohibited.
- **FR-125**: The Basis Consumer MUST implement TUC_CON_362 "Liste der Dienste abrufen" for DNS-SD-based service discovery per A_17502: input is the FQDN of a PTR Resource Record; the Stub-Resolver issues a PTR-type query to the Caching Nameserver; output is `LIST_OF_SRV_ENTITIES` (all resolved SRV records). Fachmodule (domain modules) MAY invoke this TUC directly to enumerate available TI services.
- **FR-126**: The Basis Consumer MUST expose a name service application to client systems and Fachmodule in the deployment environment per A_17509 (TAB_CONS_648), providing at minimum a `GetIPAddress` operation that resolves FQDNs to IP addresses via the internal Stub-Resolver. The interface version is defined in the Produktsteckbrief; no WSDL or XML namespace scope is prescribed.
- **FR-127**: During application boot the Basis Consumer MUST start the Caching Nameserver before any other TI-connected service attempts DNS resolution per A_17512-01; a startup failure of the DNS subsystem MUST prevent the application from reaching ready state.
- **FR-128**: An administrator interface MUST expose the following DNS configuration parameters per A_17513-01 (Tables 5 and 6): configurable (read-write) — `DNS_SERVERS_CONSUMER` (list of IP addresses for DNS servers resolving the local deployment domain; MAY be restricted to `ANLW_LAN_IP_ADDRESS` range) and `DNS_DOMAIN_CONSUMER` (local DNS domain name — MUST NOT begin with "."); read-only (view-only) — `DNS_SERVERS_TI` (TI nameserver IP addresses) and `DNS_TOP_LEVEL_DOMAIN_TI` (TI top-level domain). Changes to `DNS_SERVERS_CONSUMER` or `DNS_DOMAIN_CONSUMER` MUST take effect immediately at the Caching Nameserver without restart.
- **FR-129**: The Basis Consumer MUST localize the TI Configuration Service (KSR) by querying `DNS_SERVERS_TI` for SRV and TXT resource records at `_ksrkonfig._tcp.ksr.<DNS_TOP_LEVEL_DOMAIN_TI>` to obtain `MGM_KSR_KONFIG_URL` per A_17576. It MUST then refresh its infrastructure configuration (including `Bestandsnetze.xml`) at least once daily by connecting to `MGM_KSR_KONFIG_URL` over TLS, verifying the server certificate via `PL_TUC_PKI_VERIFY_CERTIFICATE`, and downloading the configuration via `I_KSRS_Download::get_Ext_Net_Config` per A_17574. Updated WANDA Basic network DNS forwarding rules derived from `Bestandsnetze.xml` MUST be applied to the Caching Nameserver immediately after each successful download.

**FR-130 — FR-139: SICCT Terminal Authentication & TLS Security** *(derived from gemSpec_KT_V3.17.0 and gemSpec_Kon_V5.27.0)*

- **FR-130**: Before a SICCT terminal can be used for card operations, the Basis Consumer MUST complete a pairing procedure using the EHEALTH TERMINAL AUTHENTICATE command with variant `CREATE` (P2=01): generate 16 bytes of cryptographically strong randomness as a shared secret (`ShS.KT.AUT`), open a SICCT INIT CT SESSION in Admin role, and send the command with the secret and a human-readable pairing label. The terminal prompts the operator on its display; the operator must confirm. On confirmation, the terminal returns a signature over the secret using its SM-KT identity; the Basis Consumer MUST verify this signature. On success, the terminal state advances to `gepairt → aktiv` per TUC_KON_053 (TIP1-A_4553-02).
- **FR-131**: Every CT session establishment MUST perform a mutual-authentication validation step using EHEALTH TERMINAL AUTHENTICATE variant `VALIDATE` (P2=02): the Basis Consumer generates a random challenge (≥16 bytes), sends it to the terminal, and verifies that the terminal's response equals `SHA-256(challenge ‖ ShS.KT.AUT)`. A validation failure MUST immediately close the TLS connection and mark the terminal DISCONNECTED; error code 4029 MUST be returned per TUC_KON_050 (TIP1-A_4545-04).
- **FR-132**: The Basis Consumer MUST support maintenance pairing using EHEALTH TERMINAL AUTHENTICATE variants `ADD Phase 1` (P2=03) and `ADD Phase 2` (P2=04) when a terminal is reachable over TLS but its stored pairing block references an outdated public key (e.g. after SAK.AUT key rotation). The maintenance pairing MUST be initiated automatically when reconnection fails due to a pairing mismatch per TIP1-A_5011.
- **FR-133**: All TCP/IP connections from the Basis Consumer to SICCT terminals MUST use mutual TLS. The Basis Consumer MUST present a SAK.AUT client certificate (policy OID `1.2.276.0.76.4.113`, KeyUsage `digitalSignature`, ExtKeyUsage `tlsClientAuthentication`) to the terminal per A_22887/A_22890. Only cipher suites approved by gemSpec_Krypt (ECC+AES-GCM or RSA+AES-CBC; DES MUST NOT be used) are permitted per A_22450.
- **FR-134**: The SICCT Quarkus extension MUST enforce the SICCT TLS state machine per A_22456. The four states — `NoSicctTls`, `InvalidClient`, `ClientWithoutPairing`, `ClientWithPairing` — determine which SICCT commands may execute: in `NoSicctTls` no commands are permitted (A_22461); in `InvalidClient` only INIT/CLOSE CT SESSION, GET/SET STATUS, and CT DOWNLOAD commands are permitted (CMD_KT_0004, TIP1-A_3136-01); in `ClientWithoutPairing` EHEALTH TERMINAL AUTHENTICATE is additionally permitted (CMD_KT_0005, TIP1-A_3096-01); full command access requires `ClientWithPairing`.
- **FR-135**: Shared secrets (`ShS.KT.AUT`) MUST be unique per terminal and per tenant; the Basis Consumer MUST not reuse a shared secret across terminals or across tenants. All pairing information (shared secrets and SAK.AUT public keys) MUST be sealed using the host TPM 2.0 before persistence per FR-139; they MUST be deleted when a terminal is decommissioned per TIP1-A_3244. If the terminal reports a duplicate-secret error (SICCT SW `6900`), the system MUST generate a new secret and retry.
- **FR-136**: Loss of TLS connectivity to a terminal MUST be detected within 30 seconds; upon detection, the extension MUST discard all session keys for that terminal and mark it DISCONNECTED before initiating reconnection. No SICCT commands MUST be dispatched to a terminal after TLS loss is detected, per A_19431.
- **FR-137**: The Basis Consumer MUST monitor the expiry date of the gSMC-KT certificate (`C.SMKT.AUT`) returned by each terminal during session establishment. If the certificate expires within 35 days, the system MUST publish an `EC_CardTerminal_gSMC_KT_Certificate_Expires_Soon` event and log a warning containing the terminal name and remaining days per TUC_KON_050.
- **FR-138**: The pairing initiation label sent in the EHEALTH TERMINAL AUTHENTICATE CREATE command MUST follow the format `KT:<MAC> MIT KON:<hostname> PAIREN OK?` as specified in TIP1-A_4553-02, where `<MAC>` is the terminal MAC address and `<hostname>` is the Basis Consumer's configured identity hostname. The terminal displays this text and waits up to 10 minutes for operator confirmation; a decline (operator cancels) or timeout MUST cause the pairing to be aborted and error 4041 reported.
- **FR-139**: The 16-byte `ShS.KT.AUT` shared secret for each paired terminal MUST be sealed to the host's TPM 2.0 chip before storage in the database (`CardTerminal.sealedSharedSecret`). The TPM seal operation MUST bind the secret to a set of PCR values representing the expected boot-time platform state — at minimum PCR 0 (firmware), PCR 1 (firmware configuration), and PCR 7 (Secure Boot policy). The sealed blob (not the raw secret) is what is persisted; the raw secret exists in JVM memory only for the duration of a single VALIDATE or CREATE operation and MUST be zeroed immediately after use. At each CT session establishment, the SICCT extension MUST unseal the blob via the local TPM before invoking VALIDATE; if the PCR values have changed since sealing (indicating a platform integrity violation), unsealing MUST fail, the terminal MUST be kept in FAILED state, and a CRITICAL-level alert MUST be logged naming the terminal and the mismatched PCR indices — the operator must re-pair the terminal via `EHEALTH TERMINAL AUTHENTICATE CREATE`. If the TPM is unavailable at application startup, all terminals with a sealed secret MUST remain in FAILED state until the TPM becomes available; terminals with no sealed secret (not yet paired) MUST still connect and accept initial pairing. The implementation MUST use TPM 2.0 (`TPM_PT_FAMILY_INDICATOR = "2.0"`); TPM 1.2 MUST NOT be used. The Java binding MUST be provided via the `tss.java` library (Microsoft TSS for Java) or an equivalent TCG TSS 2.0-compliant binding; no native CLI wrappers (`tpm2-tools`) are permitted in the production code path.

**FR-140 — FR-149: Card-to-Card Authentication & Advanced Card Operations**

- **FR-140**: The Basis Consumer MUST implement card-to-card authentication (TUC_KON_005) to support scenarios requiring a trusted channel between two cards (e.g. remote-PIN). The `authMode` parameter selects between unilateral (`einseitig`), mutual (`gegenseitig`), and mutual with trusted channel (`gegenseitig+TC`). The source card (gSMC-KT or SM-B) MUST have its PIN verified before a mutual authentication is initiated (TIP1-A_4572).
- **FR-141**: The Basis Consumer MUST support remote-PIN entry per TIP1-A_5012 for HBA and SMC-B cards in SICCT terminals, using the gSMC-KT in the terminal as an intermediary. Remote-PIN uses TUC_KON_005 with `authMode=gegenseitig+TC` to establish a Trusted Channel (session key) between the gSMC-KT and the target card; the PIN is encrypted by the gSMC-KT with the session key and transmitted in the PIN command on the same logical channel as the session-key agreement. Remote-PIN display on the terminal MUST indicate the PIN entry is in progress using the terminal's trusted display (TIP1-A_2979). Remote-PIN is supported for HBA.PIN.CH/PUK.CH, HBA.PIN.QES/PUK.QES, and SMC-B.PIN.SMC/PUK.SMC.
- **FR-142**: The Basis Consumer MUST support secure card sessions (TUC_KON_223 "Starte Kartensitzung") enabling integrity- and authenticity-protected APDU sequences to a card. A secure session MUST use a sequence counter; APDUs with expected status words differing from received status words MUST abort the session. The card MUST be exclusively locked for the session duration (A_25895).
- **FR-143**: `SecureSendAPDU` (TUC_KON_208) MUST transmit protected APDUs within an established secure session; the Basis Consumer MUST validate each response APDU's status word and integrity before returning it to the caller per A_26069-01.
- **FR-144**: `VerifyPin` MUST determine whether to use local or remote-PIN based on the target card's type and the terminal's capabilities. Results MUST be one of: OK, ERROR, REJECTED (with remaining tries), BLOCKED. The operation MUST emit `VERIFY_STARTED` and `VERIFY_FINISHED` events per TIP1-A_4567. PIN material MUST NOT appear in any log or audit entry. `[TUC: PL_TUC_CARD_VERIFY_PIN (A_18130, §6.6.2 — triggers card PIN entry on terminal trusted display; routes PIN to card exclusively; PIN MUST NOT be logged)]`
- **FR-145**: `ChangePin`, `UnblockPin`, `GetPinStatus`, `EnablePin`, and `DisablePin` MUST be exposed on the Konnektor SOAP interface and backed by the corresponding TUCs (TUC_KON_019, TUC_KON_021, TUC_KON_022, TUC_KON_027). PIN material MUST NOT appear in any log or network trace. `[TUC: PL_TUC_CARD_CHANGE_PIN (A_18130, §6.6.2 — ChangePin/UnblockPin); PL_TUC_CARD_ENABLE_PIN (A_18130 — EnablePin); PL_TUC_CARD_DISABLE_PIN (A_18130 — DisablePin)]`

**FR-150 — FR-159: SOAP Interface Compliance**

- **FR-150**: Every SOAP operation MUST accept and validate the Konnektor `AufrufKontext` context parameters as defined in gemSpec_Kon_V5.27.0 TAB_KON_511-01: `mandantId`, `clientSystemId`, and `workplaceId` are mandatory for all operations; `userId` is mandatory for HBA-based operations (signing with eHBA, ExternalAuthenticate with HBAx) and optional for SM-B operations. A request missing a mandatory context parameter MUST be rejected with error code 4021 per TUC_KON_000 (TIP1-A_4524-03).
- **FR-151**: The **konnektor-soap-server** `SignatureService` and `EncryptionService` endpoints (conn/ WSDLs) MUST support MTOM (Message Transmission Optimization Mechanism) for efficient binary payload transfer, mandatory from PTV5 per TIP1-A_5694-03 and A_15786. Non-MTOM requests for these services MUST still be accepted (graceful fallback). The **consumer-soap-server** endpoints (consumer/ WSDLs) do NOT use MTOM and MUST NOT configure an MTOM interceptor; the consumer WSDLs use standard SOAP document/literal encoding without MTOM attachments.
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

- **FR-174**: The generated Java package name MUST be `de.servicehealtherx.sicct.asn1`; all generated classes MUST reside in this package or a sub-package.

- **FR-175**: Every new ASN.1 type added for EHEALTH AUTHENTICATE MUST include an inline comment referencing the normative source (gematik Afo ID or gemSpec_KT section) from which it is derived.

- **FR-176**: The extended `SICCT.asn1` MUST remain parseable by the beanit parser with zero errors. Existing type definitions in the file (TEIL A–D) MUST NOT be modified in any way that breaks backward compatibility with the existing SICCT command set.

- **FR-177**: The EHEALTH AUTHENTICATE types MUST be placed in a clearly delimited section designated `TEIL E: EHEALTH TERMINAL AUTHENTICATE (gemSpec_KT_V3.17.0)`, matching the style of the existing TEIL A/B/C/D section headers.

**FR-180 — FR-183: Backup & Restore**

- **FR-180**: An authorized operator MUST be able to trigger a backup preparation operation via the Hawtio management console. The operation MUST: (1) generate a cryptographically random 256-bit backup password; (2) for each `CardTerminal` with a non-null `sealedSharedSecret`, unseal the TPM blob to recover the 16-byte `ShS.KT.AUT` (raw secret in JVM heap only), derive an AES-256-GCM key via HKDF-SHA-256 (IKM = backup password; salt = `hostname` encoded as UTF-8; info = `"ShS.KT.AUT backup"`), encrypt the raw secret, persist the result as `CardTerminal.backupEncryptedSharedSecret` (IV ‖ ciphertext ‖ GCM tag, base64url-encoded), and zero the raw secret from JVM memory immediately after encryption; (3) display the backup password to the operator exactly once in the management console — the system MUST NOT persist the backup password anywhere; (4) automatically clear all `backupEncryptedSharedSecret` fields after a configurable retention window (default: 1 hour). A full JavaDB dump taken during this retention window constitutes a complete backup of the terminal pairing state.

- **FR-181**: A restore procedure MUST be provided (via Hawtio or a CLI tool) that accepts the backup password and a restored JavaDB dump. For each `CardTerminal` with a non-null `backupEncryptedSharedSecret`, the procedure MUST: (1) re-derive the AES-256-GCM key from the operator-supplied backup password using the same HKDF parameters as FR-180; (2) decrypt the 16-byte `ShS.KT.AUT` (raw secret in JVM heap only); (3) re-seal the secret to the current host's TPM 2.0 (PCR 0, 1, 7) and update `CardTerminal.sealedSharedSecret`; (4) zero `backupEncryptedSharedSecret`; (5) emit a CRITICAL-level audit entry per terminal recording the operator identity, timestamp, and terminal ID. Terminals are left in PAIRED state and transition to ACTIVE upon the first successful VALIDATE after restore. The raw secret MUST NOT be written to disk, logged, or transmitted in unencrypted form during this procedure.

- **FR-182**: The 16-byte `ShS.KT.AUT` raw secret MUST only ever exist in one of three states: (a) sealed inside the host TPM 2.0 blob at rest (`sealedSharedSecret`), (b) AES-256-GCM encrypted under the operator-held backup password at rest (`backupEncryptedSharedSecret`, backup window only), or (c) transiently in the JVM heap during the strictly bounded operations — TPM unseal → AES-GCM encrypt/decrypt → TPM seal or VALIDATE HMAC computation. Outside these states the raw secret MUST NOT exist outside the `basis-consumer-server` JVM process or the card terminal hardware.

- **FR-183**: A JavaDB dump taken during an active backup preparation window (FR-180) MUST constitute a complete and self-contained system backup: it captures all terminal pairing state (`CardTerminal` including `backupEncryptedSharedSecret`) AND all application configuration (via `AppConfigProperty` / configsource-db, FR-028). PKCS#11/P12 private key material stored outside the database (e.g. HSM, P12 file) is NOT captured by the DB dump and MUST be backed up by the operator separately; this is a documented limitation. Kubernetes pod manifests and container images are considered infrastructure and are out of backup scope.

**FR-200 — FR-219: Error Handling — SOAP Services**

- **FR-200**: Every SOAP service MUST return errors as a gematik-structured `SOAP-Fault` per FR-153. The `Status/Result` element MUST be `Fatal` for unrecoverable errors and `Error` for recoverable errors. The innermost `Trace` MUST carry the defined gematik numeric error code; additional outer `Trace` elements MAY provide service-specific context. The technical fallback code is **4001** when no more specific code applies (TIP1-A_5058-01, A_14159).

- **FR-201**: `ExternalAuthenticate` (both consumer `SignatureService` and konnektor `AuthSignatureService`) MUST implement the following error table derived from Tab_Fehler_ExternalAuthenticate (gemSpec_Basis_Consumer §6.2.4):

  | Code | ErrorType | Fehlertext                                   | Condition                                                                                  |
  | ---- | --------- | -------------------------------------------- | ------------------------------------------------------------------------------------------ |
  | 4000 | Technical | Syntaxfehler                                 | Parameter validation failure in `checkArguments` step — invalid or inconsistent parameters |
  | 4111 | Technical | Ungültiger Signaturtyp oder Signaturvariante | `dss:SignatureType` URI is not a recognised PKCS#1 or ECDSA URI                            |

  Result codes from the underlying `PL_TUC_SIGN_HASH_nonQES` (`SecurityStatusNotSatisfied`, `ObjectNotFound`, `KeyInvalid`, `CardTerminated`, `WrongToken`) MUST be mapped to appropriate gematik fault codes and propagated as SOAP faults.

- **FR-202**: `ReadCertificate` (consumer `CertificateService`) MUST implement the following error table derived from Tab_Fehler_ReadCertificate (gemSpec_Basis_Consumer §6.3.1):

  | Code | ErrorType | Fehlertext                                | Condition                                                                     |
  | ---- | --------- | ----------------------------------------- | ----------------------------------------------------------------------------- |
  | 4000 | Technical | Syntaxfehler                              | `checkArguments` step — invalid or inconsistent parameters                    |
  | 4090 | Security  | Zugriff auf Identität nicht gestattet     | CardHandle references an eGK or HBAx (HBA, HBA-VK) — forbidden per A_24782-02 |
  | 4149 | Technical | Ungültige Zertifikatsreferenz             | `CertRef` value not resolvable to a known EF                                  |
  | 4258 | Technical | Zertifikate nicht vorhanden auf Identität | EF not found on the identity carrier (HSM / card)                             |

- **FR-203**: `VerifyCertificate` (consumer `CertificateService`) MUST NOT return numeric fault codes for the verification outcome itself; instead it MUST populate `CERT:VerificationStatus/CERT:VerificationResult` with one of three normative values (gemSpec_Basis_Consumer §6.3.2):

  | `CERT:VerificationResult` | Condition                                                                               |
  | ------------------------- | --------------------------------------------------------------------------------------- |
  | `VALID`                   | Temporal validity OK, mathematical validity OK, OCSP online valid                       |
  | `INVALID`                 | At least one of: temporal validity invalid, mathematical validity invalid, OCSP revoked |
  | `INCONCLUSIVE`            | OCSP status unknown AND all other checks valid                                          |

  If the verification *process itself* fails (e.g. unreachable OCSP, internal error), the operation MUST return a SOAP `FaultMessage` with code **4001**. INCONCLUSIVE and INVALID details MUST be carried in `CERT:VerificationStatus/GERROR:Error`.

- **FR-204**: `VerifyDocument` (consumer `SignatureService`) MUST return the signature verification outcome in `SIG:HighLevelResult` with normative values `VALID`, `INVALID`, or `INCONCLUSIVE` (gemSpec_Basis_Consumer §6.2.3). A process failure (core validation cannot complete) MUST yield a SOAP fault with code **4001**. Failure modes from `PL_TUC_VERIFY_DOCUMENT_nonQES` (no signature found, no certificate determinable, mathematical invalidity) MUST each set `SIG:HighLevelResult` to `INVALID` with appropriate detail in the accompanying error element.

- **FR-205**: For all SOAP operations, if a `CryptoProvider` operation returns a card TUC error code, the SICCT extension MUST map the TUC result to the following SOAP fault codes:

  | TUC Result Code              | SOAP Fault Code                                                               |
  | ---------------------------- | ----------------------------------------------------------------------------- |
  | `SecurityStatusNotSatisfied` | 4029 (security status not satisfied)                                          |
  | `ObjectNotFound`             | 4000 (invalid parameter / key reference)                                      |
  | `KeyInvalid`                 | 4001 (technical error — key unusable)                                         |
  | `CardTerminated`             | 4001 (technical error — card no longer usable)                                |
  | `PasswordBlocked`            | 4029 (PIN blocked)                                                            |
  | `WrongSecretWarning.X`       | 4029 (wrong PIN, X retries remaining — include remaining count in Trace text) |

- **FR-206**: Client-system authentication failure MUST return SOAP fault code **4204** (authentication required / invalid credentials) per TIP1-A_4516. A missing or invalid `AufrufKontext` mandatory field on the Konnektor interface MUST return code **4021** per TUC_KON_000. An `AufrufKontext.workplaceId` that exists in the system but does not belong to the `mandantId` in the same request MUST return code **4021**.

- **FR-207**: Requests for deferred Konnektor operations (per FR-157) MUST return SOAP fault code **7200** (service not implemented). Operations that would require hardware or context not available at the time of the request (e.g. CardHandle expired, card ejected) MUST return **4019** (timeout/exclusivity) or **4001** (technical error) as appropriate, never silently succeed or hang.

- **FR-208**: When two concurrent SOAP operations attempt to acquire exclusive access to the same CardHandle and the second request cannot acquire the lock within 30 seconds, the system MUST return fault code **4019** (per the Assumption in the spec). The lock wait timeout of 30 seconds MUST be configurable per alias.

- **FR-209**: `EncryptDocument` MUST reject encryption requests where any recipient certificate is expired or revoked; the fault MUST carry the failing certificate's subject and the reason (expired/revoked) in the `Trace` chain. `DecryptDocument` MUST return a SOAP fault with code **4001** if the private key referenced by the CardHandle is not available or the decryption algorithm cannot be determined from the encrypted document structure.

**FR-210 — FR-219: Error Handling — LDAP Proxy and KOM-LE**

- **FR-210**: The LDAP proxy MUST reject any LDAPv3 operation other than Bind, Unbind, Search, and Abandon with LDAP result code **53** (`unwillingToPerform`) per RFC 4511 and A_17341-01. All other RFC 4511 Appendix A error codes returned by the VZD MUST be forwarded unchanged to the client.

- **FR-211**: If the TLS connection to the VZD (`PL_TUC_VZD_BIND`) fails for any reason (network unreachable, certificate invalid, mutual-auth failure), the LDAP proxy MUST return an appropriate LDAP error to the client and log the failure at ERROR level with the VZD endpoint address and the TLS failure reason.

- **FR-212**: The KOM-LE SMTP interface (`openkim-server`) MUST respond to client commands in the CONNECT state per Tab_SMTP_Ant_Init (gemSpec_Basis_Consumer §6.5.4.1):

  | Condition                                            | SMTP Response                                                                                      |
  | ---------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
  | EHLO received                                        | `250 OK` + capability list (SIZE ≥ 35882577, AUTH LOGIN PLAIN, 8BITMIME, ENHANCEDSTATUSCODES, DSN) |
  | MAIL/RCPT/DATA before AUTH                           | `530 5.7.0` (authentication required)                                                              |
  | Unknown or unsupported command                       | `502 5.5.1` (command not implemented)                                                              |
  | AUTH with unsupported mechanism (not PLAIN or LOGIN) | `504 5.7.4` (security feature not supported)                                                       |
  | Connection to MTA fails                              | `454 4.7.0` (temporary authentication failure)                                                     |
  | MTA authentication fails                             | `535 5.7.8` (authentication credentials invalid)                                                   |
  | Successful MTA authentication                        | `235 2.7.0` (authentication successful)                                                            |

- **FR-213**: The KOM-LE POP3 interface MUST respond per Tab_POP3_Ant_Init (gemSpec_Basis_Consumer §6.5.4.2):

  | Condition                                                | POP3 Response |
  | -------------------------------------------------------- | ------------- |
  | AUTH with unsupported mechanism (not USER/PASS or PLAIN) | `-ERR`        |
  | Connection to POP3 server fails                          | `-ERR`        |
  | POP3 server authentication fails                         | `-ERR`        |
  | Any unrecognised command                                 | `-ERR`        |
  | Successful authentication                                | `+OK`         |

- **FR-214**: KOM-LE MUST abort outgoing message processing and return an error to the sending client if (A_17337) the required SM-B identity is not available, or (A_17338) the SM-B activation (`Freischaltung`) fails. The error MUST distinguish between the two abort conditions in the logged event; the SMTP client MUST receive a `4xx` temporary failure code so that the mail client can retry.

- **FR-215**: If the KOM-LE VZD lookup before sending (A_22664) reveals that the installed client-module version is below the version registered in VZD, the system MUST NOT abort sending; instead it MUST deliver an informational email to the sender (unsigned, unencrypted, per RFC 3461–3464 DSN format) and continue processing the original message.

**FR-216 — FR-219: Error Handling — TLS and PKI**

- **FR-216**: All outbound TLS connections (to VZD, to TI services via SZZP, to SICCT terminals) MUST be rejected immediately if any of the following occur in `PL_TUC_TLS_SECURE_CHANNEL`: FQDN not resolvable; server unreachable; server certificate mathematically invalid; server certificate expired; server certificate OCSP-revoked; server certificate role OID (`ROLLENBEZEICHNER`) mismatch; mutual-authentication failure. Each rejection MUST be logged at ERROR level with the target endpoint and specific failure cause.

- **FR-217**: Certificate verification via `PL_TUC_PKI_VERIFY_CERTIFICATE` MUST surface the following error flags when encountered (TIP1-A_6993): `CERT_READ_ERROR` (certificate unreadable — treat as INVALID); `CA_CERT_MISSING` or `AUTHORITYKEYID_DIFFERENT` (no valid issuer — MUST NOT consider the certificate valid); `OCSP_CERT_MISSING` or `OCSP_SIGNATURE_ERROR` (OCSP legitimacy cannot be established — certificate is not online-valid; abort OCSP check); `CERTHASH_EXTENSION_MISSING`, `CERTHASH_MISSMATCH`, or `WARNING_CERT_UNKNOWN` (OCSP check not successful — not online-valid). The system MUST log each flag at WARN level with the certificate subject and the failing check.

- **FR-218**: When `TOLERATE_OCSP_FAILURE=yes` is configured for an operation (FR-053, FR-032), a transient OCSP failure MUST result in `INCONCLUSIVE` (not `INVALID`) and MUST be logged at WARN level. When `TOLERATE_OCSP_FAILURE=no`, any OCSP failure MUST result in `INVALID` and the operation MUST be rejected.

- **FR-219**: DNS resolution failures (`PL_TUC_NET_NAME_RESOLUTION`) MUST cause the requesting operation to abort immediately; no retry MUST be attempted within the same request scope. The failure MUST be logged at ERROR level with the FQDN that could not be resolved. If the Caching Nameserver itself is unavailable at startup, all dependent services MUST remain in `UNAVAILABLE` state and the application readiness probe MUST return unhealthy per FR-127.

**FR-220 — FR-234: JMX Management Beans**

- **FR-220**: All JMX management beans MUST be implemented following the JDK standard MBean pattern per JSR 3 (`javax.management.*`): each bean MUST define a companion `XxxMBean` interface listing all exposed operations and attributes, and a concrete `@ApplicationScoped` CDI class `Xxx` implementing that interface; the concrete class MUST register itself with `ManagementFactory.getPlatformMBeanServer()` in a `@PostConstruct` method and deregister in a `@PreDestroy` method. Per Principle VIII, `javax.management.MBeanServer` and the standard MBean naming convention are the mandated JDK interfaces for management instrumentation; parallel custom management abstractions MUST NOT be introduced. The `ObjectName` for all project MBeans MUST use domain `de.servicehealtherx` with required keys `module=<module-folder-name>` and `name=<ClassName>`. Example: `de.servicehealtherx:module=crypto-lib,name=TslManagement`. MBean packages MUST follow FR-005: `de.servicehealtherx.<module>.jmx`.

- **FR-221**: The `quarkus-ldap-proxy-server-extension` runtime (`de.servicehealtherx.quarkus.ldap.proxy.server.runtime.jmx`) MUST expose `DnsServiceDiscoveryManagementMBean` / `DnsServiceDiscoveryManagement` (`de.servicehealtherx:module=quarkus-ldap-proxy-server-extension,name=DnsServiceDiscoveryManagement`) with:
  - `triggerServiceDiscovery(domain: String): String` — re-executes DNS-SD for the given TI domain name (PTR query per FR-125 / TUC_CON_362); if `domain` is empty or null, rediscovers all configured TI zones; returns a JSON array of discovered SRV records
  - `getDiscoveredServices(): String` — returns a JSON snapshot of all currently cached DNS-SD results across all configured TI service zones

- **FR-222**: The `quarkus-sicct-extension` runtime (`de.servicehealtherx.quarkus.sicct.runtime.jmx`) MUST expose `SicctTerminalDiscoveryManagementMBean` / `SicctTerminalDiscoveryManagement` (`de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalDiscoveryManagement`) with:
  - `triggerDiscovery(): void` — sends the SICCT UDP service-discovery broadcast on the configured port (FR-091); invokes `SicctDiscoveryHandler` to process any responding terminals; newly discovered terminals are persisted as `CardTerminal` JPA entities and connection attempts are initiated immediately
  - `getLastDiscoveryResult(): String` — returns a JSON snapshot of the most recently discovered terminals: `[{ "hostname": "...", "ipAddress": "...", "macAddress": "...", "tcpPort": 4876, "discoveredAt": "..." }]`

- **FR-223**: `crypto-lib` (`de.servicehealtherx.crypto.jmx`) MUST expose `TslManagementMBean` / `TslManagement` (`de.servicehealtherx:module=crypto-lib,name=TslManagement`) with:
  - `reloadTsl(): String` — forces an immediate synchronous TSL download bypassing the next scheduled refresh; returns a JSON result: `{ "status": "OK|FAILED", "sequenceNumber": N, "expiry": "...", "downloadedAt": "...", "error": "..." }`
  - `getTslStatus(): String` — returns the current TSL state as a JSON object with `sequenceNumber`, `expiry`, `downloadedAt`, `status` (VALID / EXPIRED / UNAVAILABLE)
  - `getTslUrl(): String` (read-only attribute) — the configured TSL download URL

- **FR-224**: The `quarkus-sicct-extension` runtime MUST expose `SicctTerminalConnectionManagementMBean` / `SicctTerminalConnectionManagement` (`de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalConnectionManagement`) with:
  - `connect(terminalId: String): String` — initiates an explicit TCP connection attempt to the specified terminal; no-op if already CONNECTED; returns the resulting connection state
  - `disconnect(terminalId: String): String` — gracefully closes the TCP connection to the specified terminal (in-flight APDUs complete or timeout per `apduTimeoutMs`); suspends automatic reconnection; returns the resulting state
  - `getTerminalStatus(terminalId: String): String` — returns connection state, host, port, pairing status, and active slot count as a JSON object
  - `listAllTerminals(): String` — returns a JSON array of all registered terminals with current connection state, host, port, and slot count

- **FR-225**: The `quarkus-sicct-extension` runtime MUST expose `CardPinManagementMBean` / `CardPinManagement` (`de.servicehealtherx:module=quarkus-sicct-extension,name=CardPinManagement`) with:
  - `verifyPin(terminalId: String, slotId: int, pinType: String): String` — triggers PIN verification for the specified card slot via the terminal's trusted PIN pad (SICCT VERIFY PIN command per FR-029); `pinType` is one of `HBA.PIN.CH`, `HBA.PIN.QES`, `SMC-B.PIN.SMC`; the PIN MUST NOT pass through JVM memory; returns `{ "result": "OK|REJECTED|BLOCKED", "retriesRemaining": N }`
  - `getPinStatus(terminalId: String, slotId: int, pinType: String): String` — reads current PIN status (active / blocked / retries remaining) via SICCT without requiring PIN entry; returns a JSON object

- **FR-226**: `crypto-lib` MUST expose `CryptoProviderManagementMBean` / `CryptoProviderManagement` (`de.servicehealtherx:module=crypto-lib,name=CryptoProviderManagement`) with:
  - `listKeyStores(): String` — returns a JSON array of all registered `KeyStoreAdapter` instances: `[{ "storeType": "SICCT", "aliases": [...], "availability": "AVAILABLE|UNAVAILABLE|ERROR" }]`
  - `listKeyReferences(): String` — returns a JSON array of all `KeyReference` objects across all adapters: `[{ "alias": "...", "storeType": "...", "algorithm": "...", "keyUsage": "...", "certificateSubject": "...", "certificateExpiry": "..." }]`
  - `getAvailability(alias: String): String` — returns the `AvailabilityStatus` for the given alias; returns `"KEY_NOT_FOUND"` if unknown
  - `getAvailabilities(): String` — returns a JSON map of alias → availability for all registered aliases

- **FR-227**: `crypto-lib` MUST expose `KeyStoreReloadManagementMBean` / `KeyStoreReloadManagement` (`de.servicehealtherx:module=crypto-lib,name=KeyStoreReloadManagement`) with:
  - `reloadKeyStore(storeType: String, alias: String): String` — triggers `engineLoad(null, null)` on the `KeyStoreAdapter` of the given type that manages the specified alias; for P12 adapters this re-reads the file from disk; for PKCS#11 this re-opens the HSM session; for PC/SC this re-detects the card; for SICCT this is a no-op (connections are managed by the extension); returns the new availability status and any error message
  - `reloadAllKeyStores(): String` — triggers `engineLoad(null, null)` on all registered `KeyStoreAdapter` instances; returns a JSON map of adapter identity → result; adapters that fail reload are marked UNAVAILABLE, not removed

- **FR-228**: `sicct-lib` (`de.servicehealtherx.sicct.jmx`) MUST expose `BackupRestoreManagementMBean` / `BackupRestoreManagement` (`de.servicehealtherx:module=sicct-lib,name=BackupRestoreManagement`) with:
  - `exportBackup(): String` — triggers the backup preparation procedure (FR-180): generates a random 256-bit backup password, unseals each terminal's `ShS.KT.AUT` via the TPM, AES-256-GCM-encrypts it under the derived key, persists `backupEncryptedSharedSecret` for each paired `CardTerminal`, and returns the backup password as a plaintext string displayed exactly once; the backup password MUST NOT be persisted anywhere; the retention window starts upon return; requires Hawtio authentication (FR-232)
  - `importBackup(backupPassword: String): String` — triggers the restore procedure (FR-181): decrypts and re-seals all `backupEncryptedSharedSecret` entries to the current host's TPM; returns a JSON result per terminal; emits CRITICAL-level audit entries per FR-181
  - `getBackupStatus(): String` — returns `{ "pairedTerminals": N, "terminalsWithBackup": N, "retentionWindowExpiry": "...|null" }`

- **FR-229**: `crypto-services-lib` (`de.servicehealtherx.crypto.services.jmx`) MUST expose `SignatureServiceManagementMBean` / `SignatureServiceManagement` (`de.servicehealtherx:module=crypto-services-lib,name=SignatureServiceManagement`) with diagnostic access to every `SignatureService` operation; all binary parameters and return values use Base64 encoding:
  - `sign(alias: String, algorithm: String, dataBase64: String): String` — invokes `CryptoProvider.sign()` and returns the DER signature as Base64
  - `verify(alias: String, algorithm: String, dataBase64: String, signatureBase64: String): boolean` — invokes `CryptoProvider.verify()` and returns the boolean result
  - `externalAuthenticate(alias: String, signatureType: String, hashBase64: String): String` — invokes the ExternalAuthenticate hash-signing flow; returns the signature in Base64
  - `getSignatureMode(): String` — returns the current comfort-signature mode state (active / inactive); returns `"NOT_IMPLEMENTED"` for deferred operations (FR-157)
  - All invocations MUST emit an audit log entry identical to those produced by SOAP-layer invocations (FR-016)

- **FR-230**: `crypto-services-lib` MUST expose `EncryptionServiceManagementMBean` / `EncryptionServiceManagement` (`de.servicehealtherx:module=crypto-services-lib,name=EncryptionServiceManagement`) with:
  - `encryptDocument(alias: String, algorithm: String, documentBase64: String, recipientCertBase64: String): String` — invokes hybrid encryption and returns the encrypted document in Base64; `recipientCertBase64` is the DER-encoded X.509 recipient certificate in Base64; for multi-recipient encryption, pass a JSON array of Base64 certs as `recipientCertBase64`
  - `decryptDocument(alias: String, ciphertextBase64: String): String` — invokes hybrid decryption and returns the plaintext document in Base64
  - All invocations MUST emit audit log entries per FR-016

- **FR-231**: `crypto-services-lib` MUST expose `CertificateServiceManagementMBean` / `CertificateServiceManagement` (`de.servicehealtherx:module=crypto-services-lib,name=CertificateServiceManagement`) with:
  - `readCertificate(alias: String, certRef: String, crypt: String): String` — invokes the ReadCertificate operation and returns the DER-encoded X.509 certificate in Base64; `certRef` is one of `C.AUT`, `C.OSIG`; `crypt` is `RSA` or `ECC`
  - `verifyCertificate(certificateBase64: String): String` — invokes TI PKI verification (OCSP, chain) and returns `{ "result": "VALID|INVALID|INCONCLUSIVE", "detail": "..." }`

- **FR-232**: All JMX operations that modify system state (TSL reload, terminal connect/disconnect, PIN verify, backup export/import, KeyStore reload) MUST be protected by Hawtio role-based access control; unauthenticated remote JMX access MUST be disabled in production (`-Dcom.sun.jndi.rmi.object.trustURLCodebase=false`; no remote JMX connector outside what Hawtio requires internally). MBeans MUST be registered exclusively on `ManagementFactory.getPlatformMBeanServer()`; no additional JMX connector server MUST be opened.

- **FR-233**: JMX operations that delegate to potentially long-running actions (TSL download, SICCT connect, backup export/import) MUST enforce the operation's natural timeout and return synchronously within that timeout. If the operation cannot complete within its timeout (TSL: 60 s, SICCT connect: 10 s, backup: 120 s), the JMX operation MUST return an error string rather than blocking the Hawtio UI indefinitely; the incomplete action MUST be logged at ERROR level.

- **FR-234**: Each JMX MBean class MUST be covered by a unit test that: (a) registers the MBean with a test `MBeanServer`, (b) invokes each operation via `MBeanServer.invoke()` to verify the JMX plumbing, and (c) asserts that the underlying service dependency (mocked) was called with the correct arguments. The test class MUST be named `XxxManagementTest` and the test methods MUST reference the MBean's class name and the FR number (e.g., `test_FR220_TslManagement_reloadTsl_invokes_tsl_downloader()`).

---

### Normative TUC References (Appendix C — gemSpec_Basis_Consumer_V1.12.1)

The following Platform TUCs from `gemSpec_Systemprozesse_dezTI_V1.4.1` MUST be implemented and provided by the Basis Consumer. This table is the normative source per **Anhang C / Tabelle 39: TAB_Systemprozesse** of gemSpec_Basis_Consumer_V1.12.1.

| TUC Identifier                       | English Description                                                                                                                                                                    | Implementing Maven Module               | FR References  |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- | -------------- |
| `PL_TUC_HYBRID_ENCIPHER`             | Hybrid-encrypt a document (XMLEnc for XML; CMS for binary); generates session key, encrypts symmetrically, wraps session key per recipient certificate (RSA or ECC)                    | `crypto-services-lib`                   | FR-031, FR-075 |
| `PL_TUC_HYBRID_DECIPHER`             | Hybrid-decrypt a document; determine algorithm from hybrid key; invoke card/HSM private key to unwrap session key; decrypt body                                                        | `crypto-services-lib`                   | FR-034, FR-081 |
| `PL_TUC_SIGN_DOCUMENT_nonQES`        | Sign a document nonQES in CMS or XML format using ECDSA; builds on PL_TUC_SIGN_HASH_nonQES; supported profiles: CMS/CAdES-BES                                                          | `crypto-services-lib`                   | FR-041, FR-075 |
| `PL_TUC_SIGN_HASH_nonQES`            | Sign a hash with a TI card identity (nonQES); ECDSA-in-CMS via SMC-B or HSM; inputs: IDENTIFIKATOR, SIGNATURVERFAHREN, HASHWERT                                                        | `crypto-services-lib`                   | FR-043, FR-045 |
| `PL_TUC_VERIFY_DOCUMENT_nonQES`      | Verify a nonQES document signature (CMS or XML); CoreValidation + certificate check via PL_TUC_PKI_VERIFY_CERTIFICATE; inputs: SIGNED_DOCUMENT, CERTIFICATE, SIGNATURE, TIME_REFERENCE | `crypto-services-lib`                   | FR-044, FR-081 |
| `PL_TUC_PKI_VERIFY_CERTIFICATE`      | Verify an X.509 TI certificate: temporal validity, mathematical validity, OCSP revocation; inputs: Certificate, PolicyList, KeyUsage, ExtendedKeyUsage, TOLERATE_OCSP_FAILURE, Timeout | `crypto-lib`                            | FR-032, FR-053 |
| `PL_TUC_VZD_BIND`                    | Connect to VZD via LDAPS on port 636; validate C.ZD.TLS-S server certificate (oid_vzd_ti, serverAuth, TOLERATE_OCSP_FAILURE=false)                                                     | `quarkus-ldap-proxy-server-extension`   | FR-060         |
| `PL_TUC_VZD_SEARCH`                  | LDAPv3 Search Request to VZD per RFC-4511 §4.5.1                                                                                                                                       | `quarkus-ldap-proxy-server-extension`   | FR-060         |
| `PL_TUC_VZD_UNBIND`                  | LDAPv3 Unbind from VZD                                                                                                                                                                 | `quarkus-ldap-proxy-server-extension`   | FR-060         |
| `PL_TUC_VZD_ABANDON`                 | LDAPv3 Abandon an in-progress directory query                                                                                                                                          | `quarkus-ldap-proxy-server-extension`   | FR-060         |
| `PL_TUC_NET_SYNC_TIME`               | Synchronize system clock with TI NTP time server (I_NTP_Time_Informationen)                                                                                                            | `openkim-server`                        | FR-074, FR-120 |
| `PL_TUC_CARD_INFORMATION`            | Collect card status information: card type, ICCSN, available applications, PIN status                                                                                                  | `sicct-lib` / `quarkus-sicct-extension` | FR-090         |
| `PL_TUC_CARD_RESET`                  | Reset card logical channel                                                                                                                                                             | `sicct-lib`                             | FR-090         |
| `PL_TUC_CARD_CHANGE_PIN`             | Change card PIN; inputs: card handle, old PIN, new PIN                                                                                                                                 | `sicct-lib`                             | FR-145         |
| `PL_TUC_CARD_ENABLE_PIN`             | Enable PIN protection on a card application                                                                                                                                            | `sicct-lib`                             | FR-145         |
| `PL_TUC_CARD_DISABLE_PIN`            | Disable PIN protection on a card application                                                                                                                                           | `sicct-lib`                             | FR-145         |
| `PL_TUC_CARD_VERIFY_PIN`             | Verify user PIN; triggers secure PIN entry on card terminal trusted display; PIN transmitted to card only                                                                              | `sicct-lib`                             | FR-049, FR-144 |
| `PL_TUC_CARD_ACTIVATE_APPLICATION`   | Make a hidden card application visible                                                                                                                                                 | `sicct-lib`                             | FR-090         |
| `PL_TUC_CARD_DEACTIVATE_APPLICATION` | Hide a card application                                                                                                                                                                | `sicct-lib`                             | FR-090         |
| `PL_TUC_CARD_GET_CHALLENGE`          | Read a random number from the card (used in authentication protocols)                                                                                                                  | `sicct-lib`                             | FR-090, FR-131 |

**Additional TUCs referenced in the normative text but not listed in Appendix C** (used indirectly or by the network layer):

| TUC Identifier               | Context                                                                                                                                                             | FR Reference   |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| `PL_TUC_TLS_SECURE_CHANNEL`  | Mutual TLS connection to TI via SZZP; server cert validated via PL_TUC_PKI_VERIFY_CERTIFICATE (oid_fd_tls_s); client auth via PL_TUC_SIGN_HASH_nonQES (PrK.HCI.AUT) | FR-120         |
| `PL_TUC_NET_NAME_RESOLUTION` | TI DNS resolution for TI-internal service endpoints (DNS-SD for VZD: `_ldap._tcp.vzd.<TI_DNS_TOP_LEVEL_DOMAIN>`)                                                    | FR-062, FR-120 |

---

### Key Entities

- **Tenant**: An organizational unit (healthcare institution or practice) with its own Kubernetes namespace, one or more SM-B identities, isolated configuration, and isolated audit logs.
- **CardHandle**: A runtime reference to a specific cryptographic identity — either an HSM-backed virtual card or a physical card in a SICCT terminal slot or PC/SC reader. Scoped to a tenant. Expires with the session or on card removal.
- **KeyAlias**: A unique string in format `<source-type>/<user-defined-name>` (e.g., `pkcs11/utimaco-slot0`, `sicct/terminal-1-slot0`) identifying a private key within the unified CryptoProvider. Globally unique per tenant instance.
- **Identity (HSM or Card)**: A cryptographic identity represented by X.509 certificates and private keys (SM-B-ORG, SM-B-KTR, eHBA). Private keys reside exclusively in their configured key source.
- **CardTerminal**: A persisted SICCT terminal configuration. Fields: UUID primary key (`ctid`), `hostname` (SICCT FriendlyName), `ipAddress`, `tcpPort`, `macAddress`, `physical` flag, `slotCount`, `slotsUsed`, `correlation` state (BEKANNT/ZUGEWIESEN/GEPAIRT/AKTIV/AKTUALISIEREND), `connected` flag, `activeRole`, `smktAutCertificate`, `sealedSharedSecret` (TPM-sealed), `backupEncryptedSharedSecret`. Stored in the database; managed exclusively via the management console.
- **SICCT Terminal**: The runtime representation of a connected SICCT card terminal. Backed by a CardTerminal. Managed by the SICCT Quarkus extension. Has one or more card slots and a connection state (CONNECTING / CONNECTED / DISCONNECTED / FAILED).
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
- SICCT card terminals reside in the same LAN as the Primärsystem / client systems (PVS / KIS / AVS); the `basis-consumer-server` connects to terminals over TCP/IP across that network boundary. No dedicated terminal aggregator is required; terminals MUST be IP-reachable from the Kubernetes cluster via standard network routing.
- All production application configuration is stored in the JavaDB `AppConfigProperty` table and read via `configsource-db` (FR-028); no production-relevant configuration resides in filesystem config files. SICCT card terminal configurations (`hostname`, `ipAddress`, `tcpPort`, and all correlation/pairing fields) are stored exclusively as `CardTerminal` JPA entities; `quarkus.sicct.terminals[*]` config keys do not exist in this system.
- Per-terminal timeout values fall back to global defaults defined in the config file if not explicitly set per-record; individual DB overrides take precedence.
- The system must support at least 20 SICCT terminals per tenant without degradation; historical terminal records are not retained after deletion.
- A Sicherer Zugangspunkt zur Telematikinfrastruktur (SZZP) is pre-configured and reachable before the Basis Consumer starts; the SZZP provides TI connectivity without a traditional VPN tunnel.
- The KOM-LE client module uses an existing MTA (Mail Transfer Agent) in the tenant's environment; the Basis Consumer does not operate its own MTA.
- The openkim fork must be created and the submodule reference established as a prerequisite before KOM-LE implementation begins.
- eHBA cards are accessed exclusively through SICCT card terminals; direct HSM-virtualization of eHBA keys is out of scope for v1.
- The Konnektor compatibility interface targets gemSpec_Kon_V5.27.0 (Stand: 21.01.2026); backward compatibility with older Konnektor versions is not guaranteed.
- QES (Qualified Electronic Signatures) is in scope; the HBA's `PrK.HP.QES` key is used for QES operations via SICCT terminal or PC/SC reader; a qualified trust service provider (TSP) listed in the EU Trusted List is a prerequisite for production QES deployment but is not managed by this system.
- PC/SC support requires the host OS to provide a PC/SC middleware layer (pcscd on Linux); the Basis Consumer does not bundle or manage PC/SC middleware.
- SoftHSM2 (software PKCS#11 emulator) is acceptable as a CI substitute for a physical HSM; production deployments MUST use a FIPS 140-2 Level 3 or CC EAL4 certified HSM.
- Interactive PIN entry is required for both SICCT and PC/SC providers (FR-029). For SICCT: the terminal's trusted PIN pad is used via the SICCT VERIFY PIN command; remote-PIN via gSMC-KT trusted channel is additionally supported per TIP1-A_5012 and FR-141. For PC/SC: class-2/3 readers with an integrated PIN pad use `FEATURE_VERIFY_PIN_DIRECT`; class-1 readers without a PIN pad fall back to a statically configured PIN. Static PIN configuration is acceptable only for non-interactive automated scenarios (e.g. SM-B in CI) or class-1 readers.
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
