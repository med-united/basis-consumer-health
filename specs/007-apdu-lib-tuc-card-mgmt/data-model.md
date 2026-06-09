# Data Model: apdu-lib Module

**Feature Branch**: `007-apdu-lib-tuc-card-mgmt`  
**Module**: `apdu-lib` (`de.servicehealtherx:apdu-lib`)  
**Last Updated**: 2026-06-09  
**Spec Reference**: gemSpec_Kon V5.27.0, Section 4.1.5.4

---

## Overview

This document describes all key entities in the `apdu-lib` Maven module. The module implements 27 Technical Use Cases (TUCs) from the gematik Konnektor specification. Entities are organized into three layers:

- **Model** (`de.servicehealtherx.apdu.model`): value types, enums, exceptions, and interfaces
- **Constants** (`de.servicehealtherx.apdu.model`): gematik-specific protocol constants
- **Infrastructure seam**: the `CardTerminalTransport` interface that isolates APDU transport for testability

---

## Entity Reference

### 1. `CardSession`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Value record (immutable identity, mutable `authState`)

```java
public record CardSession(
    CardHandle      cardHandle,   // non-null
    CardType        cardType,     // non-null
    CardVersion     cardVersion,  // non-null
    AuthState       authState,    // non-null, mutable
    UUID            sessionId,    // nullable — absent until TUC_KON_026 assigns one
    String          lockOwner     // nullable — absent when card is not reserved
) {}
```

**Fields**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `cardHandle` | `CardHandle` (sicct-lib) | No | Unique reference to the physical card slot/card pair |
| `cardType` | `CardType` | No | Discriminates TUC branching logic (eGK vs. HBA vs. SMC-B etc.) |
| `cardVersion` | `CardVersion` | No | Discriminates protocol variants (Gen1, Gen1+, Gen2.0, Gen2.1) |
| `authState` | `AuthState` | No | Per-session authentication state; mutated by PIN and key operations |
| `sessionId` | `UUID` | Yes | Assigned by TUC_KON_026; absent in an un-established session |
| `lockOwner` | `String` | Yes | Identity string of the caller that holds the reservation lock; null means unlocked |

**Validation Rules**

- `cardHandle`, `cardType`, `cardVersion`, and `authState` MUST NOT be null (constructor enforces via `Objects.requireNonNull`).
- `sessionId` is assigned exactly once by TUC_KON_026 and never reassigned.
- `lockOwner` is set by TUC_KON_023 (`doLock=true`) and cleared by TUC_KON_023 (`doLock=false`) or TUC_KON_024.

**Relationships**

- Contains `CardHandle` (imported from `de.servicehealtherx.sicct.models`).
- Contains `AuthState` (see entity 8).
- Referenced as input parameter by every TUC in the module.

---

### 2. `CardType`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Enum

```java
public enum CardType {
    EGK,       // Elektronische Gesundheitskarte
    HBA,       // Heilberufsausweis
    HBAX,      // Heilberufsausweis (extended / generation variant)
    SMC_B,     // Security Module Card type B
    KVK,       // Krankenversichertenkarte (legacy)
    UNKNOWN    // Unrecognised or unpresented card
}
```

**Validation Rules**

- `UNKNOWN` is the safe fallback; no TUC throws an NPE if the card type is unknown — instead an error is raised during precondition checks.
- `KVK` cards MUST be rejected by all write, clear, and cryptographic TUCs (FR-008).

**Relationships**

- Used as a discriminator field in `CardSession`.
- Drives branching in TUC_KON_012, TUC_KON_005, TUC_KON_202–215, TUC_KON_219/220.

---

### 3. `CardVersion`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Enum

```java
public enum CardVersion {
    GENERATION_1,       // eGK Generation 1 (RSA-only, no secure messaging)
    GENERATION_1_PLUS,  // eGK Generation 1+ (RSA, card-to-card auth required)
    GENERATION_2_0,     // eGK / HBA Generation 2.0 (ECC, PACE)
    GENERATION_2_1,     // eGK / HBA Generation 2.1 (ECC, PACE, extended features)
    UNKNOWN             // Version not determined
}
```

**Validation Rules**

- Generation 1+ cards require TUC_KON_005 Card-to-Card authentication before PIN verify (TUC_KON_012, step 5).
- Generation 2.x cards use ECC-based authentication in TUC_KON_005.
- `UNKNOWN` causes TUC implementations to treat the card as Generation 1 (most restrictive path).

**Relationships**

- Used as a discriminator field in `CardSession`.
- Drives cryptographic algorithm selection in TUC_KON_005 and TUC_KON_219/220.

---

### 4. `PinRef`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Enum

```java
public enum PinRef {
    PIN_CH       ("PIN.CH",       (byte) 0x01),
    PIN_QES      ("PIN.QES",      (byte) 0x02),
    PIN_AMTS_REP ("PIN.AMTS_REP", (byte) 0x03),
    MRPIN_AMTS   ("MRPIN.AMTS",   (byte) 0x06),
    PIN_OSD      ("PIN.OSD",      (byte) 0x05);

    private final String cosName;   // string representation per gemSpec_COS
    private final byte   reference; // P2 byte for VERIFY/CHANGE_REF_DATA commands
}
```

**Fields**

| Constant | `cosName` | `reference` | Card Types | Description |
|----------|-----------|-------------|------------|-------------|
| `PIN_CH` | `PIN.CH` | `0x01` | eGK, HBA, SMC-B | Cardholder PIN |
| `PIN_QES` | `PIN.QES` | `0x02` | HBA, HBAx | Qualified Electronic Signature PIN |
| `PIN_AMTS_REP` | `PIN.AMTS_REP` | `0x03` | eGK | Delegated access representative PIN |
| `MRPIN_AMTS` | `MRPIN.AMTS` | `0x06` | eGK | Multi-reference PIN for AMTS |
| `PIN_OSD` | `PIN.OSD` | `0x05` | HBA | On-Screen Display PIN |

**Validation Rules**

- TUC_KON_012 resolves `PIN_AMTS_REP` by calling TUC_KON_012 recursively with `PIN_CH` (standard flow step 4).
- Unsupported `PinRef` for a given `CardType` raises `TucException` error code 4072 (invalid PIN reference).

**Relationships**

- Used as input parameter in TUC_KON_012, TUC_KON_019, TUC_KON_021, TUC_KON_022, TUC_KON_027.
- Used as key type in `AuthState.verifiedPins`.
- Directly produces the `P2` byte in VERIFY (`INS 0x20`) and CHANGE REFERENCE DATA (`INS 0x24`) APDUs.

---

### 5. `PinStatus`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Enum

```java
public enum PinStatus {
    VERIFIED,       // PIN has been successfully verified in this session
    OK,             // PIN is usable but not yet verified in this session
    REJECTED,       // Last verification attempt failed; retries remaining
    BLOCKED,        // PIN is blocked; no retries remaining
    TRANSPORT_PIN,  // Card contains a transport PIN that must be changed first
    EMPTY_PIN       // No PIN set (blank); must be set before use
}
```

**Validation Rules**

- TUC_KON_012 MUST NOT proceed if `PinStatus` is `BLOCKED` (raises error 4063) or `TRANSPORT_PIN` (raises error 4065).
- `VERIFIED` is set in `AuthState.verifiedPins` after a successful TUC_KON_012 execution.

**Relationships**

- Returned by TUC_KON_022 (Provide PIN Status).
- Stored as the value type in `AuthState.verifiedPins`.

---

### 6. `PinResult`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Enum

```java
public enum PinResult {
    OK,        // PIN verified successfully; no prior security state existed
    VERIFIED,  // PIN verified successfully; replaces a prior VERIFIED state
    REJECTED,  // PIN entry was incorrect; retries remain (see leftTries)
    BLOCKED,   // PIN entry was incorrect and no retries remain
    ERROR      // Unexpected card response or transport error
}
```

**Validation Rules**

- `BLOCKED` result from a card response MUST also raise `TucException` with error code 4063.
- `ERROR` is the fallback when the card returns an unexpected SW not covered by `0x63Cx` / `0x9000` / `0x6983`.

**Relationships**

- Returned as output parameter by TUC_KON_012, TUC_KON_019.
- Derived from `ResponseApdu.getSw()` and `ResponseApdu.getLeftTries()`.

---

### 7. `PukResult`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Enum

```java
public enum PukResult {
    OK,        // PUK accepted; PIN is unblocked
    REJECTED,  // PUK entry was incorrect; retries remain
    BLOCKED,   // PUK is also blocked; card is permanently unusable for this PIN
    ERROR      // Unexpected card response or transport error
}
```

**Validation Rules**

- `BLOCKED` PUK MUST propagate as a terminal error; TUC_KON_021 raises `TucException` with error code 4063.

**Relationships**

- Returned as output parameter by TUC_KON_021 (Unblock PIN).

---

### 8. `AuthState`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Service/state class (mutable, not thread-safe by design)

```java
public final class AuthState {
    private final Map<PinRef, PinStatus> verifiedPins; // non-null, mutable
    private final Set<KeyRef>            authedKeys;   // non-null, mutable

    public AuthState() {
        this.verifiedPins = new EnumMap<>(PinRef.class);
        this.authedKeys   = EnumSet.noneOf(KeyRef.class);
    }

    public boolean isPinVerified(PinRef ref) { ... }
    public boolean isKeyAuthenticated(KeyRef ref) { ... }
    public void    markPinVerified(PinRef ref) { ... }
    public void    markKeyAuthenticated(KeyRef ref) { ... }
    public void    clear() { ... }
}
```

**Fields**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `verifiedPins` | `Map<PinRef, PinStatus>` | No | Map of PIN references to their current status within this session |
| `authedKeys` | `Set<KeyRef>` | No | Set of key references for which Card-to-Card authentication has been established |

**Validation Rules**

- `AuthState` is single-session: one instance per `CardSession`, created at session construction.
- NOT thread-safe by design; callers must not share a `CardSession` across concurrent threads.
- `clear()` is called by TUC_KON_024 (Reset Card) to wipe all security states.
- `markPinVerified(PinRef)` sets the status to `PinStatus.VERIFIED` and records it in `verifiedPins`.

**Relationships**

- Owned exclusively by `CardSession`.
- Read by TUC_KON_202–219 to verify required PIN/Key preconditions.
- Written by TUC_KON_012 (PIN verify), TUC_KON_005 (Card-to-Card auth), TUC_KON_024 (clear).

---

### 9. `KeyRef`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Enum

```java
public enum KeyRef {
    C_AUT ((byte) 0x04, "C.AUT"),  // Authentication key
    C_ENC ((byte) 0x02, "C.ENC"),  // Encryption key
    C_QES ((byte) 0x06, "C.QES"); // Qualified Electronic Signature key

    private final byte   reference; // Key reference byte for MSE SET commands
    private final String cosName;   // Name per gemSpec_COS card object system
}
```

**Fields**

| Constant | `reference` | `cosName` | Card Types | Usage |
|----------|-------------|-----------|------------|-------|
| `C_AUT` | `0x04` | `C.AUT` | eGK, HBA, SMC-B | Card-to-Card auth; TUC_KON_036 certificate reads |
| `C_ENC` | `0x02` | `C.ENC` | eGK, SMC-B | Encryption/decryption; TUC_KON_220 |
| `C_QES` | `0x06` | `C.QES` | HBA, HBAx | QES signing; TUC_KON_219 with `PIN_QES` |

**Validation Rules**

- `C_QES` is only valid on HBA/HBAx cards and only in combination with `PIN_QES`.
- The `reference` byte is used as the key reference in the `MSE SET` command (P2 of `INS_MANAGE_SECURITY_ENV`).

**Relationships**

- Used as the element type in `AuthState.authedKeys`.
- Input parameter for TUC_KON_219 (Sign) and TUC_KON_220 (Decrypt).

---

### 10. `CommandApdu`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Value record

```java
public record CommandApdu(
    byte    cla,   // Class byte
    byte    ins,   // Instruction byte
    byte    p1,    // Parameter 1
    byte    p2,    // Parameter 2
    byte[]  data,  // nullable — command data field (Lc + data)
    Integer le     // nullable — expected response length (Le)
) {
    // ISO 7816-4 case factory methods
    public static CommandApdu case1(byte cla, byte ins, byte p1, byte p2) { ... }
    public static CommandApdu case2(byte cla, byte ins, byte p1, byte p2, Integer le) { ... }
    public static CommandApdu case3(byte cla, byte ins, byte p1, byte p2, byte[] data) { ... }
    public static CommandApdu case4(byte cla, byte ins, byte p1, byte p2, byte[] data, Integer le) { ... }

    public byte[] toBytes() { ... }
}
```

**Fields**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `cla` | `byte` | No | ISO 7816-4 class byte (`0x00` = standard, `0x0C` = secure messaging) |
| `ins` | `byte` | No | Instruction byte (e.g., `0x20` = VERIFY, `0xB0` = READ BINARY) |
| `p1` | `byte` | No | Parameter 1 (instruction-specific) |
| `p2` | `byte` | No | Parameter 2 (instruction-specific, often key/PIN reference) |
| `data` | `byte[]` | Yes | Command data body; null in Case 1 and Case 2 APDUs |
| `le` | `Integer` | Yes | Expected response length; null in Case 1 and Case 3 APDUs; `0` means "all available" |

**Factory Methods**

| Method | ISO 7816-4 Case | data | le |
|--------|----------------|------|----|
| `case1(cla,ins,p1,p2)` | Case 1 | absent | absent |
| `case2(cla,ins,p1,p2,le)` | Case 2 | absent | present |
| `case3(cla,ins,p1,p2,data)` | Case 3 | present | absent |
| `case4(cla,ins,p1,p2,data,le)` | Case 4 | present | present |

**`toBytes()` Encoding**

The method produces the canonical ISO 7816-4 byte representation:

```
Case 1: [CLA][INS][P1][P2]
Case 2: [CLA][INS][P1][P2][Le]
Case 3: [CLA][INS][P1][P2][Lc][Data...]
Case 4: [CLA][INS][P1][P2][Lc][Data...][Le]
```

For extended-length APDUs (data > 255 bytes or Le > 255), the three-byte Lc/Le encoding is used.

**Validation Rules**

- `case3` and `case4` require `data != null && data.length > 0`.
- `case2` and `case4` require `le != null && le >= 0`.
- Record compact constructor validates constraints and throws `IllegalArgumentException` on violation.

**Relationships**

- Input to `CardTerminalTransport.transmit()`.
- Constructed by every TUC implementation; TUC_KON_200 accepts raw `byte[]` that is wrapped in a `CommandApdu`.

---

### 11. `ResponseApdu`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Value record

```java
public record ResponseApdu(
    byte[] data, // response data body (may be empty, never null)
    byte   sw1,  // status word high byte
    byte   sw2   // status word low byte
) {
    public short   getSw()        { return (short) ((sw1 & 0xFF) << 8 | (sw2 & 0xFF)); }
    public boolean isSuccess()    { return getSw() == (short) 0x9000; }
    public boolean isWarning()    { int sw = getSw() & 0xFFFF; return sw >= 0x6200 && sw <= 0x63FF; }
    public int     getLeftTries() { ... } // extracts N from 0x63CN; returns -1 if not applicable
}
```

**Fields**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `data` | `byte[]` | No (empty array if none) | Response data body returned by the card |
| `sw1` | `byte` | No | First byte of the status word |
| `sw2` | `byte` | No | Second byte of the status word |

**Derived Properties**

| Method | Return | Condition |
|--------|--------|-----------|
| `getSw()` | `short` | Always available |
| `isSuccess()` | `boolean` | `true` when SW == `0x9000` |
| `isWarning()` | `boolean` | `true` when SW in `[0x6200, 0x63FF]` |
| `getLeftTries()` | `int` | Decodes N from `0x63CN` retry counter; returns `-1` if SW is not in this range |

**Validation Rules**

- `data` MUST NOT be null; an empty byte array represents a response with no data body.
- `getLeftTries()` is only meaningful when `sw1 == 0x63` and `(sw2 & 0xF0) == 0xC0`; callers should check `isWarning()` first.

**PIN response mapping**:

| SW | Meaning |
|----|---------|
| `0x9000` | PIN verified — `PinResult.OK` |
| `0x63C0` | PIN blocked — `PinResult.BLOCKED` |
| `0x63Cn` (n>0) | Wrong PIN, n tries left — `PinResult.REJECTED`, `leftTries = n` |
| `0x6983` | Authentication method blocked — maps to `PinResult.BLOCKED` |

**Relationships**

- Returned by `CardTerminalTransport.transmit()`.
- Consumed by all TUC implementations to derive `PinResult`, `PukResult`, or file content bytes.

---

### 12. `TucException`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Exception class (extends `RuntimeException`)

```java
public final class TucException extends RuntimeException {
    private final int    errorCode;      // numeric Konnektor error code 4001–4094
    private final String tucIdentifier;  // e.g. "TUC_KON_012"

    public TucException(int errorCode, String tucIdentifier, String message) { ... }
    public TucException(int errorCode, String tucIdentifier, String message, Throwable cause) { ... }

    // Factory methods for frequently-used error codes
    public static TucException cardReservedByOther(String tucIdentifier) { ... }  // 4093
    public static TucException cardAccessTimeout(String tucIdentifier) { ... }    // 4094
    public static TucException pinBlocked(String tucIdentifier) { ... }           // 4063
    public static TucException invalidPinRef(String tucIdentifier) { ... }        // 4072
    public static TucException remoteKtNotConfigured(String tucIdentifier) { ... } // 4092
}
```

**Fields**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `errorCode` | `int` | No | Konnektor numeric error code; maps to TAB_KON error table values |
| `tucIdentifier` | `String` | No | Identifier of the TUC that raised the error (e.g. `"TUC_KON_012"`) |

**Error Code Reference**

| Code | Factory Method | Meaning |
|------|---------------|---------|
| 4001 | — | Internal error |
| 4043 | — | PIN entry timeout |
| 4049 | — | User aborted operation |
| 4053 | — | Remote PIN not possible |
| 4060 | — | Resource busy |
| 4063 | `pinBlocked()` | PIN blocked |
| 4065 | — | Transport PIN active |
| 4072 | `invalidPinRef()` | Invalid PIN reference |
| 4092 | `remoteKtNotConfigured()` | Remote-PIN-KT not configured |
| 4093 | `cardReservedByOther()` | Card reserved by other session |
| 4094 | `cardAccessTimeout()` | Card access timeout |

**Validation Rules**

- `errorCode` MUST be in range `[4001, 4094]`; constructor validates and throws `IllegalArgumentException` otherwise.
- `tucIdentifier` MUST NOT be null or blank.
- `TucException` is unchecked (`RuntimeException`) per constitution Principle I: no single-implementation interfaces, and consumers decide whether to handle or propagate.

**Relationships**

- Thrown by every TUC implementation in its error path.
- Factory methods are referenced by name in test assertions (SC-003).

---

### 13. `CardTerminalTransport`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Interface (infrastructure seam / mock boundary)

```java
public interface CardTerminalTransport {
    /**
     * Sends a command APDU to the card identified by the given handle and
     * returns the card's response APDU.
     *
     * @param cardHandle the target card's handle (from sicct-lib)
     * @param command    the ISO 7816-4 command APDU to transmit
     * @return the card's response APDU; never null
     * @throws TucException with code 4094 on communication timeout
     * @throws TucException with code 4001 on transport-layer failure
     */
    ResponseApdu transmit(CardHandle cardHandle, CommandApdu command);
}
```

**Design Notes**

- This is the single infrastructure boundary for the entire module. Every TUC implementation receives a `CardTerminalTransport` instance (constructor injection or method parameter).
- In production, the implementation delegates to the SICCT stack in `sicct-lib` (`SICCT.sendApdu`).
- In unit tests, the implementation is a lambda or a mock that returns pre-scripted `ResponseApdu` values, enabling full TUC coverage without physical hardware.
- Per constitution Principle I (no single-implementation interfaces), this interface is justified because it has two distinct real implementations: the production SICCT transport and the test double.

**Relationships**

- Used by every TUC service class.
- Implemented by the production SICCT adapter (in `sicct-lib` or the Quarkus extension) and by test doubles in the `apdu-lib` test suite.

---

### 14. `GematikISO7816`

**Package**: `de.servicehealtherx.apdu.model`  
**Kind**: Constants class (non-instantiable)

```java
public final class GematikISO7816 {
    private GematikISO7816() {}

    // --- Instruction bytes (INS) not present in sicct-lib's ISO7816 ---

    /** Reset Retry Counter (unblock PIN) — gemSpec_COS §14.6 */
    public static final byte INS_RESET_RETRY_COUNTER             = (byte) 0x2C;

    /** Disable Verification Requirement (disable PIN protection) — gemSpec_COS §14.7 */
    public static final byte INS_DISABLE_VERIFICATION_REQUIREMENT = (byte) 0x26;

    /** Enable Verification Requirement (enable PIN protection) — gemSpec_COS §14.7 */
    public static final byte INS_ENABLE_VERIFICATION_REQUIREMENT  = (byte) 0x28;

    /** Append Record — gemSpec_COS §14.4 */
    public static final byte INS_APPEND_RECORD                    = (byte) 0xE2;

    /** Search Record — gemSpec_COS §14.4 */
    public static final byte INS_SEARCH_RECORD                    = (byte) 0xA2;

    /** Erase Binary — gemSpec_COS §14.3 */
    public static final byte INS_ERASE_BINARY                     = (byte) 0x0E;

    /** Erase Record — gemSpec_COS §14.4 */
    public static final byte INS_ERASE_RECORD                     = (byte) 0x0C;

    /** Perform Security Operation — duplicates ISO7816.INS_PSO for direct reference */
    public static final byte INS_PERFORM_SECURITY_OPERATION       = (byte) 0x2A;

    /** Manage Security Environment — duplicates ISO7816.INS_MSE for direct reference */
    public static final byte INS_MANAGE_SECURITY_ENV              = (byte) 0x22;

    // --- Status word constants specific to gematik card objects ---

    /** File or object referenced does not exist (gemSpec_COS) */
    public static final short SW_OBJECT_NOT_FOUND = (short) 0x6A82;

    /** Execution condition not satisfied (e.g., PIN not verified) */
    public static final short SW_CONDITION_NOT_SATISFIED = (short) 0x6985;

    /** Authentication method blocked (PIN/PUK exhausted) */
    public static final short SW_AUTH_METHOD_BLOCKED = (short) 0x6983;
}
```

**Design Notes**

- Extends the constants provided by `de.servicehealtherx.sicct.ISO7816` (which covers `INS_SELECT`, `INS_READ_BINARY`, `INS_UPDATE_BINARY`, `INS_VERIFY`, `INS_CHANGE_REF_DATA`, `INS_MSE`, `INS_PSO`, `INS_INTERNAL_AUTH`, `INS_EXTERNAL_AUTH`, `INS_GET_CHALLENGE`).
- Does not extend `ISO7816` by inheritance; instead, TUC implementations import from both classes as needed. This avoids coupling the gematik layer to the SICCT layer's class hierarchy.
- `INS_PERFORM_SECURITY_OPERATION` and `INS_MANAGE_SECURITY_ENV` are re-declared here for semantic clarity in the gematik context, since their usage in gematik TUCs (PSO:CDS, MSE:SET) differs from their generic ISO 7816-4 usage.

**Relationships**

- Referenced by TUC_KON_019 (`INS_RESET_RETRY_COUNTER` → 0x2C is non-standard reset retry variant), TUC_KON_021, TUC_KON_027, TUC_KON_204, TUC_KON_209–215, TUC_KON_219, TUC_KON_220.
- Used alongside `de.servicehealtherx.sicct.ISO7816` within the same TUC classes.

---

## State Machines

### CardSession Lock State Machine

The `lockOwner` field of `CardSession` drives the card reservation state machine defined in TUC_KON_023 and TUC_KON_024.

```
┌─────────────────────────────────────────────────────┐
│                   UNLOCKED                          │
│           (lockOwner == null)                       │
└──────────────────┬──────────────────────────────────┘
                   │  TUC_KON_023(doLock=true)
                   │  [no conflicting lock]
                   ▼
┌─────────────────────────────────────────────────────┐
│                    LOCKED                           │
│           (lockOwner = callerIdentity)              │
└─────┬─────────────────────────────┬─────────────────┘
      │  TUC_KON_023(doLock=false)  │  TUC_KON_024
      │  [caller == lockOwner]      │  [caller == lockOwner
      │                             │   OR caller has override]
      ▼                             ▼
┌─────────────────────────────────────────────────────┐
│                   UNLOCKED                          │
│           (lockOwner == null)                       │
└─────────────────────────────────────────────────────┘
```

**Transition Rules**

| Trigger | Precondition | Result |
|---------|-------------|--------|
| `TUC_KON_023(doLock=true)` | `lockOwner == null` | `lockOwner := caller` |
| `TUC_KON_023(doLock=true)` | `lockOwner != null && lockOwner != caller` | Raises `TucException(4093)` |
| `TUC_KON_023(doLock=false)` | `lockOwner == caller` | `lockOwner := null` |
| `TUC_KON_024` | `lockOwner == caller` (or unlocked) | `lockOwner := null`, `AuthState.clear()` |
| `TUC_KON_024` | `lockOwner != caller` | Raises `TucException(4093)` |

---

### PIN AuthState Transitions

The `AuthState.verifiedPins` map tracks PIN verification status within a single card session.

```
┌───────────────────────────────────────────────────────────┐
│                  UNVERIFIED                               │
│          (PinRef not present in verifiedPins)             │
└──────────────────────┬────────────────────────────────────┘
                       │  TUC_KON_012 succeeds
                       │  (ResponseApdu.isSuccess())
                       ▼
┌───────────────────────────────────────────────────────────┐
│                   VERIFIED                                │
│     (verifiedPins.get(pinRef) == PinStatus.VERIFIED)      │
└──────────────────────┬────────────────────────────────────┘
                       │  TUC_KON_024 (Reset Card)
                       │  authState.clear()
                       ▼
┌───────────────────────────────────────────────────────────┐
│                  UNVERIFIED                               │
│          (verifiedPins is empty after clear())            │
└───────────────────────────────────────────────────────────┘
```

**Additional PIN State Notes**

- A `PinStatus.REJECTED` entry (wrong PIN, retries remaining) does NOT block re-entry: TUC_KON_012 can be called again.
- A `PinStatus.BLOCKED` result causes `TucException(4063)` to be thrown; the entry is NOT stored in `AuthState` as this is a permanent card state, not a session state.
- `TUC_KON_224` (Stop Card Session) calls `TUC_KON_024` internally, which clears `AuthState`.

---

## Entity Relationship Summary

```
CardSession ─────────────────── CardHandle (sicct-lib)
     │
     ├── CardType (enum)
     ├── CardVersion (enum)
     └── AuthState
              ├── Map<PinRef, PinStatus>
              └── Set<KeyRef>

CardTerminalTransport
     ├── in:  CardHandle, CommandApdu
     └── out: ResponseApdu

TucException
     ├── errorCode (int, 4001–4094)
     └── tucIdentifier (String)

GematikISO7816 (constants)
     └── extends ISO7816 (sicct-lib, conceptually)

CommandApdu (record)
     ├── cla, ins, p1, p2 (bytes)
     ├── data (byte[], nullable)
     └── le (Integer, nullable)

ResponseApdu (record)
     ├── data (byte[])
     ├── sw1, sw2 (bytes)
     └── derived: getSw(), isSuccess(), isWarning(), getLeftTries()
```
