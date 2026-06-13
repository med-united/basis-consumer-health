# TUC_KON_208 — Send Secured APDU

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: A_26069-01
**German Name**: Sende gesicherte APDU

## Purpose

Validates a signed APDU scenario and generates a secured APDU execution request for downstream transport layers, including sequence-number replay protection.

## Requirements

- **A_26069-01**: The Konnektor MUST implement the Technical Use Case "Sende gesicherte APDU" according to TUC_KON_208.

### Preconditions

- An active card session with a matching sessionID exists
- The caller holds the session lock

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| signedScenario | SignedScenario | Yes | Cryptographically signed APDU scenario containing command APDUs and expected response codes |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| securedExecutionRequest | SecuredExecutionRequest | Generated secured APDU request payload for downstream execution |

### Standard Flow

1. Set expected status code list
2. Extract scenario from input
3. Verify CardSession with sclientSessionID exists
4. Verify lock exists for the caller
5. Verify sequence number is incremented by exactly 1 from the previous value
6. For each scenario item: process expected return codes and generate command APDU execution entries (via TUC_KON_200)
7. Return secured execution request

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4060 | Resource | Error | Resource busy |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |
| Replay error | Security | Fatal | Sequence number is not incremented by 1 (replay attack detected) |

## Acceptance Criteria

- **AC-001**: Given a valid signed scenario with the correct sequence number, when TUC_KON_208 is called, then a secured execution request is returned and the sequence number is incremented.
- **AC-002**: Given a replayed scenario with an old sequence number, when TUC_KON_208 is called, then an error is returned.
