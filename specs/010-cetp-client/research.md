# Phase 0 Research: CETP Client

**Feature**: 010-cetp-client | **Date**: 2026-06-17

This document resolves the open technical decisions for the plan and records the
mandatory external-library evaluation (Principle VII) and standard-interface research
(Principle VIII). No item remains marked NEEDS CLARIFICATION.

---

## D1 — Event seam: how konnektor events reach `CetpClient`

**Decision**: Define a CDI event record `KonnektorSystemEvent(topic, eventType,
severity, Map<String,String> parameters)` in `cetp-client-lib`. Basis-services fire
it with `jakarta.enterprise.event.Event<KonnektorSystemEvent>.fireAsync(...)`;
`CetpClient` observes it with `@ObservesAsync`.

**Rationale**:
- The user explicitly requested `@ObservesAsync`. `fireAsync`/`@ObservesAsync` is the
  CDI-standard asynchronous event mechanism (Principle VIII — adopt the standard
  interface, do not build a custom bus). It runs the observer off the producer thread,
  satisfying FR-001 ("without blocking the event producer").
- The codebase already models domain events as records (`CardInsertedEvent` in
  `sicct-lib`) but does not yet fire them through CDI. `KonnektorSystemEvent` is the
  single generic envelope for the TUC_KON_256 input (`topic`, `eventType`, `severity`,
  key/value `parameters`), so every basis-service raises events the same way and
  `CetpClient` needs exactly one observer method.
- `eventType` (Operation/Security/Infrastructure) and `severity`
  (Info/Warning/Error/Fatal) map 1:1 to `EventService.xsd` enums (TAB_KON_030).

**Alternatives considered**:
- *Observe `CardInsertedEvent`/per-topic event types directly* — rejected: would force
  `CetpClient` to know every concrete event class and add an observer per topic; the
  generic envelope keeps it open/closed.
- *Custom in-process event bus / `BlockingQueue`* — rejected: duplicates CDI and
  violates Principle VIII.
- *Observe the gematik JAXB `Event` type directly* — rejected: couples producers to the
  wire format; the JAXB `Event` is built at the delivery edge, not at the source.

**Note**: The producers (basis-services firing `KonnektorSystemEvent`) are largely
out of this feature's scope; this feature provides the event record + the observer.
Wiring existing event sources (card service, CT service, MGM/TLS_CERT, BOOTUP) to fire
it is incremental and tracked per-Afo.

---

## D2 — CETP connection lifecycle

**Decision**: Open a **fresh** outbound TCP (or TLS) connection per delivery attempt
using a Netty `Bootstrap`, write the framed event, then close. A single delivery
attempt is bounded by a **2 s** combined connect + write timeout
(`CONNECT_TIMEOUT_MILLIS` + a write/idle timeout). Timeout expiry = one failed attempt.

**Rationale**: Matches the clarification answer and the "leichtgewichtiges Protokoll"
intent of `gemSpec_Kon` §4.1.6 — no application reply (FR-006), so connection reuse
buys little. Per-attempt connections give unambiguous failure counting for
Auto-Unsubscribe (FR-019–FR-022) and avoid per-sink connection-state management.
Fan-out across sinks runs on Netty's event loop / a managed executor, so one slow sink
cannot block others beyond the 2 s bound (SC-008).

**Alternatives considered**: pooled/persistent per-sink connections (lower per-event
overhead, but adds lifecycle + complicates failure counting) — rejected per
clarification.

---

## D3 — mTLS: client authentication identity + server-cert trust

**Decision**:
- **Client identity (FR-025, A_21760-02)**: reuse `SmkCSAKAut` from
  `quarkus-sicct-extension`, whose `getKeyManagerFactory()` is initialised from the
  C.AK.AUT key (HSM via SunPKCS11, P12 fallback) by `SmkCSAKAutProvider`. The CETP TLS
  context is built `SslContextBuilder.forClient().keyManager(smkCSAKAut.getKeyManagerFactory())`
  so the konnektor presents its client certificate **when the server requests it**
  (CETP1 semantics — TIP1-A_4595).
- **Server-cert trust (FR-026, TIP1-A_5009)**: build a `TrustManagerFactory` with the
  **PKIX** algorithm (`TrustManagerFactory.getInstance("PKIX")`) initialised from a
  configured **client-system trust store** (`KeyStore`). The resulting `X509TrustManager`
  performs PKIX certification-path validation of the client system's TLS server
  certificate; an invalid path aborts the handshake → delivery refused → counts as a
  failed attempt (FR-028).
- **Provider/ciphers**: BouncyCastle JSSE (`BouncyCastleJsseProvider`) + the existing
  `GematikSSLConfig.CIPHER_SUITE_LIST`, mirroring `KonnektorSslHandler`.
- **CETP2 (FR-027)**: when secure transport is not mandated, deliver over plain TCP
  (no `SslHandler` added to the pipeline).

**Rationale**: All TLS building blocks already exist for the konnektor↔terminal
channel; reusing them honours Principle VII (no new crypto code) and Principle VIII
(JSSE `KeyManagerFactory`/`TrustManagerFactory` are the standard SPIs). PKIX is the
JDK-standard path-validation algorithm the spec names explicitly.

**Distinction from TI-PKI trust**: the CETP server certificate is a **client-system**
certificate validated against an operator-managed client-system trust store via plain
PKIX — **not** the TI trust space. Therefore the existing
`TucPki18VerifierTrustManager` (gemLibPki TUC_PKI_018, used for TI certificates) is
**not** used here. This is deliberate and recorded to avoid mis-wiring.

**Alternatives considered**: SunJSSE provider (rejected for consistency with the
konnektor's BC-based gematik TLS stack); validating against the TI trust space
(rejected — client systems are not TI-PKI certified; the spec mandates a client-system
trust store).

---

## D4 — CETP framing & event serialisation

**Decision**: `CetpFrameCodec` writes the literal ASCII bytes `CETP` (0x43 0x45 0x54
0x50), then a 4-byte **big-endian** unsigned length of the following payload, then the
UTF-8 XML `Event` document (TIP1-A_4596, TAB_KON_030). `EventXmlWriter` marshals the
`de.gematik.ws.conn.eventservice.v7` JAXB `Event` (Topic, Type, Severity,
SubscriptionID, Message/Parameter[Key,Value]) to UTF-8.

**Rationale**: Verbatim from the normative spec; JAXB types already ship in
`api-telematik` and are used by `KonnektorEventService`, so no schema duplication.
Big-endian length matches the spec ("hochwertigste Byte zuerst").

**Edge**: for `BOOTUP/BOOTUP_COMPLETE`, `SubscriptionID` is an **empty** element and
delivery targets the retained `ClientEndpoint` URLs, not live subscriptions (FR-008,
TUC_KON_256 Varianten).

---

## D5 — Subscription persistence & JPA dependency structure

**Decision**: Two Panache entities in `cetp-client-lib` with a **`ClientEndpoint`
1—N `Subscription`** relationship (see data-model.md):
- `ClientEndpoint` owns the event-sink URL (`cetp://host:port`), the retained
  termination time (longest across its subscriptions — for BOOTUP_COMPLETE), and the
  **per-sink consecutive-failure counter** (Auto-Unsubscribe is "analog Unsubscribe by
  EventTo URL", i.e. per sink — TIP1-A_4611).
- `Subscription` owns `subscriptionId`, `topic`, optional XPath `filter`, caller
  context (`mandantId`, `clientSystemId`, `workplaceId`), and `terminationTime`, with a
  **unique constraint** on `(endpoint, topic, mandantId, clientSystemId, workplaceId)`
  (the FR-012 uniqueness key).

**Rationale**: Modelling the sink as its own entity gives a natural home for the
per-sink failure counter and the retained-URL lifecycle, and makes Auto-Unsubscribe a
single cascade delete of the `ClientEndpoint`. It directly answers the user's request
for "a good dependency structure for JPA entities". Panache matches `sicct-lib`'s
`CardTerminal` convention.

**Alternatives considered**: single flat `Subscription` table with a duplicated URL
column (rejected — no clean home for per-sink failure counting or retained URLs, and
BOOTUP_COMPLETE retention would need a parallel table anyway).

---

## D6 — Configuration values

**Decision**: Surface two administrator values through MicroProfile Config
(`@ConfigProperty`), consistent with `SmkCSAKAutProvider`'s `sicct.tls.client.key.password`:
- `cetp.evt-max-try` (int, **default 3**) — `EVT_MAX_TRY` consecutive-failure threshold
  for Auto-Unsubscribe (TIP1-A_4611, FR-023).
- `cetp.tls.mandatory` (boolean, **default true**) — `ANCL_TLS_MANDATORY`; `true` →
  CETP1 (TLS), `false` → CETP2 (plain TCP) (FR-024/FR-027).
- `cetp.tls.truststore.*` — path/password of the client-system trust store for PKIX
  validation (FR-026).
- `cetp.delivery.timeout-ms` (int, **default 2000**) — per-attempt timeout (SC-008,
  FR-005a).

**Rationale**: Defaults are conservative and overridable; `EVT_MAX_TRY` default of 3
balances transient-outage tolerance against unbounded retries. The spec leaves the
numeric default to the manufacturer (TIP1-A_4611 only mandates configurability).

**Alternatives considered**: hard-coding (rejected — TIP1-A_4611 requires admin
configurability); DB-backed config (deferred — MicroProfile Config matches existing
modules).

---

## Principle VII — External library evaluation (mandatory)

No new third-party dependency is introduced. All capabilities are met by
already-adopted, already-reviewed components:

| Capability | Library / component | Status | Notes |
|---|---|---|---|
| Async event seam | CDI `jakarta.enterprise.event` (Quarkus ARC) | Already adopted | Standard SPI; no new dep. |
| Outbound TCP/TLS client | Netty 4 `netty-all` | Already adopted (`sicct-lib`) | Active, maintained, on Maven Central; no unpatched CVSS ≥ 7. |
| TLS provider / ciphers | BouncyCastle JSSE | Already adopted (`quarkus-sicct-extension`) | gematik-aligned TLS stack; reused. |
| PKIX path validation | JDK `TrustManagerFactory("PKIX")` | JDK | Standard; no dep. |
| XPath filter | JDK `javax.xml.xpath` | JDK | Standard; no dep. |
| Event XML (un)marshalling | `api-telematik` JAXB + Jakarta XML Bind (CXF) | Already adopted | Generated types; no new dep. |
| Persistence | Hibernate ORM Panache | Already adopted | Matches `sicct-lib`/`konnektor-soap-server`. |

**Outcome**: no new dependency → no SBOM change required. Recorded for the Phase-1
Constitution re-check.

---

## Principle VIII — Standard interface adoption (mandatory)

| Domain | Standard interface adopted | Bespoke alternative rejected |
|---|---|---|
| Async event observation | `jakarta.enterprise.event` `@ObservesAsync` / `Event.fireAsync` | custom event bus / queue |
| TLS client key material | `javax.net.ssl.KeyManagerFactory` (via `SmkCSAKAut`) | custom key holder |
| TLS server-cert trust | `javax.net.ssl.TrustManagerFactory` (PKIX) + `X509TrustManager` | custom trust check |
| XPath evaluation | `javax.xml.xpath.XPath` | hand-rolled XML matcher |
| Persistence | JPA / `PanacheEntityBase` | custom DAO/JDBC |

`EventDeliveryFilter` is a **new** interface, but it has **three** concrete
implementations (topic, access, XPath), so it satisfies Principle I (not a
single-implementation abstraction) and there is no JDK/framework standard for "domain
event delivery filter", so Principle VIII does not mandate an existing interface.

---

## Open items deferred to implementation (non-blocking)

- Exact list of basis-services to be wired to fire `KonnektorSystemEvent` (incremental,
  per-Afo). This feature ships the event record + observer + at least one wired
  producer for the end-to-end test.
- Whether the per-sink failure counter is persisted or in-memory: planned as a column on
  `ClientEndpoint` (survives nothing meaningful across bootup since subscriptions are
  cleared) — finalised in data-model.md.
