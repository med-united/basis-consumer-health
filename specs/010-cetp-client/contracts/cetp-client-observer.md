# Contract: `CetpClient` event observer + TUC_KON_256 delivery pipeline

**Component**: `de.servicehealtherx.cetp.CetpClient` (`@ApplicationScoped` singleton).
**Source**: `gemSpec_Kon_V5.27.0` TUC_KON_256 (TIP1-A_4598-02), §4.1.6.

## Inbound event (CDI)

```java
public record KonnektorSystemEvent(
        String topic,                    // e.g. "CARD/INSERTED"
        EventType eventType,             // Operation | Security | Infrastructure
        EventSeverity severity,          // Info | Warning | Error | Fatal
        Map<String,String> parameters) { }
```

Producers (basis-services) fire it asynchronously:
`event.fireAsync(new KonnektorSystemEvent(...))`.

`CetpClient` observes it:
```java
void onKonnektorEvent(@ObservesAsync KonnektorSystemEvent e) { ... }
```
The observer MUST NOT block the producer (FR-001) — `@ObservesAsync` guarantees a
separate thread.

## Delivery pipeline (per observed event)

1. **Select valid subscriptions**: all `Subscription` with `terminationTime > now`
   (FR-002). For `topic = BOOTUP/BOOTUP_COMPLETE`, instead select the retained
   `ClientEndpoint` URLs and emit with an empty `SubscriptionID` (FR-008).
2. **Filter — topic** (`TopicPrefixFilter`): keep subscription iff `event.topic`
   equals or starts with `subscription.topic` (case-insensitive). *(TUC_KON_256 5a)*
3. **Filter — access** (`AccessAuthorizationFilter`): keep iff the TUC_KON_000 access
   check passes for `(mandantId, clientSystemId, workplaceId)` of the subscription
   (and, when `cardHandle`/`ctId` are in `parameters`, scoped accordingly).
   *(TUC_KON_256 5b)*
4. **Filter — XPath** (`XPathExpressionFilter`): keep iff the subscription's `filter`
   (if any) evaluated against the event XML yields a non-empty result; an evaluation
   error excludes only this subscription (error 4095) and never aborts the event.
   *(TUC_KON_256 5c)*
5. **Deliver**: for each surviving subscription, build the `Event` XML with that
   subscription's ID and send via `CetpEventSender` (see cetp-wire-protocol.md). On
   success reset the endpoint failure counter; on failure increment it and, at
   `EVT_MAX_TRY`, Auto-Unsubscribe (see subscription-repository.md).

Fan-out is concurrent across subscriptions/sinks; one slow sink must not delay others
beyond 2 s (SC-008).

## `EventDeliveryFilter` interface (3 implementations)

```java
interface EventDeliveryFilter {
    boolean keep(KonnektorSystemEvent event, Subscription subscription);
}
```
Implementations: `TopicPrefixFilter`, `AccessAuthorizationFilter`,
`XPathExpressionFilter`. Applied in that order; first `false` drops the subscription.

## Test obligations

- `test_TIP1_A_4598_02_event_delivered_only_to_matching_topic_subscriptions()`
- `test_TIP1_A_4598_02_xpath_filter_excludes_non_matching_subscription()`
- `test_FR_002_expired_subscriptions_excluded_from_delivery()`
- `test_FR_001_observer_runs_off_producer_thread()`
- `test_SC_008_one_unreachable_sink_does_not_block_others()`
