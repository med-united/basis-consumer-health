# TUC_KON_012 — Verify PIN

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4566
**German Name**: PIN verifizieren

## Purpose

Securely verifies a PIN via the card terminal PIN-Pad and returns the verification result.

## Requirements

- **TIP1-A_4566**: The Konnektor MUST implement the Technical Use Case "PIN verifizieren" according to TUC_KON_012.

### Preconditions

- Card supports the provided pinRef

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| workplaceId | String | Yes | Identifier of the workplace terminal for PIN entry |
| pinRef | PinRef | Yes | Reference identifying the PIN object on the card |
| appName | String | No | Optional application name context |
| verificationType | VerificationType | No | Optional type of verification operation |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| pinResult | PinResult | Verification result: OK, VERIFIED, REJECTED, BLOCKED, or ERROR |
| leftTries | Integer | Remaining PIN attempts (optional, present when relevant) |

### Standard Flow

1. Determine card from cardSession
2. Check card is not reserved by another session
3. Check PIN status is not BLOCKED or TRANSPORT_PIN
4. If pinRef=PIN.AMTS_REP, call TUC_KON_012 with PIN.CH
5. If eGK Generation 1+, call TUC_KON_005 for Card-to-Card authentication
6. Determine PIN input terminal
7. Execute atomic PIN verify operation with eventing (TUC_KON_256)
8. Return pinResult

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4043 | Timeout | Error | PIN entry timeout |
| 4049 | User | Error | User aborted operation |
| 4053 | Access | Error | Remote PIN not possible |
| 4060 | Resource | Error | Resource busy |
| 4063 | PIN | Error | PIN blocked |
| 4065 | PIN | Error | Transport PIN active |
| 4072 | Parameter | Error | Invalid PIN reference |
| 4092 | Config | Error | Remote-PIN-KT not configured |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |

## Acceptance Criteria

- **AC-001**: Given correct PIN is entered, when TUC_KON_012 is called, then pinResult=OK is returned.
- **AC-002**: Given wrong PIN with retries remaining, when TUC_KON_012 is called, then pinResult=REJECTED with leftTries is returned.
- **AC-003**: Given blocked PIN, when TUC_KON_012 is called, then error 4063 is returned.
