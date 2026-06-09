# TUC_KON_210 — Write Record

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4576
**German Name**: SchreibeRecord

## Purpose

Writes data to a specific record of a linear file on a card. KVK cards are not supported.

## Requirements

- **TIP1-A_4576**: The Konnektor MUST implement the Technical Use Case "SchreibeRecord" according to TUC_KON_210.

### Preconditions

- Card.TYPE != KVK

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| fileIdentifier | FileIdentifier | No | File identifier of the target file |
| sfid | ShortFileIdentifier | No | Short file identifier of the target file |
| folder | Folder | Yes | Folder (DF) containing the target file |
| recordNumber | Integer | Yes | Number of the record to write |
| dataToBeWritten | byte[] | Yes | Data bytes to write to the record |

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
6. Write record using UPDATE RECORD command

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given a valid record number and write permission on a non-KVK card, when TUC_KON_210 is called, then the record is updated on the card.
- **AC-002**: Given a KVK card, when TUC_KON_210 is called, then an error is returned.
