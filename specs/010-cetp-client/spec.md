# Feature Specification: CETP Client (Event Delivery to Client Systems)

**Feature Branch**: `010-cetp-client`

**Created**: 2026-06-17

**Status**: Draft

**Input**: User description: "Please read gemSpec_Kon_V5.27.0.pdf. I want to implement the CETP client in a konnektor. Create a new maven module called cetp-client-lib. It should have a singleton class CetpClient that uses @ObservesAsync to observe required CETP events. When it receives an event it iterates over the subscriptions and sends the events to the listed hosts in the subscriptions. If it fails the subscription should be removed based on the details written in gemSpec_Kon_V5.27.0.pdf. The subscriptions are created and persisted in the KonnektorEventService. Please come up with a good dependency structure for JPA entities for subscriptions. The CetpClient should support mTLS. It should use the SmkCSAKAut for authenticate it self and should expect a server TLS certificate that is trusted by using the PKIX algorithm in a client system trust store."

## Overview

The konnektor's system-information service (Systeminformationsdienst) must push
operational, security and infrastructure events to the client systems that have
registered interest in them. Registration happens through the EventService
operation *Subscribe*; delivery happens over the konnektor-proprietary **CETP**
(Connector Event Transport Protocol). This feature delivers both halves: the
persistence and lifecycle management of client subscriptions, and the outbound
CETP client that fans events out to every matching subscriber's event sink
(Ereignissenke).

This realises the PUSH mechanism of `gemSpec_Kon_V5.27.0` §4.1.6, in particular
TUC_KON_256 "Systemereignis absetzen" and the EventService operations Subscribe,
Unsubscribe, RenewSubscriptions and GetSubscription.

## Clarifications

### Session 2026-06-17

- Q: What attribute set defines two subscriptions as "the same" for FR-012 dedup (the JPA uniqueness key)? → A: EventTo + Topic + caller context (mandantId + clientSystemId + workplaceId)
- Q: Where do the Subscription JPA entities live and which way does the module dependency point? → A: In `cetp-client-lib`; `konnektor-soap-server` depends on `cetp-client-lib`
- Q: How does the CETP client manage TCP/TLS connections to a client system's event sink? → A: Open a new CETP connection per delivery attempt and close it after sending
- Q: What per-attempt connect+send timeout bounds a single delivery (counting as one failed attempt on expiry, per SC-008)? → A: 2 seconds

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Subscribed client system receives operational events (Priority: P1)

A client system (e.g. a primary system / PVS) has registered an event sink and
subscribed to one or more topics. When the konnektor raises a matching internal
event (for example a card insertion `CARD/INSERTED` or a TLS-certificate change
`MGM/TLS_CERT`), the konnektor opens a CETP connection to the client system's
sink and delivers the event message. The client system needs no application-level
reply.

**Why this priority**: Event delivery is the core value of the feature and of the
PUSH mechanism. Without it, subscriptions have no effect and client systems cannot
react to konnektor state changes. It is the minimum viable slice — given a
pre-seeded subscription, the konnektor can deliver events end-to-end.

**Independent Test**: Seed one subscription pointing at a local TCP listener, fire
an internal event whose topic matches the subscription's topic, and assert that the
listener receives a well-formed CETP frame (magic bytes + length prefix + UTF-8 XML
`Event` document) carrying the correct topic, type, severity, subscription ID and
parameters.

**Acceptance Scenarios**:

1. **Given** an active subscription to topic `CARD` with an event sink at
   `cetp://127.0.0.1:9200`, **When** an event with topic `CARD/INSERTED` is raised,
   **Then** a CETP message is delivered to `127.0.0.1:9200` containing the topic,
   type, severity, the subscription's ID and the event parameters.
2. **Given** an active subscription to topic `CARD/INSERTED`, **When** an event with
   topic `CT/CONNECTED` is raised, **Then** no message is delivered to that
   subscription (topic does not match).
3. **Given** two active subscriptions to topic `CARD` for two different event sinks,
   **When** a `CARD/REMOVED` event is raised, **Then** both sinks each receive one
   CETP message.
4. **Given** a subscription whose `TerminationTime` is already in the past,
   **When** a matching event is raised, **Then** no message is delivered to it.
5. **Given** a subscription carrying an XPath `Filter`, **When** a matching-topic
   event is raised whose XML representation does not satisfy the filter, **Then** no
   message is delivered to that subscription.

---

### User Story 2 - Client system manages its subscriptions (Priority: P2)

A client system registers, renews, queries and cancels its interest in topics
through the EventService. Each registration is persisted so that the delivery side
(User Story 1) has an authoritative, durable list of who wants which events and
where to send them.

**Why this priority**: Delivery is only meaningful once real subscriptions can be
created and managed. This story makes the subscription list real (replacing the
current stubbed EventService) and gives it the lifecycle the specification mandates.

**Independent Test**: Call Subscribe with a topic and event sink, confirm a
subscription ID is returned and a row is persisted with a `TerminationTime` of
system time + 25 h; call GetSubscription and see it listed; call RenewSubscriptions
and observe an extended `TerminationTime`; call Unsubscribe and observe the row
removed.

**Acceptance Scenarios**:

1. **Given** a valid Subscribe request with topic `CARD` and event sink
   `cetp://host:9200`, **When** the operation completes, **Then** a unique
   subscription ID is returned and a subscription is persisted with `TerminationTime`
   = system time + 25 h and the mandant / client-system / workplace context.
2. **Given** an identical Subscribe request for a subscription that already exists,
   **When** the operation completes, **Then** the existing subscription ID is
   returned (no duplicate is created).
3. **Given** an existing subscription, **When** Unsubscribe is called with its event
   sink URL (and optionally its subscription ID), **Then** the subscription is
   removed and no longer appears in GetSubscription.
4. **Given** existing subscriptions, **When** RenewSubscriptions is called, **Then**
   each still-valid subscription receives a new `TerminationTime` and a renewal entry
   is returned; subscriptions already past their `TerminationTime` are not renewed.
5. **Given** a Subscribe request that fails the access-authorisation check for the
   calling mandant/client system, **When** the operation runs, **Then** it is
   rejected and no subscription is persisted.
6. **Given** the konnektor is (re)started, **When** it boots, **Then** the
   subscription list starts empty.

---

### User Story 3 - Unreachable client systems are auto-unsubscribed (Priority: P3)

When a client system's event sink can no longer be reached, the konnektor must not
keep trying forever. After a configurable number of consecutive failed connection
or delivery attempts it removes the subscription automatically (Auto-Unsubscribe),
keeping the subscription list clean and bounded.

**Why this priority**: Required for correct, self-healing operation and to honour the
`EVT_MAX_TRY` requirement, but the system delivers value (P1/P2) before this is in
place. It protects resource usage and matches the specified lifecycle.

**Independent Test**: Point a subscription at an unreachable host, configure the
failure threshold to a small number, fire matching events repeatedly, and assert the
subscription is removed exactly once the configured number of consecutive failures is
reached — and that a single successful delivery resets the counter.

**Acceptance Scenarios**:

1. **Given** the failure threshold is set to N and a subscription whose sink is
   unreachable, **When** N consecutive delivery/connection attempts to that sink
   fail, **Then** the subscription is removed (Auto-Unsubscribe) and receives no
   further events.
2. **Given** a subscription that has accumulated N−1 consecutive failures, **When**
   the next delivery succeeds, **Then** the failure counter resets to zero and the
   subscription is retained.
3. **Given** an event sink that never acknowledges deliveries at the application
   level, **When** failures are counted, **Then** only connection-establishment
   failures are counted toward the threshold.

---

### User Story 4 - Events are delivered over a secured, mutually authenticated channel (Priority: P4)

When secure transport is mandated, the konnektor delivers events over TLS as the TLS
client. It authenticates itself with its konnektor authentication identity
(SmkCSAKAut / the C.AK.AUT key-and-certificate) when the client system requests
client authentication, and it validates the client system's server certificate using
the PKIX path-validation algorithm against the configured client-system trust store.
When secure transport is not mandated, events are delivered over plain TCP.

**Why this priority**: Security of the channel is essential for production but the
delivery and lifecycle mechanics (P1–P3) can be developed and tested over plain TCP
first. This story hardens the transport without changing the delivery semantics.

**Independent Test**: Stand up a TLS event sink that requests client authentication,
configure the konnektor authentication identity and a trust store containing the
sink's CA, deliver an event, and assert the handshake completes with mutual
authentication and the event arrives. Then point at a sink whose certificate does not
chain to the trust store and assert delivery is refused.

**Acceptance Scenarios**:

1. **Given** secure transport is mandated and a TLS event sink that requests client
   authentication, **When** an event is delivered, **Then** the konnektor presents its
   SmkCSAKAut client identity, the handshake completes mutually authenticated, and the
   event arrives.
2. **Given** secure transport is mandated and a TLS event sink whose server
   certificate does not build a valid PKIX path to the client-system trust store,
   **When** delivery is attempted, **Then** the handshake is rejected and the event is
   not delivered (and the attempt counts as a failure per User Story 3).
3. **Given** secure transport is not mandated, **When** an event is delivered, **Then**
   it is sent over a plain TCP connection without TLS.

---

### Edge Cases

- **Concurrent delivery**: many subscriptions match a single event — deliveries fan
  out without one slow/unreachable sink blocking delivery to the others.
- **Event raised during shutdown/bootup**: subscriptions are empty immediately after
  bootup, so events raised before any Subscribe are delivered to no one.
- **`BOOTUP/BOOTUP_COMPLETE` event**: must be delivered to the persisted client
  endpoint URLs (with an empty subscription ID) even though the subscription list
  itself was cleared during bootup; endpoint URLs are retained until their
  `TerminationTime` expires.
- **Malformed XPath filter on a subscription**: filter evaluation fails for that
  subscription only; other subscriptions are unaffected.
- **Event sink reachable but slow**: a delivery that does not complete within the
  2-second per-attempt timeout is treated as a failed attempt for Auto-Unsubscribe
  counting.
- **Subscription expiry mid-flight**: a subscription whose `TerminationTime` passes is
  excluded from delivery selection.
- **Topic comparison casing**: topic prefix matching is case-insensitive.

## Requirements *(mandatory)*

### Functional Requirements

#### Event delivery (PUSH / TUC_KON_256)

- **FR-001**: The system MUST observe the konnektor-internal events that are eligible
  for client delivery and, for each, run the delivery flow asynchronously without
  blocking the event producer. *(TIP1-A_4598-02)*
- **FR-002**: For each event, the system MUST select the set of currently valid
  subscriptions (`TerminationTime` > current system time) and deliver only to those
  that pass, in order, three filters: (a) topic prefix match (case-insensitive),
  (b) access authorisation for the subscription's mandant/client-system/workplace
  context, (c) the subscription's optional XPath filter evaluated against the event
  XML. *(TIP1-A_4598-02)*
- **FR-003**: The system MUST build the event as a UTF-8 XML `Event` document
  conforming to the EventService schema, containing Topic, Type
  (Operation/Security/Infrastructure), Severity (Info/Warning/Error/Fatal), the
  matching subscription's ID, and the event Message parameters as key/value pairs.
  *(TIP1-A_4596)*
- **FR-004**: The system MUST frame each CETP message with the literal ASCII marker
  `CETP` followed by a 4-byte big-endian length field giving the byte length of the
  following XML event document. *(TIP1-A_4596)*
- **FR-005**: The system MUST establish, as the connecting party, a CETP connection to
  the event sink URL (`cetp://host:port`) that the client system supplied in its
  Subscribe request, and send the framed event message to it. *(TIP1-A_4594)*
- **FR-005a**: The system MUST open a fresh CETP connection for each delivery attempt
  and close it after the event has been sent (no connection pooling or reuse across
  events). Each delivery attempt MUST be bounded by a 2-second combined
  connection-establishment + send timeout; expiry of that timeout counts as one failed
  attempt (FR-019/FR-020).
- **FR-006**: The system MUST NOT expect or wait for an application-level response from
  the client system for a delivered event.
- **FR-007**: The system MUST carry CETP exclusively over TCP (optionally TLS-secured).
  *(TIP1-A_5536)*
- **FR-008**: For the `BOOTUP/BOOTUP_COMPLETE` event the system MUST deliver to the
  retained list of client endpoint URLs (rather than the live subscription list) with
  an empty subscription ID, and MUST notify endpoints whose `TerminationTime` had not
  yet expired at boot time.

#### Subscription lifecycle & persistence

- **FR-009**: The system MUST persist subscriptions created via Subscribe, storing for
  each: a generated subscription ID, `TerminationTime`, mandant ID, client-system ID,
  workplace ID, event sink URL (EventTo), subscribed topic, and optional XPath filter.
  *(TIP1-A_4608)*
- **FR-010**: The system MUST set a new subscription's `TerminationTime` to current
  system time + 25 hours. *(TIP1-A_4608)*
- **FR-011**: The system MUST validate Subscribe arguments and perform an access
  authorisation check before persisting; on failure the operation is rejected and
  nothing is persisted. *(TIP1-A_4608)*
- **FR-012**: The system MUST return the existing subscription ID when a Subscribe
  request matches an already-existing subscription, rather than creating a duplicate.
  Two subscriptions are "the same" when they share the same event sink URL (EventTo),
  the same subscribed topic, AND the same caller context (mandant ID + client-system ID
  + workplace ID); this triple is the subscription uniqueness key. A differing XPath
  filter does not by itself create a new subscription (the latest Subscribe updates the
  matched row's filter). *(TIP1-A_4608)*
- **FR-013**: The system MUST remove a subscription on Unsubscribe, identified by its
  event sink URL and/or subscription ID. *(TIP1-A_4609)*
- **FR-014**: The system MUST, on RenewSubscriptions, assign a new `TerminationTime` to
  each still-valid subscription and return the renewals; subscriptions already past
  their `TerminationTime` MUST NOT be renewed. *(TIP1-A_5112)*
- **FR-015**: The system MUST, on GetSubscription, return the currently persisted
  subscriptions (sink URL, topic, optional filter, subscription ID).
- **FR-016**: The system MUST treat a subscription as valid until it is explicitly
  unsubscribed, auto-unsubscribed, the konnektor restarts, or its `TerminationTime`
  passes. *(TIP1-A_4608)*
- **FR-017**: The system MUST start with an empty subscription list at bootup, while
  retaining persisted client endpoint URLs needed for the `BOOTUP_COMPLETE`
  notification until their `TerminationTime` expires. *(TIP1-A_4613)*
- **FR-018**: The system MUST support at least 999 concurrent subscriptions in total.
  *(TIP1-A_4612)*

#### Failure handling / Auto-Unsubscribe

- **FR-019**: The system MUST count consecutive failed connection-establishment and/or
  delivery attempts per subscription (per event sink). *(TIP1-A_4611)*
- **FR-020**: The system MUST automatically remove a subscription (Auto-Unsubscribe,
  equivalent to Unsubscribe by event sink URL) once the configured maximum number of
  consecutive failed attempts (`EVT_MAX_TRY`) is reached. *(TIP1-A_4611)*
- **FR-021**: The system MUST reset a subscription's consecutive-failure counter to
  zero upon a successful delivery.
- **FR-022**: When the event sink never answers deliveries at the application level,
  the system MUST count only connection-establishment failures toward the threshold.
  *(TIP1-A_4608)*
- **FR-023**: The maximum-failed-attempts threshold MUST be administrator-configurable.
  *(TIP1-A_4611)*

#### Secure transport (mTLS)

- **FR-024**: When secure transport is mandated, the system MUST deliver events over a
  TLS connection that it initiates as the TLS client. *(TIP1-A_4595)*
- **FR-025**: The system MUST authenticate itself in the TLS handshake using the
  konnektor authentication identity (SmkCSAKAut — the C.AK.AUT key and certificate)
  when the client system requests client authentication. *(A_21760-02, TIP1-A_4595)*
- **FR-026**: The system MUST validate the client system's server certificate using the
  PKIX certification-path-validation algorithm against a configured client-system trust
  store, and MUST refuse delivery when no valid path is found. *(TIP1-A_5009)*
- **FR-027**: When secure transport is not mandated, the system MUST deliver events over
  a plain (non-TLS) TCP connection. *(CETP2, TAB_KON_852)*
- **FR-028**: A TLS handshake failure (including trust-validation failure) MUST be
  treated as a failed delivery attempt for Auto-Unsubscribe counting (FR-019/FR-020).

#### Packaging / structure

- **FR-029**: The CETP client functionality MUST be delivered as a new maven module
  `cetp-client-lib`, registered in the reactor build. The Subscription JPA entities and
  their repository MUST live in `cetp-client-lib`; `konnektor-soap-server` (which hosts
  KonnektorEventService) MUST depend on `cetp-client-lib`, so the dependency points from
  the SOAP server to the lower-level CETP library (never the reverse).
- **FR-030**: The CETP client MUST be exposed as a single application-scoped component
  that observes events asynchronously and is the single owner of outbound CETP
  delivery.

### Key Entities *(include if feature involves data)*

- **Subscription**: A client system's persisted registration of interest. Attributes:
  subscription ID (unique), `TerminationTime`, subscribed topic, optional XPath filter,
  consecutive-failure counter. Belongs to one event sink and one caller context. Its
  business uniqueness key is (event sink URL + topic + mandant ID + client-system ID +
  workplace ID) — see FR-012. Persisted in `cetp-client-lib`.
- **Event Sink (Ereignissenke / Endpoint)**: The client-side CETP delivery target,
  identified by URL (`cetp://host:port`). Endpoint URLs persist (for `BOOTUP_COMPLETE`)
  independently of the live subscriptions that reference them. Multiple subscriptions
  may share one sink.
- **Caller Context**: The mandant ID, client-system ID and workplace ID under which a
  subscription was created; used by the access-authorisation delivery filter.
- **Event Message**: The transport-neutral representation of a konnektor event — topic,
  type, severity and key/value parameters — serialised to the XML `Event` document for
  delivery.
- **Konnektor Authentication Identity (SmkCSAKAut)**: The konnektor's C.AK.AUT key and
  certificate material used to authenticate the konnektor as TLS client on the CETP
  interface.
- **Client-System Trust Store**: The set of trust anchors against which the client
  system's TLS server certificate is validated via PKIX.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A client system subscribed to a topic receives 100% of the matching
  events raised while its subscription is valid and its sink is reachable.
- **SC-002**: A client system that is not subscribed to a topic, or whose filter does
  not match, receives 0 events for that topic.
- **SC-003**: The system sustains at least 999 concurrent valid subscriptions and
  delivers a single event to all matching subscribers.
- **SC-004**: An event sink that is unreachable is automatically unsubscribed after
  exactly the configured number of consecutive failed attempts, and not before.
- **SC-005**: A single successful delivery to a previously-failing sink resets its
  failure count, so a transient outage shorter than the threshold never causes an
  unsubscribe.
- **SC-006**: When secure transport is mandated, 100% of deliveries to a trusted,
  client-authenticating sink complete with mutual authentication, and 100% of
  deliveries to a sink whose certificate is not trusted are refused.
- **SC-007**: Newly created subscriptions are durable: after a process restart the
  subscription list is empty, while retained endpoint URLs still receive the
  `BOOTUP_COMPLETE` notification until their `TerminationTime` expires.
- **SC-008**: One slow or unreachable subscriber does not delay delivery to other
  subscribers for the same event by more than the 2-second per-attempt delivery timeout.

## Assumptions

- The user-named **SmkCSAKAut** refers to the existing konnektor TLS client identity
  abstraction (`de.servicehealtherx.quarkus.sicct.runtime.tls.SmkCSAKAut`), which wraps
  the C.AK.AUT key material; the same certificate serves the konnektor's server identity
  on SOAP and its client identity on CETP per A_21760-02.
- Subscriptions are persisted via JPA (Hibernate ORM / Panache), consistent with the
  existing `sicct-lib` entity conventions (`@Entity` Panache classes). The Subscription
  entities and their repository live in `cetp-client-lib` (see FR-029); **KonnektorEventService**
  (currently stubbed, in `konnektor-soap-server`, which now depends on `cetp-client-lib`)
  drives Subscribe/Unsubscribe/RenewSubscriptions/GetSubscription against that repository.
- The set of "required CETP events" observed asynchronously corresponds to the konnektor
  system events defined across `gemSpec_Kon` (CARD/*, CT/*, CERT/*, MGM/*, BOOTUP/*,
  OPERATIONAL_STATE/* etc.); the exact internal event type(s) observed via `@ObservesAsync`
  are an implementation detail of the planning phase.
- `EVT_MAX_TRY` and the "secure transport mandated" switch (`ANCL_TLS_MANDATORY`,
  config variants CETP1/CETP2) are administrator configuration values surfaced through
  the project's existing configuration mechanism.
- The XPath filter namespace context is the EventService v7.2 namespace
  (`http://ws.gematik.de/conn/EventService/v7.2`) as the default namespace and the `EVT`
  prefix, per TIP1-A_4608.
- Per-target / total subscription caps beyond the mandatory minimum of 999 are a product
  decision and not constrained by this feature.
- TLS cipher suites and JSSE provider follow the konnektor's existing gematik TLS
  configuration (`GematikSSLConfig`, BouncyCastle JSSE) already used for the
  konnektor↔terminal channel.

## Dependencies

- Existing `KonnektorEventService` (konnektor-soap-server) for the EventService SOAP
  operations that create and query subscriptions.
- Existing TLS building blocks in `quarkus-sicct-extension` (`SmkCSAKAut`,
  `KonnektorSslHandler`, `GematikSSLConfig`) for the konnektor's TLS client identity and
  PKIX trust handling.
- Existing JPA/Panache persistence infrastructure used by `sicct-lib`.
- Access-authorisation check (TUC_KON_000 equivalent) used by the delivery filter.
