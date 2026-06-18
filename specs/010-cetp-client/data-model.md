# Phase 1 Data Model: CETP Client

**Feature**: 010-cetp-client | **Date**: 2026-06-17

Two JPA (Hibernate ORM Panache) entities in `cetp-client-lib`, in a
**`ClientEndpoint` 1—N `Subscription`** relationship. Convention follows
`sicct-lib`'s `CardTerminal` (public-field `PanacheEntityBase`, explicit `@Table`/
`@Column`, UUID surrogate keys).

```
ClientEndpoint (CETP_CLIENT_ENDPOINT)            Subscription (CETP_SUBSCRIPTION)
┌───────────────────────────────┐    1     N    ┌────────────────────────────────────┐
│ id            UUID  (PK)       │──────────────<│ id              UUID (PK)          │
│ eventTo       String (unique)  │               │ subscriptionId  UUID (unique, biz) │
│ host          String           │               │ endpoint        → ClientEndpoint   │
│ port          int              │               │ topic           String            │
│ retainedUntil Instant          │               │ filter          String (XPath?)   │
│ failureCount  int  (per-sink)  │               │ mandantId       String            │
└───────────────────────────────┘               │ clientSystemId  String            │
                                                 │ workplaceId     String            │
                                                 │ terminationTime Instant           │
                                                 └────────────────────────────────────┘
                                                 UNIQUE(endpoint_id, topic,
                                                        mandantId, clientSystemId, workplaceId)
```

---

## Entity: `ClientEndpoint` — table `CETP_CLIENT_ENDPOINT`

The client-side CETP delivery target (Ereignissenke). Persists **independently of
its subscriptions** so it can still receive the `BOOTUP_COMPLETE` notification after
the subscription list is cleared at bootup.

| Field | Type | Constraints | Meaning |
|---|---|---|---|
| `id` | UUID | PK, `@GeneratedValue` | Surrogate key. |
| `eventTo` | String | not null, **unique**, len 256 | Full sink URL `cetp://host:port` from Subscribe's `EventTo`. |
| `host` | String | not null, len 253 | Parsed host (IP or FQDN). |
| `port` | int | not null | Parsed port. |
| `retainedUntil` | Instant | not null | Longest `terminationTime` across this sink's subscriptions; the endpoint URL is retained until this passes (FR-008/FR-017, TUC_KON_256 BOOTUP variant). |
| `failureCount` | int | not null, default 0 | Consecutive failed connection/delivery attempts to this sink (FR-019). Reset to 0 on a successful delivery (FR-021). |

**Lifecycle**:
- Created/looked-up on first Subscribe for a given `eventTo`.
- `retainedUntil` recomputed to `max(existing, newSubscription.terminationTime)` on
  Subscribe/Renew.
- **Auto-Unsubscribe** (FR-020): when `failureCount` reaches `cetp.evt-max-try`, the
  endpoint and all its subscriptions are deleted (cascade) — equivalent to Unsubscribe
  by `EventTo` URL.
- **Bootup** (FR-017): all `Subscription` rows are deleted; `ClientEndpoint` rows with
  `retainedUntil > now` are kept for the `BOOTUP_COMPLETE` send, then deleted.

**Relationships**: `@OneToMany(mappedBy = "endpoint", cascade = ALL, orphanRemoval =
true)` to `Subscription`.

---

## Entity: `Subscription` — table `CETP_SUBSCRIPTION`

A client system's persisted registration of interest in one topic at one sink under
one caller context (TIP1-A_4608, Ablauf Subscribe step 3 `saveSubscription`).

| Field | Type | Constraints | Meaning |
|---|---|---|---|
| `id` | UUID | PK, `@GeneratedValue` | Surrogate key. |
| `subscriptionId` | UUID | not null, **unique** | Business identifier returned by Subscribe; used by Unsubscribe/Renew/GetSubscription. |
| `endpoint` | `ClientEndpoint` | `@ManyToOne`, not null | The event sink (Ereignissenke). |
| `topic` | String | not null, len 256 | Subscribed topic (tree path, e.g. `CARD/INSERTED`). |
| `filter` | String | nullable, len 1024 | Optional XPath filter (default-NS = EventService v7.2, prefix `EVT`). |
| `mandantId` | String | not null | Caller context. |
| `clientSystemId` | String | not null | Caller context. |
| `workplaceId` | String | not null | Caller context. |
| `terminationTime` | Instant | not null | Validity limit; set to `now + 25h` on Subscribe/Renew (FR-010, TIP1-A_4608). |

**Uniqueness key (FR-012)**: `UNIQUE(endpoint_id, topic, mandantId, clientSystemId,
workplaceId)`. A repeat Subscribe matching this key returns the existing
`subscriptionId` and refreshes `filter` + `terminationTime` rather than inserting.

**Validation rules**:
- `eventTo` MUST parse as `cetp://host:port` with a valid port (else Subscribe fails
  4000 — TIP1-A_4608 checkArguments).
- `filter`, if present, MUST be a compilable XPath expression; a malformed/eval-error
  filter excludes only this subscription from a given delivery (FR-002, error 4095) and
  never affects others (Edge Cases).
- Access authorisation (TUC_KON_000) MUST pass before persistence (FR-011).

**Validity predicate (delivery selection, FR-002)**: a subscription is *valid* iff
`terminationTime > now`. Bootup starts with zero subscriptions (FR-017).

---

## State transitions — Subscription lifecycle

See `diagrams/state-subscription.puml`. Summary:

```
(none) --Subscribe--> ACTIVE
ACTIVE --RenewSubscriptions (terminationTime := now+25h)--> ACTIVE
ACTIVE --Unsubscribe (by subscriptionId or eventTo)--> (deleted)
ACTIVE --terminationTime < now--> EXPIRED --(excluded from delivery; reaped)--> (deleted)
ACTIVE --endpoint.failureCount reaches EVT_MAX_TRY--> AUTO_UNSUBSCRIBED (cascade delete)
ACTIVE --konnektor restart (bootup)--> (deleted; endpoint URL retained until retainedUntil)
```

The **failure counter** lives on `ClientEndpoint` and transitions:
`failureCount := 0` on any successful delivery to the sink (FR-021);
`failureCount += 1` on each failed connection/delivery attempt (FR-019, FR-028);
when the sink never answers at application level, only connection-establishment
failures increment it (FR-022).

---

## Derived / non-persistent types (not entities)

- **`KonnektorSystemEvent`** (record) — the CDI event observed by `CetpClient`:
  `topic`, `eventType` (`EventType`), `severity` (`EventSeverity`),
  `parameters: Map<String,String>`. Maps to the JAXB `Event` for the wire (TAB_KON_030).
- **`EventType`** = Operation | Security | Infrastructure; **`EventSeverity`** = Info |
  Warning | Error | Fatal — mirror `EventService.xsd` enums.
