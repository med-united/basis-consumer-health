# TUC_KON_026 — Provide Card Session

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4567
**German Name**: Liefere CardSession

## Purpose

Provides a CardSession for a card that is ready for exclusive access, including card reset and session ID generation.

## Requirements

- **TIP1-A_4567**: The Konnektor MUST implement the Technical Use Case "Liefere CardSession" according to TUC_KON_026.

### Preconditions

- Card is available and not reserved by another session

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | No | If card terminal is already in session |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| sessionID | String | Unique identifier for the established card session |

### Standard Flow

1. Reserve card (TUC_KON_023)
2. Reset card (TUC_KON_024)
3. Generate sessionID
4. Set APDU scenario timer

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4060 | Resource | Error | Resource busy |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |

## Acceptance Criteria

- **AC-001**: Given card is available, when TUC_KON_026 is called, then sessionID is returned.
- **AC-002**: Given card is reserved by another session, when TUC_KON_026 is called, then error 4093 is returned.
