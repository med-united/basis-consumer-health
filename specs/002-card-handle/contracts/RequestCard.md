# Contract: RequestCard

**Service**: CardTerminalService v1.1
**Endpoint**: `/ws/conn/CardTerminalService`
**WSDL type**: `CardTerminalServicePortType.requestCard(RequestCard) → RequestCardResponse`
**Spec refs**: FR-033–FR-040, SC-011, TUC_KON_056 (TAB_KON_723)

## Behaviour

Actively prompts a card terminal slot to accept a card via SICCT REQUEST ICC. Returns when a card is present (whether pre-existing or freshly inserted). Drives `TUC_KON_001` on card insertion which fires `CARD/INSERTED` and creates the `CardHandle`.

## Request Parameters

| Parameter | Required | Description |
|---|---|---|
| `Context.MandantId` | Yes | Access context (passed to TUC_KON_000) |
| `Context.ClientSystemId` | Yes | |
| `Context.WorkplaceId` | Yes | |
| `Slot.CtId` | Yes | Target card terminal |
| `Slot.SlotId` | Yes | Target slot (1-based) |
| `CardType` | No | Optional type filter (`EGK`, `KVK`, `HBAx`, `SM-B`); mismatch → error 4051 |
| `DisplayMsg` | No | Text shown on terminal display; if omitted, default per TIP1-A_5408 used |
| `TimeOut` | No | Seconds to wait; default **20 s** if omitted |

**Default display messages** (TIP1-A_5408):

| Requested type | Message |
|---|---|
| EGK | "Bitte eGK in SLOT X stecken" |
| HBAx / HBA-qSig | "Bitte HBA in SLOT X stecken" |
| SMC-B | "Bitte SMC-B in SLOT X stecken" |
| None / other | "Bitte Karte in SLOT X stecken" |

Slot reference omitted if terminal has no slot selection.

## Response

| Field | Always present | Description |
|---|---|---|
| `Status` | Yes | OK or error |
| `AlreadyInserted` | No (optional element) | `true` if card was present before call; `false` / absent if inserted on request |
| `Card` | On success | Same structure as `GetCards` `Card` element |

## Error Codes

| Code | Condition |
|---|---|
| 4000 | Syntax error |
| 4007 | Invalid terminal ID |
| 4039 | Terminal display in use by another operation |
| 4044 | Terminal access error |
| 4051 | Inserted card type does not match requested `CardType` |
| 4058 | Unsupported card type requested |
| 4097 | Invalid slot ID |
| 4202 | Timeout — no card inserted within `TimeOut` period |
| 4221 | Terminal not active |
| 4222 | Terminal not connected |

## Implementation Flow

```
1. checkArguments → error 4000 on syntax / 4058 on unsupported cardType
2. TUC_KON_000 access check → propagate error on failure
3. check CardHandleRegistry for existing handle at (ctId, slotId)
   → if present: set alreadyInserted=true; skip SICCT call; go to step 5
4. Send SICCT_REQUEST_ICC(ctId, slotId, displayMessage, timeoutSec)
   → on timeout: error 4202
   → on card inserted: TUC_KON_001 fires CARD/INSERTED; CardHandle registered
5. if cardType filter set: read card AID; compare; error 4051 on mismatch
6. return RequestCardResponse(status=OK, alreadyInserted, card=mapToCard(handle))
```
