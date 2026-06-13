# TUC_KON_023 — Reserve Card

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4571-03
**German Name**: Karte reservieren

## Purpose

Reserves a card for exclusive use by assigning a lock, or releases an existing lock.

## Requirements

- **TIP1-A_4571-03**: The Konnektor MUST implement the Technical Use Case "Karte reservieren" according to TUC_KON_023.

### Preconditions

- Card session is valid

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| doLock | Boolean | Yes | If true, reserve the card; if false, release the existing lock |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| — | — | No output parameters |

### Standard Flow

1. Determine card from cardSession
2. If doLock=true: check no conflicting lock exists, reserve card and assign lock to caller
3. If doLock=false: verify caller owns the lock, release lock immediately

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4060 | Resource | Error | Resource busy |
| 4093 | Access | Error | Card reserved by other session |

## Acceptance Criteria

- **AC-001**: Given an unlocked card and doLock=true, when TUC_KON_023 is called, then card is reserved exclusively for the caller.
- **AC-002**: Given a card locked by the caller and doLock=false, when TUC_KON_023 is called, then the lock is released.
- **AC-003**: Given a card locked by another caller and doLock=true, when TUC_KON_023 is called, then error 4093 is returned.
