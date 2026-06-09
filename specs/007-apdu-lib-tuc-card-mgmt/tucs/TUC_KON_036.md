# TUC_KON_036 — Provide Professional Role

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_5478
**German Name**: LiefereFachlicheRolle

## Purpose

Derives and returns the professional role (fachliche Rolle) from the card's C.AUT X.509 certificate. Supports HBAx, SMC-B, eGK, and KVK cards.

## Requirements

- **TIP1-A_5478**: The Konnektor MUST implement the Technical Use Case "LiefereFachlicheRolle" according to TUC_KON_036.

### Preconditions

- Card session is valid

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| role | String | Professional role string derived from the card's C.AUT certificate per access profile |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. If Card.TYPE=KVK, return fixed role "Versicherter" immediately (no card access needed)
4. Determine fileIdentifier and folder of the C.AUT certificate for the card type
5. Read certificate via TUC_KON_216
6. Extract ProfessionOIDs from the certificate
7. Map OIDs to role per access profile and return

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given an SMC-B card, when TUC_KON_036 is called, then the role corresponding to the institution type OID is returned.
- **AC-002**: Given a KVK card, when TUC_KON_036 is called, then "Versicherter" is returned without card access.
- **AC-003**: Given an HBAx card, when TUC_KON_036 is called, then the appropriate professional role OID is mapped and returned.
