# TUC_KON_005 — Card-to-Card Authentication

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4572
**German Name**: Card-to-Card authentisieren

## Purpose

Performs cryptographic card-to-card authentication between a source card (typically SMC-B) and a target card (typically eGK). Generation 1+ uses RSA; Generation 2 uses elliptic curves.

## Requirements

- **TIP1-A_4572**: The Konnektor MUST implement the Technical Use Case "Card-to-Card authentisieren" according to TUC_KON_005.

### Preconditions

- Neither source nor target card is foreign-locked
- Key reference combination is valid per TAB_KON_673

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| sourceCardSession | CardSession | Yes | Card session for the source card (typically SMC-B) |
| targetCardSession | CardSession | Yes | Card session for the target card (typically eGK) |
| authMode | AuthMode | Yes | Authentication mode per TAB_KON_673 |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| — | — | No direct output; authentication state is updated in CARDSESSION.AUTHSTATE of target card |

### Standard Flow

1. Determine both source and target cards from their respective card sessions
2. Check neither card is foreign-locked
3. Validate key reference combination per TAB_KON_673
4. Select CV certificates on both cards
5. Check CED (certificate effective date)
6. Select key references on both cards
7. Execute mutual or one-sided authentication protocol

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4060 | Resource | Error | Resource busy |
| 4071 | Config | Error | Missing C2C card |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given SMC-B and eGK Generation 1+ with valid CV certificates, when TUC_KON_005 is called in one-sided mode, then the eGK AUTHSTATE is updated.
- **AC-002**: Given an expired CV certificate, when TUC_KON_005 is called, then an error is returned.
