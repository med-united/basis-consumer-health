# TUC_KON_018 — Check eGK Blocking Status

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4579-02
**German Name**: eGK-Sperrung prüfen

## Purpose

Determines whether an eGK is blocked, optionally checking the AUT certificate validity in addition to the DF.HCA blocking flag.

## Requirements

- **TIP1-A_4579-02**: The Konnektor MUST implement the Technical Use Case "eGK-Sperrung prüfen" according to TUC_KON_018.

### Preconditions

- Card.TYPE=eGK

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target eGK |
| checkHcaOnly | Boolean | No | If true, only check DF.HCA blocking flag; if false, also check AUT certificate validity (default=false) |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| blocked | Boolean | True if the eGK is blocked, false otherwise |
| status | Status | Full blocking status including certificate information (optional, present when checkHcaOnly=false) |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Select DF.HCA on the card
4. If checkHcaOnly=true, read and return blocking status from DF.HCA
5. Read C.AUT certificate object
6. Determine ProfessionOIDs from certificate
7. Evaluate full blocking status combining DF.HCA flag and certificate validity
8. Return result

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given a non-blocked eGK and checkHcaOnly=false, when TUC_KON_018 is called, then blocked=false and certificate status is returned.
- **AC-002**: Given a blocked eGK, when TUC_KON_018 is called, then blocked=true is returned.
