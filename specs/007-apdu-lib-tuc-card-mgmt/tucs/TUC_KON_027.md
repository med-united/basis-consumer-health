# TUC_KON_027 — Enable/Disable PIN Protection

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_5486
**German Name**: PIN-Schutz ein-/ausschalten

## Purpose

Enables or disables the PIN verification requirement for a PIN object on an eGK Generation 2 or later card.

## Requirements

- **TIP1-A_5486**: The Konnektor MUST implement the Technical Use Case "PIN-Schutz ein-/ausschalten" according to TUC_KON_027.

### Preconditions

- Card.TYPE=eGK
- Card.Version >= Generation 2

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target eGK |
| pinRef | PinRef | Yes | Reference identifying the PIN object (must be MRPIN.AMTS) |
| enable | Boolean | Yes | If true, enable PIN protection; if false, disable PIN protection |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| pinResult | PinResult | Operation result: OK, REJECTED, BLOCKED, or ERROR |
| leftTries | Integer | Remaining PIN attempts (optional) |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Validate Card.TYPE=EGK and Generation >= 2
4. Validate pinRef=MRPIN.AMTS
5. If enable=true, execute atomic PIN enable (ENABLE VERIFICATION REQUIREMENT)
6. If enable=false, execute atomic PIN disable (DISABLE VERIFICATION REQUIREMENT)
7. Return pinResult

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4043 | Timeout | Error | PIN entry timeout |
| 4049 | User | Error | User aborted operation |
| 4060 | Resource | Error | Resource busy |
| 4063 | PIN | Error | PIN blocked |
| 4072 | Parameter | Error | Invalid PIN reference |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |

## Acceptance Criteria

- **AC-001**: Given an eGK Generation 2 card and enable=true, when TUC_KON_027 is called, then PIN protection is enabled and pinResult=OK is returned.
- **AC-002**: Given a non-eGK card, when TUC_KON_027 is called, then an error is returned.
