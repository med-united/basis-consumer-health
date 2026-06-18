# Implementation Plan: CETP Client (Event Delivery to Client Systems)

**Branch**: `010-cetp-client` | **Date**: 2026-06-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/010-cetp-client/spec.md`

## Summary

Implement the konnektor PUSH mechanism of the Systeminformationsdienst
(`gemSpec_Kon_V5.27.0` §4.1.6): persist client-system subscriptions and deliver
matching konnektor events to each subscriber over the proprietary **CETP**
(Connector Event Transport Protocol).

A new maven module **`cetp-client-lib`** owns both halves. An application-scoped
singleton **`CetpClient`** observes konnektor system events asynchronously
(`@ObservesAsync` on a `KonnektorSystemEvent` CDI event), runs the TUC_KON_256
three-stage filter pipeline (topic prefix → access authorisation → XPath) over the
currently valid subscriptions, serialises each match to the EventService `Event`
XML document, frames it as `CETP` + 4-byte big-endian length, and sends it — over a
fresh TCP connection per delivery (2 s timeout), optionally mTLS-secured. The
konnektor authenticates as TLS client with **SmkCSAKAut** (C.AK.AUT) and validates
the client system's server certificate with **PKIX** against a configured
client-system trust store. After `EVT_MAX_TRY` consecutive failures to a sink the
subscriptions to that sink are auto-unsubscribed.

The **Subscription** and **ClientEndpoint** JPA entities live in `cetp-client-lib`;
`konnektor-soap-server`'s `KonnektorEventService` (today a stub) depends on
`cetp-client-lib` and drives Subscribe / Unsubscribe / RenewSubscriptions /
GetSubscription against the subscription repository. Delivery semantics are
developed and tested over plain TCP first (CETP2), then hardened with mTLS (CETP1).

## Technical Context

**Language/Version**: Java 21 LTS

**Primary Dependencies**:

| Dependency | Purpose |
|---|---|
| Quarkus 3.34.7 ARC (CDI) — `jakarta.enterprise.event` | `@ObservesAsync` event observation; `Event<KonnektorSystemEvent>.fireAsync(...)` from basis-services. **Standard interface (Principle VIII).** |
| Quarkus Hibernate ORM Panache + JDBC (H2 in test) | `Subscription` / `ClientEndpoint` Panache entities and queries (already used by `sicct-lib`, `konnektor-soap-server`). |
| Netty 4 (`netty-all`, already in `sicct-lib`) | Outbound CETP TCP/TLS client (`Bootstrap`, `SslHandler`); per-delivery channel. |
| BouncyCastle JSSE (`BouncyCastleJsseProvider`, already in `quarkus-sicct-extension`) | TLS provider for the mTLS context, reusing `GematikSSLConfig` cipher suites. |
| `quarkus-sicct-extension` runtime (`SmkCSAKAut`, `SmkCSAKAutProvider`, `GematikSSLConfig`, `KonnektorSslHandler`) | Konnektor TLS client identity (C.AK.AUT) and gematik TLS config — reused, not reinvented. |
| `api-telematik` (generated `de.gematik.ws.conn.eventservice.v7.*` + `EventService.xsd`) + Jakarta XML Bind (via CXF) | `Event` envelope JAXB types for the CETP message body. |
| JDK `javax.net.ssl` (`KeyManagerFactory`, `TrustManagerFactory.getInstance("PKIX")`, `KeyStore`) | mTLS client auth + PKIX server-cert trust. **Standard interface (Principle VIII).** |
| JDK `javax.xml.xpath` (`XPathFactory`, `XPath`) | Subscription XPath filter evaluation against the event XML. **Standard interface.** |
| JUnit 5 + `@QuarkusTest` + Mockito (already present) | Unit + integration tests; embedded TCP/TLS fake event sink. |

**Storage**: Relational DB via JPA/Panache. Two tables: `CETP_CLIENT_ENDPOINT`
(retained across bootup for `BOOTUP_COMPLETE`) and `CETP_SUBSCRIPTION` (cleared at
bootup). H2 in tests; production datasource is the konnektor's existing datasource.

**Testing**: JUnit 5 unit tests for the CETP frame codec, `Event` XML writer, and
the three filters; `@QuarkusTest` integration tests driving Subscribe→event→delivery
against an embedded TCP listener (CETP2) and a TLS listener with a test trust store
(CETP1); deterministic Auto-Unsubscribe tests with an injectable failure threshold
and a controllable clock for `TerminationTime`. Afo-named tests
(`test_TIP1_A_4596_…`, `test_TIP1_A_4611_…`).

**Target Platform**: Linux server, JVM mode, Kubernetes pod (consistent with
features 001/002).

**Project Type**: Extension to the existing multi-module Maven reactor — new library
module `cetp-client-lib`; one new dependency edge `konnektor-soap-server →
cetp-client-lib`.

**Performance Goals**: A single slow/unreachable sink must not delay delivery to
other sinks by more than the **2 s** per-attempt timeout (SC-008); fan-out is
concurrent (non-blocking per sink). Sustain **≥ 999** concurrent subscriptions
(FR-018, TIP1-A_4612).

**Constraints**: CETP runs exclusively over TCP, optionally TLS (TIP1-A_5536);
no application-level acknowledgement is expected (FR-006); delivery is fire-and-forget;
secret key material (SmkCSAKAut) never logged (Principle V); subscription list empty
at bootup (FR-017, TIP1-A_4613). 150 MB memory ceiling (Principle IV) — event XML
buffered per delivery, no unbounded queues.

**Scale/Scope**: One event fans out to all matching subscriptions; typically tens of
client systems, ≥ 999 subscriptions supported. No throughput SLA beyond non-blocking
fan-out.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Gate | Status | Notes |
|---|---|---|
| Principle I — No premature abstractions / no single-impl interface; no `Impl` suffix | ✅ PASS | `EventDeliveryFilter` interface is justified by **three** real implementations (`TopicPrefixFilter`, `AccessAuthorizationFilter`, `XPathExpressionFilter`). All other types are concrete single-purpose classes (`CetpClient`, `CetpEventSender`, `CetpFrameCodec`, `EventXmlWriter`, `CetpTlsContextFactory`). No `Impl` suffix. |
| Principle II — Test-first, ≥ 80% coverage, deterministic | ✅ PASS | Tests authored before implementation; fan-out/timeout/Auto-Unsubscribe made deterministic via injected clock + injected `EVT_MAX_TRY` + embedded in-process sink (infrastructure seam only). |
| Principle V — Security & gematik TI compliance + Afo traceability | ✅ PASS | Normative Afos implemented per-commit with named tests (TIP1-A_4594/4595/4596/4598-02/4608/4609/5112/4611/4612/4613/5536/5009, A_21760-02). mTLS with SmkCSAKAut; PKIX server-cert validation; no secret material logged; subscription PII handled per access-authorisation filter. |
| Principle VI — UML diagrams | ✅ PASS | use-case, deployment, component, sequence-event-delivery, sequence-subscribe, state-subscription, class-subscription + `diagrams/README.md` produced in Phase 1. |
| Principle VII — External library evaluation | ✅ PASS | **No new third-party dependency.** Reuses already-adopted Netty, BouncyCastle JSSE, Hibernate ORM Panache, JDK JSSE/XPath/JAXB. Evaluation recorded in research.md. |
| Principle VIII — Standard interface adoption | ✅ PASS | CDI `@ObservesAsync` (jakarta.enterprise.event) as the event seam instead of a bespoke bus; JSSE `KeyManagerFactory`/`TrustManagerFactory("PKIX")`; JPA/Panache; `javax.xml.xpath.XPath`. Recorded in research.md. |
| Quality Gate — SBOM / new dependency | ✅ PASS | No new dependency → no SBOM addition. |
| Principle IV — Performance budgets | ✅ PASS | 2 s per-attempt timeout bounds fan-out latency (SC-008); no unbounded buffering. |

**Result: PASS — no violations. Complexity Tracking not required.**

## Project Structure

### Documentation (this feature)

```text
specs/010-cetp-client/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions (event seam, TLS/PKIX, framing, dependency direction)
├── data-model.md        # Phase 1 — Subscription / ClientEndpoint entities + lifecycle
├── quickstart.md        # Phase 1 — validation guide (subscribe → fire event → assert CETP frame; mTLS)
├── contracts/
│   ├── cetp-wire-protocol.md       # CETP frame format + Event XML mapping (TAB_KON_030)
│   ├── cetp-client-observer.md     # KonnektorSystemEvent + @ObservesAsync + TUC_KON_256 filter pipeline
│   ├── subscription-repository.md  # API cetp-client-lib exposes to KonnektorEventService
│   └── tls-mtls.md                 # mTLS contract: SmkCSAKAut client identity + PKIX trust + CETP1/CETP2
├── diagrams/
│   ├── README.md
│   ├── use-case.puml
│   ├── deployment.puml
│   ├── component.puml
│   ├── sequence-event-delivery.puml
│   ├── sequence-subscribe.puml
│   ├── state-subscription.puml
│   └── class-subscription.puml
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
cetp-client-lib/                         # NEW reactor module (add to root pom <modules>)
├── pom.xml                              # parent basis-consumer-parent; deps: api-telematik,
│                                        #   quarkus-sicct-extension, quarkus-arc,
│                                        #   quarkus-hibernate-orm-panache, netty-all, BC JSSE
└── src/
    ├── main/java/de/servicehealtherx/cetp/
    │   ├── CetpClient.java                       # @ApplicationScoped singleton; @ObservesAsync(KonnektorSystemEvent)
    │   ├── KonnektorSystemEvent.java             # record(topic, EventType, EventSeverity, Map<String,String> params)
    │   ├── delivery/
    │   │   ├── CetpEventSender.java              # opens per-delivery TCP/TLS channel, writes frame, 2 s timeout
    │   │   ├── CetpFrameCodec.java               # "CETP" magic + 4-byte big-endian length framing
    │   │   └── EventXmlWriter.java               # builds Event XML (EventService.xsd) via JAXB
    │   ├── filter/
    │   │   ├── EventDeliveryFilter.java          # interface (3 impls)
    │   │   ├── TopicPrefixFilter.java            # case-insensitive topic prefix match
    │   │   ├── AccessAuthorizationFilter.java    # TUC_KON_000 access check per subscription context
    │   │   └── XPathExpressionFilter.java        # javax.xml.xpath over the event XML
    │   ├── subscription/
    │   │   ├── Subscription.java                 # @Entity PanacheEntityBase (CETP_SUBSCRIPTION)
    │   │   ├── ClientEndpoint.java               # @Entity PanacheEntityBase (CETP_CLIENT_ENDPOINT)
    │   │   └── SubscriptionService.java          # @ApplicationScoped: subscribe/unsubscribe/renew/get/autoUnsubscribe
    │   └── tls/
    │       └── CetpTlsContextFactory.java        # Netty SslContext: SmkCSAKAut KMF + PKIX TrustManager
    └── test/java/de/servicehealtherx/cetp/...    # unit + @QuarkusTest IT + embedded fake sink

konnektor-soap-server/
└── src/main/java/de/servicehealtherx/konnektor/soap/
    └── KonnektorEventService.java        # MODIFIED: delegate Subscribe/Unsubscribe/Renew/Get to SubscriptionService
```

**Structure Decision**: New library module `cetp-client-lib` holds the Subscription
JPA entities, the subscription service, and the CETP delivery + TLS machinery
(per the clarification — FR-029). The only new reactor edge is
`konnektor-soap-server → cetp-client-lib`; `cetp-client-lib` depends downward on
`api-telematik` (Event JAXB types) and `quarkus-sicct-extension` (SmkCSAKAut +
gematik TLS config), both of which already sit below `konnektor-soap-server`, so the
graph stays acyclic.

## Implementation Refinements (post-design, applied)

Two refinements were made during implementation and are reflected in the code, the component
diagram, and `cetp-client-lib/README.md`:

1. **JDK sockets instead of Netty for delivery.** The CETP client is a unidirectional,
   fire-and-forget, one-connection-per-delivery sender, so `CetpEventSender` uses
   `java.net.Socket` / `javax.net.ssl.SSLSocket` rather than a Netty `Bootstrap`. This gives
   deterministic connect/read timeouts and the standard JSSE TLS stack with far less machinery
   (Principle I — simplicity; Principle VIII — standard interfaces). No Netty dependency is added.

2. **`cetp-client-lib` decoupled from `quarkus-sicct-extension`.** That extension's `@Startup`
   `SicctTerminalManager` runs at boot (and reaches for card terminals), which would break
   `@QuarkusTest` boots in this library. Instead the konnektor TLS identity is consumed as a
   standard JSSE `KeyManagerFactory` qualified `@KonnektorClientIdentity`; `konnektor-soap-server`
   produces it from `SmkCSAKAut` (`CetpClientIdentityProducer`). The library therefore depends only
   on `api-telematik` (Event JAXB types) for its non-Quarkus internal dependencies. SmkCSAKAut is
   still the client identity at runtime (A_21760-02), just injected via the standard interface.

Both refinements keep the Constitution Check result unchanged (no new dependency; standard
interfaces preferred).

## Complexity Tracking

> No Constitution Check violations — section intentionally empty.
