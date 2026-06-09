# Feature Specification: APDU Library — Card Management Technical Use Cases

**Feature Branch**: `007-apdu-lib-tuc-card-mgmt`

**Created**: 2026-06-09

**Status**: Draft

**Source Specification**: gemSpec_Kon V5.27.0, Section 4.1.5.4 — Kartendienst: Interne TUCs, auch durch Fachmodule nutzbar

## Overview

This feature defines a new Maven module `apdu-lib` that implements all 27 card management Technical Use Cases (TUCs) defined in section 4.1.5.4 of the gematik Konnektor specification (gemSpec_Kon). These TUCs form the core card interaction primitives used by higher-level functional modules (Fachmodule) and the Konnektor's card service.

Each TUC is specified in its own file under `tucs/` using the TUC identifier as the filename. This spec governs the module's scope, user scenarios, functional requirements, and success criteria.

### TUC Inventory (Section 4.1.5.4)

| # | TUC Identifier | English Name | Req. ID | File |
|---|----------------|--------------|---------|------|
| 1 | TUC_KON_026 | Provide Card Session | TIP1-A_4567 | [TUC_KON_026.md](tucs/TUC_KON_026.md) |
| 2 | TUC_KON_012 | Verify PIN | TIP1-A_4566 | [TUC_KON_012.md](tucs/TUC_KON_012.md) |
| 3 | TUC_KON_019 | Change PIN | TIP1-A_4568 | [TUC_KON_019.md](tucs/TUC_KON_019.md) |
| 4 | TUC_KON_021 | Unblock PIN | TIP1-A_4569-02 | [TUC_KON_021.md](tucs/TUC_KON_021.md) |
| 5 | TUC_KON_022 | Provide PIN Status | TIP1-A_4570 | [TUC_KON_022.md](tucs/TUC_KON_022.md) |
| 6 | TUC_KON_027 | Enable/Disable PIN Protection | TIP1-A_5486 | [TUC_KON_027.md](tucs/TUC_KON_027.md) |
| 7 | TUC_KON_023 | Reserve Card | TIP1-A_4571-03 | [TUC_KON_023.md](tucs/TUC_KON_023.md) |
| 8 | TUC_KON_005 | Card-to-Card Authentication | TIP1-A_4572 | [TUC_KON_005.md](tucs/TUC_KON_005.md) |
| 9 | TUC_KON_202 | Read File | TIP1-A_4573 | [TUC_KON_202.md](tucs/TUC_KON_202.md) |
| 10 | TUC_KON_203 | Write File | TIP1-A_4574 | [TUC_KON_203.md](tucs/TUC_KON_203.md) |
| 11 | TUC_KON_204 | Clear File Content | TIP1-A_4576-1 | [TUC_KON_204.md](tucs/TUC_KON_204.md) |
| 12 | TUC_KON_209 | Read Record | TIP1-A_4575 | [TUC_KON_209.md](tucs/TUC_KON_209.md) |
| 13 | TUC_KON_210 | Write Record | TIP1-A_4576-2 | [TUC_KON_210.md](tucs/TUC_KON_210.md) |
| 14 | TUC_KON_211 | Clear Record Content | TIP1-A_4577-1 | [TUC_KON_211.md](tucs/TUC_KON_211.md) |
| 15 | TUC_KON_214 | Append Record | TIP1-A_4577-2 | [TUC_KON_214.md](tucs/TUC_KON_214.md) |
| 16 | TUC_KON_215 | Search Record | TIP1-A_4578 | [TUC_KON_215.md](tucs/TUC_KON_215.md) |
| 17 | TUC_KON_018 | Check eGK Blocking Status | TIP1-A_4579-02 | [TUC_KON_018.md](tucs/TUC_KON_018.md) |
| 18 | TUC_KON_006 | Write Data Access Audit to eGK | TIP1-A_4580 | [TUC_KON_006.md](tucs/TUC_KON_006.md) |
| 19 | TUC_KON_219 | Sign | TIP1-A_4581 | [TUC_KON_219.md](tucs/TUC_KON_219.md) |
| 20 | TUC_KON_220 | Decrypt | TIP1-A_4582 | [TUC_KON_220.md](tucs/TUC_KON_220.md) |
| 21 | TUC_KON_223 | Start Card Session | A_26067 | [TUC_KON_223.md](tucs/TUC_KON_223.md) |
| 22 | TUC_KON_208 | Send Secured APDU | A_26069-01 | [TUC_KON_208.md](tucs/TUC_KON_208.md) |
| 23 | TUC_KON_224 | Stop Card Session | A_26068 | [TUC_KON_224.md](tucs/TUC_KON_224.md) |
| 24 | TUC_KON_200 | Send APDU | TIP1-A_4583-02 | [TUC_KON_200.md](tucs/TUC_KON_200.md) |
| 25 | TUC_KON_024 | Reset Card | TIP1-A_4584-02 | [TUC_KON_024.md](tucs/TUC_KON_024.md) |
| 26 | TUC_KON_216 | Read Certificate | TIP1-A_4585 | [TUC_KON_216.md](tucs/TUC_KON_216.md) |
| 27 | TUC_KON_036 | Provide Professional Role | TIP1-A_5478 | [TUC_KON_036.md](tucs/TUC_KON_036.md) |

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Implement PIN Management TUCs (Priority: P1)

A functional module (Fachmodul) or Konnektor card service needs to verify, change, unblock, and query PIN status on health cards (eGK, HBA, SMC-B) without knowing the underlying APDU protocol details.

**Why this priority**: PIN management is the security gateway for all card operations. No data access, signing, or encryption TUC can proceed without prior PIN verification. It is the foundational dependency for the entire card service.

**Independent Test**: The PIN management TUCs (TUC_KON_012, TUC_KON_019, TUC_KON_021, TUC_KON_022, TUC_KON_027) can be tested independently against a SICCT card terminal emulator with a test eGK card.

**Acceptance Scenarios**:

1. **Given** a valid card session and a correct PIN, **When** TUC_KON_012 (Verify PIN) is called, **Then** pinResult = OK is returned and no error codes are raised.
2. **Given** a valid card session and a blocked PIN, **When** TUC_KON_012 (Verify PIN) is called, **Then** pinResult = BLOCKED is returned with error code 4063.
3. **Given** a valid card session and a transport-protected PIN, **When** TUC_KON_019 (Change PIN) is called with old and new PINs, **Then** pinResult = OK is returned and the PIN protection status is updated on the card.
4. **Given** a valid card session and a rejected (REJECTED) PUK, **When** TUC_KON_021 (Unblock PIN) is called, **Then** result = REJECTED and leftTries > 0 is returned.
5. **Given** a valid card session, **When** TUC_KON_022 (Provide PIN Status) is called, **Then** the current pinStatus (OK, VERIFIED, BLOCKED, TRANSPORT_PIN, EMPTY_PIN, REJECTED) is returned correctly.

---

### User Story 2 — Implement Card File and Record I/O TUCs (Priority: P2)

A functional module needs to read, write, clear, append, and search records and transparent files on health cards through a uniform, card-type-agnostic interface.

**Why this priority**: File and record I/O TUCs are required by virtually every application-level operation on health cards (reading VSD data, writing audit entries, accessing MF/DF structures).

**Independent Test**: The file/record TUCs (TUC_KON_202–TUC_KON_215) can be tested independently using a test eGK with known file structures and a SICCT simulator.

**Acceptance Scenarios**:

1. **Given** a valid card session, file identifier, and read permission, **When** TUC_KON_202 (Read File) is called with an offset and length, **Then** the exact byte range is returned without error.
2. **Given** a valid card session and write permission, **When** TUC_KON_203 (Write File) is called with new content, **Then** the file content is updated on the card.
3. **Given** a valid card session and a KVK card, **When** TUC_KON_203 (Write File) is attempted, **Then** an error is raised because write operations are prohibited on KVK cards.
4. **Given** a valid card session and a structured file, **When** TUC_KON_209 (Read Record) is called with a record number, **Then** the record content is returned.
5. **Given** a valid card session and a linear file, **When** TUC_KON_215 (Search Record) is called with a search pattern, **Then** the list of matching record numbers is returned.

---

### User Story 3 — Implement Cryptographic and Session TUCs (Priority: P2)

A functional module needs to produce card-based digital signatures and decrypt data using card-resident private keys, and to manage secure APDU sessions.

**Why this priority**: Signing and decryption are mandatory for QES workflows and ENC operations. Secure APDU sessions (TUC_KON_223/208/224) are required for eGK Generation 2+ remote operations.

**Independent Test**: TUC_KON_219 (Sign) can be tested independently against a test HBA with a known key reference; TUC_KON_220 (Decrypt) against a test eGK with RSA or ECDH key.

**Acceptance Scenarios**:

1. **Given** a valid card session, correct pinRef, keyRef, and algorithmId, **When** TUC_KON_219 (Sign) is called with data, **Then** a valid signature is returned that can be verified with the card's public key.
2. **Given** a valid card session and encrypted data, **When** TUC_KON_220 (Decrypt) is called, **Then** the original plaintext is returned.
3. **Given** an active card session ID, **When** TUC_KON_208 (Send Secured APDU) is called with a signed scenario, **Then** the response APDUs are returned and the sequence number is incremented.
4. **Given** a session ID from TUC_KON_223, **When** TUC_KON_224 (Stop Card Session) is called, **Then** the session is removed, the card is reset, and the lock is released.

---

### User Story 4 — Implement Card-to-Card Authentication and Utility TUCs (Priority: P3)

A functional module needs to perform card-to-card authentication between an SMC-B and eGK, check eGK blocking status, write access audit entries, read certificates, and obtain professional roles.

**Why this priority**: These TUCs are required for specific workflows (eGK data access, QES signing with HBA) but have narrower usage than core PIN and I/O operations.

**Independent Test**: TUC_KON_005 (Card-to-Card Authentication) can be tested independently with an SMC-B and eGK pair in a SICCT environment.

**Acceptance Scenarios**:

1. **Given** source card session (SMC-B) and target card session (eGK Gen1+), **When** TUC_KON_005 is called with one-sided authMode, **Then** the target card authenticates the source card successfully.
2. **Given** a valid eGK card session, **When** TUC_KON_018 (Check eGK Blocking Status) is called, **Then** the blocking status and optional certificate status are returned correctly.
3. **Given** an eGK with write permission to EF.Logging, **When** TUC_KON_006 (Write Data Access Audit) is called, **Then** the audit entry is appended to EF.Logging.
4. **Given** a valid card session for an HBAx or SMC-B, **When** TUC_KON_036 (Provide Professional Role) is called, **Then** the professional role derived from the C.AUT certificate's ProfessionOIDs is returned.

---

### Edge Cases

- What happens when a card session references a card that has been removed mid-operation?
- How does the system handle PIN entry timeout at the card terminal (error code 4043)?
- What occurs when a card is reserved by another session and an exclusive operation is attempted (error code 4093)?
- How are transport-protected PINs handled during PIN change operations?
- What happens when TUC_KON_005 Card-to-Card Authentication is aborted during a TUC_KON_024 card reset?
- How does the library behave when APDU response codes differ from expected values in a secure session scenario?

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The `apdu-lib` module MUST implement all 27 TUCs defined in section 4.1.5.4 of gemSpec_Kon V5.27.0, each as a dedicated, independently callable operation.
- **FR-002**: Each TUC implementation MUST enforce all preconditions defined in its specification (card type restrictions, lock state checks, PIN status checks, authentication state checks) before executing the main APDU flow.
- **FR-003**: Each TUC implementation MUST return all output parameters (result codes, leftTries, data content, certificates, etc.) as specified in the corresponding TAB_KON table.
- **FR-004**: Each TUC implementation MUST raise all error codes defined in its Fehlerfälle table as typed exceptions or structured error results, preserving the numeric error code for caller interpretation.
- **FR-005**: The library MUST support all card types referenced in the TUCs: eGK (Generation 1, 1+, 2.0, 2.1), HBA, HBAx, SMC-B, KVK, with card-type-specific branches executed according to `Card.TYPE` and `Card.Version`.
- **FR-006**: PIN management TUCs (TUC_KON_012, TUC_KON_019, TUC_KON_021) MUST support both local PIN-Pad entry (direct at card terminal) and Remote-PIN input as specified in TIP1-A_5012.
- **FR-007**: TUC_KON_005 (Card-to-Card Authentication) MUST support both Generation 1+ RSA-based and Generation 2 ECC-based authentication modes as defined in TAB_KON_673.
- **FR-008**: File and record I/O TUCs (TUC_KON_202 through TUC_KON_215) MUST enforce that write and clear operations are rejected for KVK cards.
- **FR-009**: TUC_KON_208 (Send Secured APDU) MUST validate the sequence number in the signed scenario and reject replayed or out-of-order scenarios.
- **FR-010**: TUC_KON_006 (Write Data Access Audit) MUST only be callable for eGK cards and MUST append audit entries to EF.Logging using TUC_KON_214 (Append Record).
- **FR-011**: TUC_KON_018 (Check eGK Blocking Status) MUST support both full checking (including AUT certificate validity) and HCA-only checking via the `checkHcaOnly` flag.
- **FR-012**: TUC_KON_036 (Provide Professional Role) MUST support KVK (returns fixed role "Versicherter"), HBAx, SMC-B, and eGK cards, deriving the role from the C.AUT certificate's ProfessionOIDs.
- **FR-013**: All TUC implementations MUST emit the appropriate events (TUC_KON_256) at the start and end of operations that require eventing, as specified in each TUC's Standardablauf.
- **FR-014**: The library MUST provide a Maven module artifact (`apdu-lib`) with a stable public API that functional modules can depend on without accessing internal card service state.
- **FR-015**: Each TUC MUST be independently testable with a SICCT card terminal emulator or a real card terminal, without requiring other TUCs to be fully implemented.

### Key Entities

- **CardSession**: Represents an active card interaction context. Contains card handle, card type, card version, CARDSESSION.AUTHSTATE (authenticated PIN references and key references), and reservation lock.
- **PinRef**: Reference to a specific PIN object on a card (e.g., PIN.CH, PIN.QES, PIN.AMTS_REP, MRPIN.AMTS). Defined per card type's object system specification.
- **KeyRef**: Reference to a cryptographic key on a card (private key for signing or decryption).
- **CardHandle**: Unique identifier for a physical card slot / card combination within the Konnektor context.
- **WorkplaceId**: Identifier for the workplace associated with a card terminal, used for Remote-PIN routing.
- **PinResult / PukResult**: Typed result of a PIN or PUK operation — one of: OK, VERIFIED, REJECTED, BLOCKED, ERROR.
- **CommandAPDU / ResponseAPDU**: Binary APDU data exchanged with the card via the card terminal.
- **SignedScenario / SignedScenarioResponse**: Cryptographically protected APDU scenario for secure card sessions (TUC_KON_208).

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All 27 TUC operations are callable by a functional module without compile-time or runtime errors for each supported card type.
- **SC-002**: The unit test suite for `apdu-lib` achieves a minimum of 80% line coverage, with each TUC covered by at least one test method whose name references the TUC requirement ID (e.g., `test_TIP1_A_4566_verify_pin_returns_ok_for_correct_pin()`).
- **SC-003**: Each TUC's error handling is verified: every error code listed in the TUC's Fehlerfälle table is exercised by at least one test scenario.
- **SC-004**: PIN entry operations (TUC_KON_012, TUC_KON_019, TUC_KON_021) complete end-to-end (APDU exchange including terminal display messages) within 30 seconds under normal conditions (excluding user input delay).
- **SC-005**: APDU send operations (TUC_KON_200, TUC_KON_208) achieve a round-trip time of under 500ms for a single APDU exchange on a local card terminal connection.
- **SC-006**: The `apdu-lib` module integrates into the parent Maven build without modifying any existing module's dependencies or breaking existing tests.
- **SC-007**: All 27 TUC requirement IDs (e.g., TIP1-A_4567) appear in the Afo_Testmatrix with a corresponding test reference before release.

---

## Assumptions

- The `apdu-lib` module operates within a Konnektor runtime that provides SICCT terminal access and card handle management; the library does not manage physical card reader connections directly.
- Card terminal communication (SICCT protocol) is provided by an existing infrastructure layer; `apdu-lib` constructs and interprets APDUs but delegates transport to this layer.
- The CARDSESSION.AUTHSTATE is maintained externally by the card service and passed into each TUC call; `apdu-lib` reads and updates it but does not own its lifecycle.
- Remote-PIN support (TIP1-A_5012) requires a Remote-PIN card terminal to be configured for the mandant; if none is configured, the relevant TUCs return error code 4092.
- ECC-based Card-to-Card Authentication (TUC_KON_005, Generation 2) relies on Bouncy Castle for elliptic curve operations, pending security review per Principle VII.
- The `apdu-lib` module targets Java 17+ and Maven 3.9+, consistent with the parent project's technology stack.
- KVK cards support only read operations; all write, clear, and cryptographic TUCs will reject KVK cards with a typed error.
- The specification source is gemSpec_Kon V5.27.0 (2026-01-21); any normative updates to TUC definitions require a corresponding update to this specification and the affected TUC files.
