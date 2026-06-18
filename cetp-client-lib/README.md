# cetp-client-lib

CETP (Connector Event Transport Protocol) client for the konnektor: the **PUSH** side of the
Systeminformationsdienst (`gemSpec_Kon_V5.27.0` §4.1.6). It persists client-system subscriptions and
delivers konnektor events to each subscriber's event sink over TCP/mTLS.

See the feature design under [`specs/010-cetp-client/`](../specs/010-cetp-client/)
(spec, plan, research, data-model, contracts, diagrams).

## What it does

- **`CetpClient`** (`@ApplicationScoped`) observes `KonnektorSystemEvent` asynchronously
  (`@ObservesAsync`) and runs the TUC_KON_256 filter pipeline (topic prefix → access authorisation →
  XPath) over the currently valid subscriptions, then delivers the framed `Event` XML
  (`CETP` + big-endian length + UTF-8 body) to each matching sink.
- **`SubscriptionService`** persists subscriptions (Subscribe/Unsubscribe/RenewSubscriptions/
  GetSubscription) as the `ClientEndpoint` 1—N `Subscription` JPA model, and performs Auto-Unsubscribe
  after `cetp.evt-max-try` consecutive delivery failures to a sink.
- **`CetpTlsContextFactory`** builds the TLS client context: client auth via the konnektor identity
  (SmkCSAKAut / C.AK.AUT, injected as a `@KonnektorClientIdentity KeyManagerFactory`) and PKIX
  validation of the sink's server certificate against the configured client-system trust store.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `cetp.evt-max-try` | `3` | Consecutive-failure threshold for Auto-Unsubscribe (TIP1-A_4611). |
| `cetp.tls.mandatory` | `true` | `true` = CETP1 (TLS), `false` = CETP2 (plain TCP). |
| `cetp.delivery.timeout-ms` | `2000` | Per-attempt connect+send timeout (SC-008). |
| `cetp.tls.truststore.path` | — | Client-system trust store (classpath or filesystem) for PKIX. |
| `cetp.tls.truststore.password` | — | Trust store password. |

## Wiring into a konnektor

`konnektor-soap-server` depends on this module: `KonnektorEventService` delegates the EventService
operations to `SubscriptionService`, and `CetpClientIdentityProducer` exposes the konnektor's
`SmkCSAKAut` key material as the `@KonnektorClientIdentity KeyManagerFactory`. Basis-services raise
events with `Event<KonnektorSystemEvent>.fireAsync(...)`.

## Implementation notes (refinements vs. plan)

- **Transport**: uses JDK `java.net.Socket` / `javax.net.ssl.SSLSocket` rather than Netty. The CETP
  client is a unidirectional, fire-and-forget, one-connection-per-delivery sender; JDK sockets give
  deterministic connect/read timeouts and use the standard JSSE TLS stack with less machinery
  (Principle I — simplicity; Principle VIII — standard interfaces).
- **Decoupling**: this module does **not** depend on `quarkus-sicct-extension` (whose `@Startup`
  `SicctTerminalManager` would run during tests). The konnektor TLS identity is consumed as a
  standard JSSE `KeyManagerFactory` qualified `@KonnektorClientIdentity`; `konnektor-soap-server`
  produces it from `SmkCSAKAut`. No new third-party dependency is introduced (Principle VII).

## Tests

`./mvnw -pl cetp-client-lib verify` — surefire runs the unit + `@QuarkusTest` `*Test` classes;
failsafe runs the `*IT` integration tests (delivery, Auto-Unsubscribe, mTLS) in a separate JVM.
Test method names embed the gematik Afo ID for traceability (Principle V).
