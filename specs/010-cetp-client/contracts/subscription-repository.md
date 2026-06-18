# Contract: `SubscriptionService` (cetp-client-lib → KonnektorEventService)

**Component**: `de.servicehealtherx.cetp.subscription.SubscriptionService`
(`@ApplicationScoped`). Consumed by `KonnektorEventService` in `konnektor-soap-server`
(which now depends on `cetp-client-lib`). Source: `gemSpec_Kon_V5.27.0` §4.1.6.5
(Subscribe / Unsubscribe / RenewSubscriptions / GetSubscription).

All methods are transactional (`@Transactional`) and operate on the
`ClientEndpoint`/`Subscription` entities (data-model.md).

## Operations

### `subscribe(SubscribeContext) → SubscribeResult`  *(TIP1-A_4608)*

Input: `mandantId, clientSystemId, workplaceId, eventTo, topic, filter?`.

1. Validate `eventTo` parses as `cetp://host:port`; else `Status=4000` (checkArguments).
2. Caller MUST have passed access authorisation (TUC_KON_000) — FR-011.
3. Look up / create the `ClientEndpoint` for `eventTo`.
4. Upsert the `Subscription` on uniqueness key
   `(endpoint, topic, mandantId, clientSystemId, workplaceId)` (FR-012): if present,
   keep `subscriptionId`, refresh `filter`; else generate a new `subscriptionId`.
5. Set `terminationTime = now + 25h`; bump `endpoint.retainedUntil` to the max
   (FR-010).

Returns `subscriptionId` + `terminationTime`.

### `unsubscribe(subscriptionId?, eventTo?) → void`  *(TIP1-A_4609)*

Remove the subscription(s) matching `subscriptionId` and/or `eventTo`. When an
endpoint has no remaining subscriptions it is removed. Precondition: a prior Subscribe
existed for the `eventTo` (else `Status` error per TAB_KON_576).

### `renew() → List<Renewal>`  *(TIP1-A_5112)*

For each subscription with `terminationTime > now`, set
`terminationTime = now + 25h`, bump `endpoint.retainedUntil`, and return
`(subscriptionId, terminationTime)`. Subscriptions already expired are **not** renewed.

### `getSubscriptions() → List<SubscriptionView>`

Return persisted subscriptions: `subscriptionId, eventTo, topic, filter?`.

### `autoUnsubscribe(ClientEndpoint) → void`  *(TIP1-A_4611, internal)*

Invoked by the delivery path when `endpoint.failureCount` reaches `cetp.evt-max-try`:
delete the endpoint and cascade-delete its subscriptions (equivalent to Unsubscribe by
`EventTo`).

### `clearOnBootup() → retained URL list`  *(TIP1-A_4613 / FR-017)*

Delete all `Subscription` rows; return the `ClientEndpoint` URLs with
`retainedUntil > now` (for the BOOTUP_COMPLETE send), then delete endpoints with
`retainedUntil <= now`.

## Capacity

MUST support ≥ 999 concurrent subscriptions (FR-018, TIP1-A_4612). No hard cap imposed
by default; an optional per-target / total cap MAY be configured.

## Test obligations

- `test_TIP1_A_4608_subscribe_persists_with_terminationTime_now_plus_25h()`
- `test_FR_012_duplicate_subscribe_returns_existing_id()`
- `test_TIP1_A_4609_unsubscribe_removes_subscription()`
- `test_TIP1_A_5112_renew_extends_only_valid_subscriptions()`
- `test_TIP1_A_4613_bootup_starts_with_empty_subscription_list()`
- `test_TIP1_A_4612_supports_999_subscriptions()`
