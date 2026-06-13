# TUC_KON_204 — Clear File Content

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4576
**German Name**: LöscheDateiInhalt

## Purpose

Erases the content of a transparent file on a card. KVK cards are not supported.

## Requirements

- **TIP1-A_4576**: The Konnektor MUST implement the Technical Use Case "LöscheDateiInhalt" according to TUC_KON_204.

### Preconditions

- Card.TYPE != KVK

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| fileIdentifier | FileIdentifier | No | File identifier of the target file |
| sfid | ShortFileIdentifier | No | Short file identifier of the target file |
| folder | Folder | Yes | Folder (DF) containing the target file |
| offset | Integer | No | Byte offset within the file to start erasing from |

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
6. Erase content using ERASE BINARY command

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given erase permission on a non-KVK card, when TUC_KON_204 is called, then the file content is cleared.
- **AC-002**: Given a KVK card, when TUC_KON_204 is called, then an error is returned.
