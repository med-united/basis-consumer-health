# Feature Specification: ECIES Transport Encryption in EncryptionService

**Feature Branch**: `009-ecies-encryption-service`

**Created**: 2026-06-15

**Status**: Draft

**Input**: User description: "Implement ECIES in EncryptionService in crypto-lib using Java JCE as an abstraction for e.g. Cipher and MessageDigest. The algorithm is described here https://gemspec.gematik.de/docs/gemSpec/gemSpec_Krypt/latest/#4.7 in section 4.7. Please make sure that you use the correct ASN.1 data structure including the correct keyEncryptionAlgorithm oid_ti_ecies_transport_encryption 1.2.276.0.76.4.222"

## Clarifications

### Session 2026-06-15

- Q: When the recipient private key lives on a smart card (eGK/SMC-B/HBA), how is the EC key agreement performed during decryption? → A: Behind a standard JCE provider/SPI (a `CipherSpi`/`KeyAgreementSpi` that issues APDUs to the encapsulated card). The `EncryptionService` uses only standard JCE abstractions and stays unaware of whether a key is software- or card-resident; both decrypt through the same seam.
- Q: On decryption of a (possibly multi-recipient) object, how is the recipient entry / key selected? → A: The caller supplies the decryption key alias; the service uses that one key to locate its recipient entry and rejects with a "no matching key" error if no entry corresponds.
- Q: How are decryption failures reported to the caller? → A: Distinguishable reasons (unsupported-algorithm, malformed/truncated, no-matching-key, integrity-failure).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Encrypt a document for one or more elliptic-curve recipients (Priority: P1)

A caller submits a document together with one or more elliptic-curve recipient certificates and asks the encryption service to protect the document so that any one of the named recipients — and only those recipients — can read it (e.g. an eGK, SMC-B, or HBA card holder, or a TI back-end component). The service produces a single self-contained encrypted object: the document is encrypted once with a content-encryption key, and that content-encryption key is ECIES-wrapped separately for each recipient (the per-recipient `keyEncryptionAlgorithm`). The object conforms to the gematik ECIES transport-encryption scheme and can be decrypted by any gematik-conformant Telematikinfrastruktur (TI) component holding any one of the recipient private keys.

**Why this priority**: Producing TI-conformant ciphertext addressable to the intended recipient(s) is the core value of the feature. Today the service contains a placeholder key-wrap that does not produce interoperable output; without correct encryption no confidential document can be exchanged with TI partners. Real exchanges routinely target several recipients (e.g. an insured person plus a practice), so multi-recipient support is part of the minimum viable capability. This story alone delivers a usable, demonstrable capability.

**Independent Test**: Provide one or more known elliptic-curve recipient public keys and a sample document; confirm the produced object carries the mandated key-encryption algorithm identifier for each recipient, contains one ephemeral public key and wrapped content key per recipient plus a single authenticated ciphertext, and is byte-structure-conformant to the gematik specification. Interoperability is confirmed by decrypting the object with each recipient's matching private key (and an independent gematik-conformant reference) and recovering the identical original document in every case.

**Acceptance Scenarios**:

1. **Given** a single valid, trust-verified elliptic-curve recipient certificate and a document, **When** the caller requests encryption, **Then** the service returns an encrypted object whose per-recipient key-encryption algorithm identifier is the OID `1.2.276.0.76.4.222` (`oid_ti_ecies_transport_encryption`).
2. **Given** the same inputs, **When** encryption completes, **Then** the encrypted object contains, for the recipient, a freshly generated ephemeral public key on the same elliptic curve as the recipient key and an ECIES-wrapped content key, plus one authenticated symmetric ciphertext of the document.
3. **Given** several valid elliptic-curve recipient certificates and a document, **When** the caller requests encryption, **Then** the service returns one object containing one recipient entry per certificate (each with its own ephemeral public key and wrapped content key) and a single shared document ciphertext, such that **each** recipient can independently decrypt and recover the identical original document.
4. **Given** two encryption requests for the same document and recipient set, **When** both complete, **Then** the two outputs differ (new ephemeral keys and a new content key are generated each time), and both decrypt to the identical original document.
5. **Given** a recipient certificate whose public key uses an elliptic curve not permitted by the gematik specification, **When** the caller requests encryption, **Then** the service rejects the request with a clear error and produces no output.
6. **Given** an empty recipient list, **When** the caller requests encryption, **Then** the service rejects the request with a clear "no recipient" error and produces no output.

---

### User Story 2 - Decrypt a document received via TI (Priority: P2)

A caller receives an ECIES-encrypted object created by a gematik-conformant TI partner — possibly addressed to several recipients — and asks the service to recover the original document using a decryption key it names by alias (the service accesses that key through its configured key providers via standard JCE abstractions, whether the key is software-resident or held on a smart card). The service locates the recipient entry corresponding to the named key, unwraps the content-encryption key from that entry, and decrypts the shared document ciphertext.

**Why this priority**: Decryption is required to consume confidential documents from TI partners and to close the round-trip. It depends on the same primitives as encryption but is only useful once encryption exists, so it is P2.

**Independent Test**: Supply an encrypted object produced by an independent gematik-conformant tool together with access to the matching private key; confirm the service recovers the original plaintext byte-for-byte.

**Acceptance Scenarios**:

1. **Given** a well-formed ECIES object carrying the OID `1.2.276.0.76.4.222` and access to the matching private key, **When** the caller requests decryption, **Then** the service returns the original document unchanged.
2. **Given** a multi-recipient object and access to exactly one of the recipients' private keys, **When** the caller requests decryption, **Then** the service selects that recipient's entry, recovers the content key, and returns the original document — without needing any other recipient's key.
3. **Given** a multi-recipient object and access to none of the recipients' private keys, **When** the caller requests decryption, **Then** the service rejects it with a clear "no matching key" error and returns no plaintext.
4. **Given** an object whose authenticated ciphertext has been altered after creation, **When** the caller requests decryption, **Then** the service detects the integrity failure and refuses to return any plaintext.
5. **Given** an object whose key-encryption algorithm identifier is not the supported ECIES OID, **When** the caller requests decryption, **Then** the service rejects it with a clear "unsupported algorithm" error.
6. **Given** a truncated or structurally invalid encrypted object, **When** the caller requests decryption, **Then** the service rejects it with a clear "malformed input" error and returns no plaintext.

---

### User Story 3 - Verifiable interoperability and conformance (Priority: P3)

A maintainer needs assurance that the service's encrypted output is wire-compatible with gematik tooling and that the service can consume gematik-produced input, so that conformance can be demonstrated during certification and regression-tested over time.

**Why this priority**: Round-trip interoperability with an external reference is what proves conformance, but it is a verification concern layered on top of the working encrypt/decrypt capabilities, so it is P3.

**Independent Test**: Run a cross-tool round-trip — encrypt with the service and decrypt with an independent reference, and vice-versa — against the published gematik test vectors for the scheme; confirm both directions succeed and the data structure matches the specification field-by-field.

**Acceptance Scenarios**:

1. **Given** the published gematik test vector(s) for the scheme, **When** the service decrypts the supplied ciphertext, **Then** it reproduces the expected plaintext.
2. **Given** a document encrypted by the service, **When** an independent gematik-conformant tool decrypts it, **Then** the tool recovers the original document without errors.
3. **Given** the encrypted object emitted by the service, **When** its structure is validated against the gematik ASN.1 definition, **Then** every field (algorithm identifier, ephemeral key, ciphertext) is present, correctly typed, and correctly ordered.

---

### Edge Cases

- A recipient certificate carrying an RSA public key (not elliptic-curve) is supplied — the service MUST NOT attempt ECIES and MUST signal that the recipient is unsuitable for this scheme (see Assumptions for RSA-recipient handling boundary).
- The recipient public key is on a curve the service does not support — rejected before any cryptographic work.
- An empty recipient list is supplied — rejected with a clear "no recipient" error before any cryptographic work.
- The recipient list contains duplicate certificates, or several recipients on different supported curves — each recipient still receives its own correctly-curved ephemeral key and wrapped content key.
- One certificate in a multi-recipient request fails trust verification or sits on an unsupported curve — the whole request is rejected with a clear error identifying the offending recipient; no partial object is produced.
- An empty (zero-length) document is submitted — the service still produces a valid, decryptable object that recovers an empty document.
- A very large document is submitted — the service either encrypts it within the performance budget or fails cleanly with a size-related error rather than exhausting memory.
- On decryption, the ephemeral public key embedded in the object is not a valid point on the expected curve — rejected as malformed before key agreement.
- The configured key providers cannot supply the private key matching the encrypted object — decryption fails with a clear "no matching key" error and no partial output.
- Concurrent encryption/decryption requests are processed without one request's ephemeral key, symmetric key, or buffer leaking into another.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The encryption service MUST encrypt a document for one or more elliptic-curve recipients using the gematik ECIES transport-encryption scheme defined in gemSpec_Krypt §4.7, accepting a list of recipient certificates and producing a single encrypted object that any one of the recipients can decrypt.
- **FR-002**: Each recipient entry in the encrypted object MUST identify its key-encryption algorithm using the OID `1.2.276.0.76.4.222` (`oid_ti_ecies_transport_encryption`).
- **FR-003**: The encrypted object MUST be serialized in the exact ASN.1 data structure mandated by gemSpec_Krypt §4.7, including correct field types, tags, and ordering, and MUST carry one recipient entry per recipient, so that gematik-conformant components can parse it without prior agreement.
- **FR-004**: The service MUST encrypt the document exactly once with a single freshly generated content-encryption key, and MUST ECIES-wrap that content-encryption key independently for each recipient; the recovered document MUST be identical for every recipient.
- **FR-005**: For each recipient the service MUST generate a fresh, single-use ephemeral elliptic-curve key pair on the same curve as that recipient's public key, include the ephemeral public key in that recipient's entry, and derive that recipient's key-wrapping secret from the shared secret produced by elliptic-curve key agreement between the ephemeral private key and the recipient public key, using the key-derivation function mandated by gemSpec_Krypt §4.7.
- **FR-006**: The service MUST protect the document with an authenticated symmetric encryption scheme as mandated by gemSpec_Krypt §4.7, such that any modification of the document ciphertext is detectable on decryption.
- **FR-007**: The service MUST decrypt an ECIES object carrying the supported OID by using the caller-supplied decryption key (named by alias) to locate the matching recipient entry, performing EC key agreement between that key and the entry's ephemeral public key, recovering the content-encryption key, and verifying integrity before returning plaintext. If the object contains no recipient entry corresponding to the supplied key, the service MUST reject it with a "no matching key" error and return no plaintext.
- **FR-008**: Encryption and decryption MUST round-trip: any document encrypted by the service MUST be recoverable byte-for-byte by the service (and by a gematik-conformant counterpart) using any one of the addressed recipients' private keys.
- **FR-009**: The service MUST support the elliptic curve(s) mandated by gemSpec_Krypt for this scheme, MUST allow different recipients in the same request to use different supported curves, and MUST reject recipient or ephemeral keys on unsupported curves with a clear error.
- **FR-010**: The service MUST reject an encryption request that names no recipient, and MUST reject the entire request (producing no output) if any named recipient certificate fails trust verification, consistent with the service's existing recipient-certificate validation.
- **FR-011**: On decryption, the service MUST reject objects that (a) carry an unsupported algorithm identifier, (b) are structurally malformed or truncated, or (c) fail the authenticity/integrity check — in every case returning no plaintext and a distinguishable error reason.
- **FR-012**: The service MUST NOT expose secret key material (ephemeral private key, derived symmetric key, recovered plaintext) in logs, audit records, or error messages; audit records MUST record the operation, algorithm, recipient/key reference, caller identity, and outcome only.
- **FR-013**: Each encryption and decryption operation MUST produce an audit record (success or failure) consistent with the service's existing auditing behavior.
- **FR-014**: The cryptographic primitives MUST be obtained through the platform's standard Java Cryptography (JCE) abstractions for the cipher, message digest, key agreement, and key generation, rather than bespoke cryptographic arithmetic, except where the gematik ASN.1 container requires explicit structure handling.
- **FR-015**: Decryption private-key operations (EC key agreement) MUST be performed through the standard JCE provider abstraction (e.g. a `KeyAgreement`/`Cipher` SPI). The `EncryptionService` MUST remain unaware of whether the named key is software-resident or held on a smart card: smart-card keys are served by a JCE provider/SPI that issues the necessary card commands (APDUs) transparently, so the identical decryption code path serves both software (P12) and card-based (eGK/SMC-B/HBA) keys.

### Key Entities *(include if data involved)*

- **Encryption request**: A document to protect, one or more elliptic-curve recipient certificates, the caller identity, and the document type — the inputs needed to produce an ECIES object.
- **Decryption request**: An ECIES-encrypted object, a reference to the key that should decrypt it, and the caller identity.
- **ECIES encrypted object**: The self-contained output — the gematik-mandated ASN.1 structure carrying one recipient entry per recipient (each with the key-encryption algorithm identifier `oid_ti_ecies_transport_encryption`, that recipient's ephemeral public key, and the ECIES-wrapped content-encryption key) together with a single authenticated ciphertext of the document.
- **Recipient entry**: The per-recipient portion of the object that lets exactly one recipient recover the content-encryption key — its algorithm identifier, ephemeral public key, and wrapped content key.
- **Content-encryption key**: A single freshly generated symmetric key that encrypts the document once; it is ECIES-wrapped separately for each recipient and never leaves the service in the clear.
- **Recipient key pair**: A recipient's elliptic-curve public key (from the certificate, used for encryption) and the corresponding private key (accessed via the configured key providers, used for decryption).
- **Ephemeral key pair**: A single-use elliptic-curve key pair generated per recipient per encryption, whose public part travels in that recipient's entry and whose private part is discarded immediately after key derivation.
- **Audit record**: The operation, algorithm, key/recipient reference, caller identity, timing, and outcome of each cryptographic operation — never the protected data or key material.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of documents encrypted by the service for one or more valid elliptic-curve recipients are decrypted successfully and recovered byte-for-byte by an independent gematik-conformant counterpart holding **any one** of the addressed recipients' private keys.
- **SC-002**: 100% of the published gematik test vectors for the scheme decrypt to their expected plaintext, and structure validation of the service's output matches the gematik ASN.1 definition with zero field discrepancies.
- **SC-003**: Every encrypted object emitted by the service carries the key-encryption algorithm identifier `1.2.276.0.76.4.222` — verified on 100% of outputs in conformance testing.
- **SC-004**: 100% of tampered, truncated, unsupported-algorithm, or wrong-curve inputs are rejected with no plaintext returned and a distinguishable error reason — zero cases where altered ciphertext yields output.
- **SC-005**: Two encryptions of the same document for the same recipient produce different objects in 100% of trials (confirming per-request ephemeral keys), while both still decrypt to the identical original.
- **SC-006**: No secret key material or plaintext appears in any log, audit record, or error message across the full test suite (zero occurrences).
- **SC-007**: A typical document is encrypted or decrypted within the service's existing per-operation performance budget under expected load.
- **SC-008**: For multi-recipient objects, each addressed recipient independently recovers the identical original document using only its own private key in 100% of trials, and no recipient requires another recipient's key.

## Assumptions

- **Scope — elliptic-curve recipients**: This feature covers the ECIES scheme for elliptic-curve recipient keys. Handling of RSA recipient certificates via a separate (e.g. CMS RSA key-transport) recipient entry is out of scope here; an RSA recipient supplied to the ECIES path is treated as an unsuitable-recipient error. Mixing RSA and ECIES recipients in one object is out of scope.
- **Multiple recipients per object**: An encrypted object MAY target several elliptic-curve recipients. The document is encrypted once with a content-encryption key; that key is ECIES-wrapped per recipient as the `keyEncryptionAlgorithm` (`oid_ti_ecies_transport_encryption`). Any one recipient's private key suffices to recover the document. This replaces the current placeholder hybrid key-wrap output.
- **Enveloped (key-encapsulation) structure**: Because `oid_ti_ecies_transport_encryption` is a *key-encryption* algorithm, ECIES wraps the content-encryption key inside a recipient entry rather than encrypting the document body directly. The exact container shape (e.g. a CMS-style enveloped structure with one recipient entry per recipient versus a gematik-specific SEQUENCE) is confirmed against gemSpec_Krypt §4.7 during planning.
- **Curve, key-derivation, and symmetric-scheme parameters** (curve identity, KDF, and authenticated-encryption mode, including any fixed initialization value) are taken exactly as mandated by gemSpec_Krypt §4.7; the byte-exact ASN.1 schema and these parameters are confirmed against the normative specification during planning (`specs/gemSpec_Krypt_V2.49.0.pdf` is available in-repo).
- **Private-key access**: Decryption uses the caller-supplied key alias and obtains the key through the standard JCE provider abstraction; the `EncryptionService` does not distinguish software keys from smart-card keys. Smart-card EC key agreement is provided by a JCE provider/SPI (a `CipherSpi`/`KeyAgreementSpi`) that issues APDUs to the encapsulated card under the hood. This feature does not add new key-storage mechanisms, but it does introduce (or rely on) this JCE SPI seam for card-based EC key agreement.
- **Trust verification reuse**: Recipient-certificate trust verification reuses the service's existing trust-verification capability; this feature does not redefine trust rules.
- **Platform cryptography**: Per the request, the standard Java Cryptography (JCE) abstractions provide the cipher, digest, key agreement, and key generation; a vetted security provider supplies the mandated curves and primitives. Library/provider selection and the ASN.1 encoding approach are evaluated during planning per the project's external-library and standard-interface principles.
