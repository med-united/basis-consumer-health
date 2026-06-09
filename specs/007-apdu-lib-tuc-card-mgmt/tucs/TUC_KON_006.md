# TUC_KON_006 — Write Data Access Audit to eGK

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4580
**German Name**: Datenzugriffsaudit eGK schreiben

## Purpose

Records a data access event on an eGK by appending an audit entry to EF.Logging.

## Requirements

- **TIP1-A_4580**: The Konnektor MUST implement the Technical Use Case "Datenzugriffsaudit eGK schreiben" according to TUC_KON_006.

### Preconditions

- Card.TYPE=eGK
- KeyRef for DF.HCA.EF.LOGGING is present in CARDSESSION.AUTHSTATE

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target eGK |
| sourceCardSession | CardSession | Yes | Card session of the source card performing the access |
| dataType | DataType | Yes | Type of data that was accessed |
| accessType | AccessType | Yes | Type of access performed (e.g., read, write) |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| — | — | No output parameters |

### Standard Flow

1. Determine card from cardSession
2. Verify Card.TYPE=EGK
3. Verify KeyRef for EF.LOGGING is present in CARDSESSION.AUTHSTATE
4. Generate logging data record from the provided parameters
5. Call TUC_KON_214 (Append Record) to write the audit entry to EF.LOGGING

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given an eGK with write access to EF.LOGGING, when TUC_KON_006 is called, then the audit entry is appended to EF.LOGGING.
- **AC-002**: Given a non-eGK card, when TUC_KON_006 is called, then an error is returned.
