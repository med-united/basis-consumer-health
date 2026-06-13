# TUC_KON_215 — Search Record

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4578
**German Name**: SucheRecord

## Purpose

Searches a structured file for records matching a byte pattern and returns the matching record numbers.

## Requirements

- **TIP1-A_4578**: The Konnektor MUST implement the Technical Use Case "SucheRecord" according to TUC_KON_215.

### Preconditions

- Card session is valid

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| fileIdentifier | FileIdentifier | No | File identifier of the target file |
| sfid | ShortFileIdentifier | No | Short file identifier of the target file |
| folder | Folder | Yes | Folder (DF) containing the target file |
| pattern | byte[] | Yes | Byte pattern to search for within records |
| recordNumber | Integer | No | Record number from which to start searching (default=1) |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| numbersFound | List<Integer> | List of record numbers matching the search pattern |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Check PIN or KeyRef in CARDSESSION.AUTHSTATE
4. Select file using SELECT command
5. Execute SEARCH RECORD command with the pattern
6. Return matching record numbers

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given a pattern matching two records, when TUC_KON_215 is called, then both matching record numbers are returned.
- **AC-002**: Given a pattern matching no records, when TUC_KON_215 is called, then an empty list is returned.
