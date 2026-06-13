# TUC_KON_024 — Reset Card

**Source**: gemSpec_Kon V5.27.0, Section 4.1.5.4
**Requirement ID**: TIP1-A_4584-02
**German Name**: Karte zurücksetzen

## Purpose

Resets a card: all security states are cleared on both the card and in Konnektor administration; the MF (Master File) is selected; optionally any in-progress Card-to-Card authentication is aborted.

## Requirements

- **TIP1-A_4584-02**: The Konnektor MUST implement the Technical Use Case "Karte zurücksetzen" according to TUC_KON_024.

### Preconditions

- At least one of ctId/slotId or cardSession must be provided

### Input Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| ctId | TerminalId | No | Card terminal identifier |
| slotId | SlotId | No | Slot identifier within the card terminal |
| cardSession | CardSession | No | Active card session identifying the target card |

### Output Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| — | — | No output parameters |

### Standard Flow

1. If cardSession is given, determine ctId and slotId from it
2. Check card is not reserved by another caller, or that the caller holds the lock
3. Optionally abort any parallel TUC_KON_005 execution in progress
4. Send SICCT RESET ICC for the resolved slotId
5. Clear all security states (CARDSESSION.AUTHSTATE) for the card

### Error Cases

| Error Code | Type | Severity | Description |
|------------|------|----------|-------------|
| 4001 | Internal | Fatal | Internal error |
| 4060 | Resource | Error | Resource busy |
| 4093 | Access | Error | Card reserved by other session |
| 4094 | Timeout | Error | Card access timeout |

## Acceptance Criteria

- **AC-001**: Given the caller holds the lock, when TUC_KON_024 is called, then the card security state is cleared and MF is selected.
- **AC-002**: Given a card reserved by another caller, when TUC_KON_024 is called, then error 4093 is returned.
