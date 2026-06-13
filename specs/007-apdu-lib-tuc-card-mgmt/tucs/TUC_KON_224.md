# TUC_KON_224 — Stop Card Session

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: A_26068
**German Name**: Stoppe Kartensitzung

## Purpose

Terminates an active card session, resets the card, and releases all locks.

## Requirements

- **A_26068**: The Konnektor MUST implement the Technical Use Case "Stoppe Kartensitzung" according to TUC_KON_224.

### Preconditions

- A card session with the given sessionID exists

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| sessionID | UUID | Yes | Unique identifier of the active card session to terminate |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| — | — | No output parameters |

### Standard Flow

1. Determine CardSession by sessionID
2. Remove sessionID from persisted context
3. Reset card (TUC_KON_024)
4. Release lock (TUC_KON_023 with doLock=false)

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |

## Acceptance Criteria

- **AC-001**: Given an active session ID, when TUC_KON_224 is called, then the session is terminated, the card is reset, and the lock is released.
- **AC-002**: Given an unknown session ID, when TUC_KON_224 is called, then an error is returned.
