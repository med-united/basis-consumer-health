# TUC_KON_021 — Unblock PIN

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4569-02
**German Name**: PIN entsperren

## Purpose

Resets the failed-attempt counter for a PIN on the card using the PUK, and optionally sets a new PIN. Remote-PIN input is automatically supported.

## Requirements

- **TIP1-A_4569-02**: The Konnektor MUST implement the Technical Use Case "PIN entsperren" according to TUC_KON_021.

### Preconditions

- Card supports the provided pinRef

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| workplaceId | String | Yes | Identifier of the workplace terminal for PUK entry |
| pinRef | PinRef | Yes | Reference identifying the PIN object to unblock |
| setNewPin | Boolean | No | If true, a new PIN is set after unblocking (default=false) |
| sourceCardSession | CardSession | No | Required for Card-to-Card authentication on eGK Generation 1+ |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| result | PukResult | Unblock result: OK, REJECTED, BLOCKED, or ERROR |
| leftTries | Integer | Remaining PUK attempts (optional, present when pukStatus=REJECTED) |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. If PIN status is not BLOCKED and not TRANSPORT_PIN, finish successfully
4. If pinRef=PIN.AMTS_REP, set setNewPin=true and call TUC_KON_012 with PIN.CH
5. If eGK Generation 1+, call TUC_KON_005 for Card-to-Card authentication
6. Determine PIN input terminal
7. Execute atomic PIN unblock with eventing
8. Return result

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4043 | Timeout | Error | PIN entry timeout |
| 4049 | User | Error | User aborted operation |
| 4053 | Access | Error | Remote PIN not possible |
| 4060 | Resource | Error | Resource busy |
| 4064 | PIN | Error | PUK blocked |
| 4067 | PIN | Error | New PINs do not match |
| 4068 | PIN | Error | New PIN too short or too long |
| 4072 | Parameter | Error | Invalid PIN reference |
| 4092 | Config | Error | Remote-PIN-KT not configured |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |

## Acceptance Criteria

- **AC-001**: Given blocked PIN and valid PUK, when TUC_KON_021 is called, then result=OK and PIN counter is reset.
- **AC-002**: Given wrong PUK, when TUC_KON_021 is called, then result=REJECTED with leftTries is returned.
