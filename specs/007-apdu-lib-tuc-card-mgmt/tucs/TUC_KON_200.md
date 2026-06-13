# TUC_KON_200 — Send APDU

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4583-02
**German Name**: SendeAPDU

## Purpose

Builds a raw command APDU request that can be executed by an external transport layer (for example `sicct-lib` or `crypto-pcsc-lib`).

## Requirements

- **TIP1-A_4583-02**: The Konnektor MUST implement the Technical Use Case "SendeAPDU" according to TUC_KON_200.

### Preconditions

- Either cardSession or ctId must be provided

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | No | Active card session identifying the target card (required if ctId not given) |
| ctId | TerminalId | No | Card terminal identifier (required if cardSession not given) |
| commandAPDU | byte[] | Yes | Raw command APDU bytes to include in the generated execution request |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| apduExecutionRequest | ApduExecutionRequest | Generated APDU request payload with expected status handling metadata |

### Standard Flow

**Path A — via cardSession:**
1. Determine card and terminal context from cardSession
2. Verify caller holds the lock for the card
3. Build APDU execution request payload (command APDU + expected status metadata)
4. Return the generated request payload

**Path B — via ctId:**
1. Build APDU execution request payload for the terminal identified by ctId
2. Return the generated request payload

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4060 | Resource | Error | Resource busy |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout reported by downstream execution layer |

## Acceptance Criteria

- **AC-001**: Given a locked card session and a valid command APDU, when TUC_KON_200 is called, then an APDU execution request payload is generated.
- **AC-002**: Given a card session without the required lock and a lock-requiring operation, when TUC_KON_200 is called, then error 4093 is returned.
