# Contract: GetCards

**Service**: EventService v7.2
**Endpoint**: `/conn/EventService`
**WSDL type**: `EventServicePortType.getCards(GetCards) → GetCardsResponse`
**Spec refs**: FR-041, FR-062, FR-064, SC-012, SC-017

## Behaviour

Returns the cached in-memory card list, **aggregated across every CryptoProvider's own CM_CARD_LIST** (PC/SC and SICCT) into one unified, transport-spanning response. MUST NOT issue any commands to card terminals/readers (read-only). Cards from a directly PC/SC-connected reader and from a SICCT terminal are indistinguishable in structure, differing only in `CtId` (and capability-dependent fields). Each `CardHandle` appears at most once (system-wide uniqueness across the union, FR-067). Filters applied server-side before response.

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

- `GetCardsResponse` is assembled by concatenating each provider's `CmCardList.findAll(filter)` (PC/SC + SICCT) — zero terminal/reader I/O
- Cards for terminals/readers that are currently disconnected/unplugged are NOT returned (their handles are already invalidated by FR-044/FR-069, both transports)
- `CtId` distinguishes the source: a synthesized name-derived UUID for a PC/SC reader (FR-068) or the SICCT terminal id
- Response latency target: ≤ 100 ms (in-memory aggregation)
