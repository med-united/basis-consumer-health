# TUC_KON_220 — Decrypt

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4582
**German Name**: Entschlüssele

## Purpose

Uses the private key of the card to decrypt data that was encrypted with the card's corresponding public key.

## Requirements

- **TIP1-A_4582**: The Konnektor MUST implement the Technical Use Case "Entschlüssele" according to TUC_KON_220.

### Preconditions

- pinRef is authenticated in CARDSESSION.AUTHSTATE

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cardSession | CardSession | Yes | Active card session identifying the target card |
| pinRef | PinRef | Yes | Reference to the PIN that authenticates use of the decryption key |
| keyRef | KeyRef | Yes | Reference to the private key on the card |
| algorithmUsId | AlgorithmId | Yes | Identifier of the decryption algorithm to use |
| encryptedData | byte[] | Yes | Data encrypted with the card's public key |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| plainData | byte[] | The decrypted plaintext |

### Standard Flow

1. Determine card from cardSession
2. Check no foreign lock is present
3. Verify pinRef is authenticated in CARDSESSION.AUTHSTATE
4. Select DF and private key on the card
5. Set keyRef and algorithmUsId on the card
6. Execute PSO: DECIPHER with encryptedData
7. Return plainData

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| COS errors | Card | Error | Card-specific error codes per gemSpec_COS |

## Acceptance Criteria

- **AC-001**: Given data encrypted with the card's public key and an authenticated PIN, when TUC_KON_220 is called, then the original plaintext is returned.
- **AC-002**: Given data encrypted with the wrong key, when TUC_KON_220 is called, then a card error is returned.
