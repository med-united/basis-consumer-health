# Contract: GetCards

**Service**: EventService v7.2
**Endpoint**: `/conn/EventService`
**WSDL type**: `EventServicePortType.getCards(GetCards) → GetCardsResponse`
**Spec refs**: FR-041, SC-012

## Behaviour

Returns the cached in-memory card list. MUST NOT issue any commands to card terminals (read-only). Filters applied server-side before response.

## Request Parameters

| Parameter                | Required | Description                                                                           |
| ------------------------ | -------- | ------------------------------------------------------------------------------------- |
| `Context.MandantId`      | Yes      | Tenant scope; only cards accessible to this tenant are returned                       |
| `Context.ClientSystemId` | Yes      | Client system identifier                                                              |
| `Context.WorkplaceId`    | Yes      | Workplace identifier (enforces single-workplace eGK access)                           |
| `@mandantwide`           | No       | `true` = return all cards for mandant+csid; `false` (default) = workplace-scoped only |
| `CtId`                   | No       | Filter: only return cards in this terminal                                            |
| `SlotId`                 | No       | Filter: only return the card in this specific slot (requires `CtId`)                  |
| `CardType`               | No       | Filter: one of `EGK`, `KVK`, `HBAx`, `SM-B`, `SMC-KT`, `UNKNOWN`                      |

## Response

| Field                            | Always present       | Description                                          |
| -------------------------------- | -------------------- | ---------------------------------------------------- |
| `Status`                         | Yes                  | OK or error code                                     |
| `Cards.Card[]`                   | Yes (may be empty)   | One element per occupied, accessible slot            |
| `Card.CardHandle`                | Yes                  | Opaque handle string (UUID)                          |
| `Card.CardType`                  | Yes                  | Enum from TAB_KON_500                                |
| `Card.Kvnr`                      | eGK only             | KVNR unveränderbarer Teil                            |
| `Card.CertificateExpirationDate` | Non-KVK/unknown only | AUT certificate expiry; ECC cert preferred for G2.1+ |

## Error Codes

| Code | Condition               |
| ---- | ----------------------- |
| 4000 | Syntax error in request |

## Implementation Notes

- `GetCardsResponse` is assembled entirely from `CardHandleRegistry.findAll(filter)` — zero terminal I/O
- Cards for terminals that are currently disconnected are NOT returned (handles for disconnected terminals are already invalidated by FR-044)
- Response latency target: ≤ 100 ms (in-memory lookup)
