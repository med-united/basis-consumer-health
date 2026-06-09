# TUC_KON_019 — Change PIN

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4568
**German Name**: PIN ändern

## Purpose

Changes a PIN on a card. The user enters the old and new PIN at the card terminal display, with Remote-PIN input automatically supported.

## Requirements

- **TIP1-A_4568**: The Konnektor MUST implement the Technical Use Case "PIN ändern" according to TUC_KON_019.

### Preconditions

- Card supports the provided pinRef
- PIN status is not BLOCKED

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| workplaceId | String | Yes | Identifier of the workplace terminal for PIN entry |
| pinRef | PinRef | Yes | Reference identifying the PIN object on the card |
| sourceCardSession | CardSession | No | Required for Card-to-Card authentication on eGK Generation 1+ |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| pinResult | PinResult | Change result: OK, REJECTED, BLOCKED, or ERROR |
| leftTries | Integer | Remaining attempts (optional, present when pinStatus=REJECTED) |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Check PIN status is not BLOCKED (TUC_KON_022)
4. If pinRef=PIN.AMTS_REP, call TUC_KON_012 with PIN.CH
5. If eGK Generation 1+, call TUC_KON_005 for Card-to-Card authentication
6. Determine PIN input terminal
7. Execute atomic PIN change (MODIFY VERIFICATION DATA) with eventing
8. Return pinResult

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4043 | Timeout | Error | PIN entry timeout |
| 4049 | User | Error | User aborted operation |
| 4053 | Access | Error | Remote PIN not possible |
| 4060 | Resource | Error | Resource busy |
| 4063 | PIN | Error | PIN blocked |
| 4066 | Hardware | Error | No PIN pad available |
| 4067 | PIN | Error | New PINs do not match |
| 4068 | PIN | Error | New PIN too short or too long |
| 4071 | Config | Error | No C2C card set |
| 4072 | Parameter | Error | Invalid PIN reference |
| 4092 | Config | Error | Remote-PIN-KT not configured |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |

## Acceptance Criteria

- **AC-001**: Given valid current PIN and new PIN entered correctly twice, when TUC_KON_019 is called, then pinResult=OK is returned.
- **AC-002**: Given new PIN entries that do not match, when TUC_KON_019 is called, then error 4067 is returned.
