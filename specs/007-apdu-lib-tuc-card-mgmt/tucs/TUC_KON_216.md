# TUC_KON_216 — Read Certificate

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4585
**German Name**: LeseZertifikat

## Purpose

Reads an X.509 certificate from a specific file on a card.

## Requirements

- **TIP1-A_4585**: The Konnektor MUST implement the Technical Use Case "LeseZertifikat" according to TUC_KON_216.

### Preconditions

- Card session is valid

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| fileIdentifier | FileIdentifier | No | File identifier of the certificate file |
| sfid | ShortFileIdentifier | No | Short file identifier of the certificate file |
| folder | Folder | Yes | Folder (DF) containing the certificate file |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| certificate | byte[] | DER-encoded X.509 certificate read from the card |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Check CARDSESSION.AUTHSTATE for the addressed file
4. Call TUC_KON_202 (Read File) to read the certificate bytes
5. Return certificate bytes

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given a valid file path to a certificate and read permission, when TUC_KON_216 is called, then the DER-encoded certificate is returned.
- **AC-002**: Given a file path pointing to non-certificate data, when TUC_KON_216 is called, then raw bytes are returned (validation is the caller's responsibility).
