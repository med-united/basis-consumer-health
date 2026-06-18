# Contract: CETP Wire Protocol (konnektor → client system)

**Direction**: konnektor (TLS/TCP client) → client-system event sink (server).
**Transport**: TCP, optionally TLS (TIP1-A_5536). Unidirectional; **no application
reply** (FR-006). Source: `gemSpec_Kon_V5.27.0` §4.1.6.1, TAB_KON_030, TIP1-A_4596.

## Frame format (one event = one frame)

```
+--------+--------+--------+--------+--------+--------+--------+ ... +
|  'C'   |  'E'   |  'T'   |  'P'   |   length (uint32, BE)   | XML  |
| 0x43   | 0x45   | 0x54   | 0x50   |   4 bytes               | body |
+--------+--------+--------+--------+-------------------------+ ... +
```

- Bytes 0–3: ASCII magic `CETP` (`0x43 0x45 0x54 0x50`).
- Bytes 4–7: unsigned 32-bit **big-endian** length = byte count of the XML body.
- Bytes 8..: the UTF-8-encoded XML `Event` document (below).

`CetpFrameCodec.encode(byte[] xmlUtf8) -> ByteBuf` MUST produce exactly this layout.
Multiple frames MAY be sent on one connection, but this implementation opens one
connection per delivery (D2) and writes exactly one frame.

## XML body — `Event` (EventService.xsd v7.2, namespace
`http://ws.gematik.de/conn/EventService/v7.2`)

| Element | Source field | Notes |
|---|---|---|
| `Event/Topic` | `KonnektorSystemEvent.topic` | e.g. `CARD/INSERTED`. |
| `Event/Type` | `eventType` | `Operation` \| `Security` \| `Infrastructure`. |
| `Event/Severity` | `severity` | `Info` \| `Warning` \| `Error` \| `Fatal`. |
| `Event/SubscriptionID` | matched `Subscription.subscriptionId` | **Empty element** for `BOOTUP/BOOTUP_COMPLETE` (FR-008). |
| `Event/Message/Parameter[]` | `parameters` map | each → `Parameter/Key` (case-sensitive) + `Parameter/Value`. |

Body MUST be UTF-8 (TAB_KON_030 Hinweise). Built from the
`de.gematik.ws.conn.eventservice.v7` JAXB types via `EventXmlWriter`.

## Delivery semantics

- Target host:port is parsed from the subscription's `eventTo` (`cetp://host:port`).
- One delivery attempt is bounded by **2 s** (connect + write); expiry or any
  IO/TLS error = one failed attempt (FR-005a, FR-028) feeding Auto-Unsubscribe.
- Success = bytes flushed to the (optionally TLS-secured) socket; no read/ack is
  performed.

## Test obligations

- `test_TIP1_A_4596_frame_starts_with_CETP_and_big_endian_length()`
- `test_TIP1_A_4596_event_body_is_utf8_and_carries_topic_type_severity_subscriptionId()`
- `test_FR_008_bootup_complete_uses_empty_subscription_id()`
