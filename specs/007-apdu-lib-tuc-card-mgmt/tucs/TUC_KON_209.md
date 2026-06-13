# TUC_KON_209 — Read Record

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4575
**German Name**: LeseRecord

## Purpose

Reads a specific record from a structured (linear or cyclic) file on a card.

## Requirements

- **TIP1-A_4575**: The Konnektor MUST implement the Technical Use Case "LeseRecord" according to TUC_KON_209.

### Preconditions

- Card session is valid

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| fileIdentifier | FileIdentifier | No | File identifier of the target file |
| sfid | ShortFileIdentifier | No | Short file identifier of the target file |
| folder | Folder | Yes | Folder (DF) containing the target file |
| recordNumber | Integer | Yes | Number of the record to read |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| content | byte[] | Byte array of the record content read from the card |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Check PIN or KeyRef in CARDSESSION.AUTHSTATE
4. Select file using SELECT command
5. Read record using READ RECORD command
6. Return content

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given a valid record number and read permission, when TUC_KON_209 is called, then the record bytes are returned.
- **AC-002**: Given a non-existent record number, when TUC_KON_209 is called, then an error is returned.
