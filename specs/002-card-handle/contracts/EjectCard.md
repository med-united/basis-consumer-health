# Contract: EjectCard

**Service**: CardTerminalService v1.1
**Endpoint**: `/conn/CardTerminalService`
**WSDL type**: `CardTerminalServicePortType.ejectCard(EjectCard) → EjectCardResponse`
**Spec refs**: FR-051–FR-056, FR-063, FR-070, FR-071, TUC_KON_057 (TAB_KON_725)

## Behaviour

Ejects a card. Over the SICCT transport (and any reader with `hasMechanicalEject=true`) this sends EJECT ICC with "Delivery: Mechanical Throwout" to physically eject the card. Card removal triggers `CT/SLOT_FREE` → `CARD/REMOVED` event and handle invalidation. Can address the card by `CardHandle` OR by `Slot` (ctId + slotId) — not both.

**Logical eject for non-ejecting readers (FR-070)**: when the addressed reader has `hasMechanicalEject=false` (typical directly PC/SC-connected reader), the operation performs a **logical eject** — it removes the `CardObject` from CM_CARD_LIST, fires `CARD/REMOVED`, and returns `Status=OK` **without** error 4203 (error 4203 applies only to readers that physically eject but whose card the user does not remove in time). The display prompt is skipped when `hasDisplay=false` (FR-071).

## Request Parameters

| Parameter                   | Required | Description                                                                                           |
| --------------------------- | -------- | ----------------------------------------------------------------------------------------------------- |
| `Context.MandantId`         | Yes      | Access context (passed to TUC_KON_000)                                                                |
| `Context.ClientSystemId`    | Yes      |                                                                                                       |
| `Context.WorkplaceId`       | Yes      |                                                                                                       |
| `CardHandle`                | One of   | Addresses the card by handle; mutually exclusive with `Slot`                                          |
| `Slot.CtId` + `Slot.SlotId` | One of   | Addresses the card by terminal + slot                                                                 |
| `DisplayMsg`                | No       | Message on terminal display; if omitted, default per TIP1-A_5408 used                                 |
| `TimeOut`                   | No       | Milliseconds to wait for user to remove card after mechanical eject; default **20 000 ms** if omitted |

## Response

| Field    | Always present | Description |
| -------- | -------------- | ----------- |
| `Status` | Yes            | OK or error |

## Error Codes

| Code | Condition                                                             |
| ---- | --------------------------------------------------------------------- |
| 4000 | Syntax error                                                          |
| 4007 | Invalid terminal ID                                                   |
| 4039 | Terminal display in use                                               |
| 4044 | Terminal access error                                                 |
| 4093 | Card exclusively reserved by another session (FR-053)                 |
| 4097 | Invalid slot ID                                                       |
| 4101 | Supplied `CardHandle` is invalid or already invalidated               |
| 4203 | Card mechanically ejected but user did not remove it within `TimeOut` |
| 4221 | Terminal not active                                                   |
| 4222 | Terminal not connected                                                |

## Implementation Flow

```
1. checkArguments → error 4000; resolve ctId/slotId from CardHandle if needed (via CmCardList)
2. TUC_KON_000 access check → propagate error on failure
3. check eGK session lock in CmCardList
   → if locked by a DIFFERENT holder: error 4093
4. Send SICCT_EJECT_ICC(ctId, slotId, displayMessage, timeoutMs, deliveryMechanical=true)
   → on 4203: card deactivated but not removed → propagate error 4203
   → even if CmCardList shows no card in slot: send command anyway (FR-054)
5. Card removal triggers CT/SLOT_FREE event → CmCardList.invalidate(cardHandle)
   → CARD/REMOVED CDI event fired → SicctEventPublisher logs it
6. return EjectCardResponse(status=OK)
```

## Notes

- `TimeOut` is in **milliseconds** (unlike RequestCard which uses seconds)
- Even when the slot appears empty in `CmCardList`, the SICCT command is always sent (FR-054); if the terminal reports no error, neither does the Konnektor
- The card lock check (step 3) only blocks cross-holder ejection; the lock holder itself may eject its own locked card
