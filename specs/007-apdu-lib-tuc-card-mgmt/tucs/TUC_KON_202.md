# TUC_KON_202 — Read File

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4573
**German Name**: LeseDatei

## Purpose

Reads all or part of a transparent file from a card.

## Requirements

- **TIP1-A_4573**: The Konnektor MUST implement the Technical Use Case "LeseDatei" according to TUC_KON_202.

### Preconditions

- Card session is valid

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| fileIdentifier | FileIdentifier | No | File identifier of the target file |
| sfid | ShortFileIdentifier | No | Short file identifier of the target file |
| folder | Folder | Yes | Folder (DF) containing the target file |
| offset | Integer | No | Byte offset within the file to start reading from |
| length | Integer | No | Number of bytes to read |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| content | byte[] | Byte array of the file content read from the card |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Check PIN or KeyRef in CARDSESSION.AUTHSTATE for the target file
4. Select file using SELECT command
5. Read data using READ BINARY command
6. Return content

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given a file with read permission and valid authentication state, when TUC_KON_202 is called, then the file bytes are returned.
- **AC-002**: Given missing required authentication for the target file, when TUC_KON_202 is called, then an error is returned.
