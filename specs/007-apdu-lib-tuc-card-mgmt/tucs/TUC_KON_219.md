# TUC_KON_219 — Sign

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4581
**German Name**: Signiere

## Purpose

Uses the private key of the card to produce a cryptographic signature or authentication token.

## Requirements

- **TIP1-A_4581**: The Konnektor MUST implement the Technical Use Case "Signiere" according to TUC_KON_219.

### Preconditions

- pinRef is authenticated in CARDSESSION.AUTHSTATE

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| pinRef | PinRef | Yes | Reference to the PIN that authenticates use of the signing key |
| keyRef | KeyRef | Yes | Reference to the private key on the card |
| algorithmUsId | AlgorithmId | Yes | Identifier of the signing algorithm to use |
| dataToBeSigned | byte[] | Yes | Data to be signed |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| chiffrat | byte[] | The resulting cryptographic signature |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Verify pinRef is authenticated in CARDSESSION.AUTHSTATE
4. Select DF and set keyRef on the card
5. Set algorithmUsId on the card
6. Execute PSO: COMPUTE DIGITAL SIGNATURE with dataToBeSigned
7. Return signature as chiffrat

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given an authenticated PIN and valid key reference, when TUC_KON_219 is called, then a signature is returned that verifies with the card's corresponding public key.
- **AC-002**: Given an unauthenticated pinRef, when TUC_KON_219 is called, then an error is returned.
