# TUC_KON_022 — Provide PIN Status

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4570
**German Name**: Liefere PIN-Status

## Purpose

Returns the current status of a PIN object on a card within a CardSession.

## Requirements

- **TIP1-A_4570**: The Konnektor MUST implement the Technical Use Case "Liefere PIN-Status" according to TUC_KON_022.

### Preconditions

- Card session is valid

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| pinRef | PinRef | Yes | Reference identifying the PIN object to query |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| pinStatus | PinStatus | Current PIN status: VERIFIED, OK, REJECTED, BLOCKED, TRANSPORT_PIN, or EMPTY_PIN |
| leftTries | Integer | Remaining PIN attempts (optional) |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Check CARDSESSION.AUTHSTATE for pinRef
4. Send GET PIN STATUS card command
5. Evaluate response and return pinStatus

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4072 | Parameter | Error | Invalid PIN reference |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |

## Acceptance Criteria

- **AC-001**: Given a PIN that has been verified in the current session, when TUC_KON_022 is called, then pinStatus=VERIFIED is returned.
- **AC-002**: Given a PIN that has never been verified, when TUC_KON_022 is called, then pinStatus=OK is returned.
- **AC-003**: Given a blocked PIN, when TUC_KON_022 is called, then pinStatus=BLOCKED is returned.
