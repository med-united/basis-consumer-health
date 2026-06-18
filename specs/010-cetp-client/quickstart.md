# Quickstart & Validation Guide: CETP Client

**Feature**: 010-cetp-client | **Date**: 2026-06-17

This guide validates the feature end-to-end. It references
[data-model.md](data-model.md) and [contracts/](contracts/) rather than repeating
field lists. It is a run/validation guide — implementation belongs in `tasks.md`.

## Prerequisites

- JDK 21, the reactor builds (`./mvnw -q -pl cetp-client-lib -am install`).
- New module `cetp-client-lib` present in the root `pom.xml` `<modules>` and
  `konnektor-soap-server` depending on it.
- H2 datasource active for tests (as in `konnektor-soap-server`).

## Build & test

```bash
# Unit + integration tests for the new module
./mvnw -q -pl cetp-client-lib -am test

# Full reactor (ensures konnektor-soap-server still builds against the new dep)
./mvnw -q -DskipITs=false verify
```

## Scenario 1 — Subscribe → event → CETP delivery over plain TCP (CETP2)

Validates User Story 1 + 2 (FR-001..FR-007, FR-009..FR-012).

1. Start an in-process TCP listener on `127.0.0.1:9200` (test helper `FakeEventSink`).
2. `SubscriptionService.subscribe(ctx, eventTo="cetp://127.0.0.1:9200", topic="CARD")`.
   - **Expect**: a `subscriptionId` returned; a `CETP_SUBSCRIPTION` row with
     `terminationTime ≈ now + 25h` and a `CETP_CLIENT_ENDPOINT` row for the URL.
3. Fire `event.fireAsync(new KonnektorSystemEvent("CARD/INSERTED", Operation, Info, params))`.
4. **Expect** on the listener: one CETP frame — bytes `CETP`, a big-endian length, then
   a UTF-8 `Event` XML carrying `Topic=CARD/INSERTED`, `Type=Operation`, `Severity=Info`,
   the `subscriptionId`, and the parameters (see [cetp-wire-protocol.md](contracts/cetp-wire-protocol.md)).
5. Fire `KonnektorSystemEvent("CT/CONNECTED", …)` → **Expect**: no frame (topic mismatch).

## Scenario 2 — Subscription lifecycle

Validates FR-012..FR-017.

- Repeat the same Subscribe → **Expect**: the **same** `subscriptionId` (no duplicate).
- `renew()` → **Expect**: `terminationTime` extended for the valid subscription.
- `unsubscribe(eventTo="cetp://127.0.0.1:9200")` → **Expect**: row removed;
  `getSubscriptions()` no longer lists it.
- Restart the app (or call `clearOnBootup()`) → **Expect**: `CETP_SUBSCRIPTION` empty;
  retained endpoint URL still receives a `BOOTUP/BOOTUP_COMPLETE` frame with an **empty**
  `SubscriptionID`.

## Scenario 3 — Auto-Unsubscribe on repeated failure

Validates User Story 3 (FR-019..FR-023, TIP1-A_4611).

1. Set `cetp.evt-max-try=3`. Subscribe with `eventTo` pointing at an **unused** port.
2. Fire 2 matching events → **Expect**: subscription still present (`failureCount=2`).
3. Have the sink accept one delivery → **Expect**: `failureCount` resets to 0.
4. Make the sink unreachable again; fire 3 matching events →
   **Expect**: after the 3rd consecutive failure the subscription (and its endpoint) are
   removed; no further deliveries are attempted.

## Scenario 4 — mTLS (CETP1)

Validates User Story 4 (FR-024..FR-028, TIP1-A_4595/5009, A_21760-02).

1. Set `cetp.tls.mandatory=true` and point `cetp.tls.truststore.*` at a test trust store
   containing the fake sink's CA.
2. Start a TLS `FakeEventSink` that **requests** client authentication.
3. Subscribe + fire a matching event → **Expect**: TLS handshake completes
   mutually authenticated (konnektor presents its SmkCSAKAut/C.AK.AUT cert) and the
   frame arrives.
4. Point at a sink whose server cert does **not** chain to the trust store →
   **Expect**: handshake rejected via PKIX, no frame delivered, and the attempt counts
   toward Auto-Unsubscribe.
5. Set `cetp.tls.mandatory=false` → **Expect**: delivery over plain TCP.

## Success signals (map to Success Criteria)

| Scenario | Criteria |
|---|---|
| 1 | SC-001, SC-002 |
| 2 | SC-007 |
| 3 | SC-004, SC-005 |
| 4 | SC-006 |
| 1 (fan-out, ≥999 subs, 1 dead sink) | SC-003, SC-008 |
