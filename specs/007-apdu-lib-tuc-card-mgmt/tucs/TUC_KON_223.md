# TUC_KON_223 — Start Card Session

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: A_26067
**German Name**: Starte Kartensitzung

## Purpose

Initializes an eGK for an exclusive card session, generating a session ID and setting the APDU scenario timer.

## Requirements

- **A_26067**: The Konnektor MUST implement the Technical Use Case "Starte Kartensitzung" according to TUC_KON_223.

### Preconditions

- Card.TYPE=eGK (only eGK is supported for card sessions)

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target eGK |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| sessionID | UUID | Unique identifier for the established exclusive card session |

### Standard Flow

1. Reserve card (TUC_KON_023)
2. Reset card (TUC_KON_024)
3. Generate sessionID as UUID
4. Set APDU scenario timer

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4060 | Resource | Error | Resource busy |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |

## Acceptance Criteria

- **AC-001**: Given an available eGK, when TUC_KON_223 is called, then a unique UUID session ID is returned and the card is reserved.
- **AC-002**: Given a non-eGK card, when TUC_KON_223 is called, then an error is returned.
