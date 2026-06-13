# TUC_KON_214 — Append Record

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4577
**German Name**: FügeHinzuRecord

## Purpose

Appends a new record to a linear file on a card. KVK cards are not supported.

## Requirements

- **TIP1-A_4577**: The Konnektor MUST implement the Technical Use Case "FügeHinzuRecord" according to TUC_KON_214.

### Preconditions

- Card.TYPE != KVK
- File has capacity for an additional record

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| fileIdentifier | FileIdentifier | No | File identifier of the target file |
| sfid | ShortFileIdentifier | No | Short file identifier of the target file |
| folder | Folder | Yes | Folder (DF) containing the target file |
| dataToBeWritten | byte[] | Yes | Data bytes to write as the new record |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| — | — | No output parameters |

### Standard Flow

1. Determine card from cardSession
2. Reject if Card.TYPE=KVK
3. Check no foreign lock is present
4. Check PIN or KeyRef in CARDSESSION.AUTHSTATE
5. Select file using SELECT command
6. Append record using APPEND RECORD command

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given append permission and available capacity in the file, when TUC_KON_214 is called, then a new record is added.
- **AC-002**: Given a full file with no capacity for additional records, when TUC_KON_214 is called, then a card error is returned.
- **AC-003**: Given a KVK card, when TUC_KON_214 is called, then an error is returned.
