---
description: "Task list for CETP Client (Event Delivery to Client Systems)"
---

# Tasks: CETP Client (Event Delivery to Client Systems)

**Input**: Design documents from `/specs/010-cetp-client/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: INCLUDED. The project constitution mandates Test-First (Principle II) and a
named test per gematik Afo (Principle V), so every behavioural task is preceded by its
test. Test names embed the Afo ID for machine-readable traceability.

**Organization**: Tasks are grouped by user story (US1–US4 from spec.md) so each story
is an independently testable increment.

**Module root**: `cetp-client-lib/` — Java package `de.servicehealtherx.cetp`.
Main: `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/`.
Test: `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US4; Setup/Foundational/Polish carry no story label

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the new reactor module and wire it into the build.

- [X] T001 Create the `cetp-client-lib/pom.xml` (parent `basis-consumer-parent`; dependencies: `api-telematik`, `quarkus-sicct-extension`, `quarkus-arc`, `quarkus-hibernate-orm-panache`, `netty-all`, BouncyCastle JSSE; test: `quarkus-junit5`, `quarkus-jdbc-h2`, `mockito-core`) and the `src/main/java/de/servicehealtherx/cetp/` + `src/test/java/...` directory skeleton
- [X] T002 Register `cetp-client-lib` in the root `pom.xml` `<modules>` (after `sicct-lib`) and add the `de.servicehealtherx:cetp-client-lib` dependency to `konnektor-soap-server/pom.xml`
- [X] T003 [P] Add `cetp-client-lib/src/test/resources/application.properties` (H2 datasource, `quarkus.hibernate-orm.database.generation=drop-and-create`) and the MicroProfile config defaults `cetp.evt-max-try=3`, `cetp.tls.mandatory=true`, `cetp.delivery.timeout-ms=2000`, `cetp.tls.truststore.path/password` (documented in research.md D6)

**Checkpoint**: `./mvnw -q -pl cetp-client-lib -am compile` succeeds with an empty module.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain types, JPA entities, and shared test infra that ALL user stories
depend on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 [P] Create `EventType` (Operation/Security/Infrastructure) and `EventSeverity` (Info/Warning/Error/Fatal) enums in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/EventType.java` and `EventSeverity.java` (mirror `EventService.xsd` enums)
- [X] T005 [P] Create the `KonnektorSystemEvent` record (`topic`, `EventType`, `EventSeverity`, `Map<String,String> parameters`) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/KonnektorSystemEvent.java`
- [X] T006 Create the `ClientEndpoint` Panache entity in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/subscription/ClientEndpoint.java` (table `CETP_CLIENT_ENDPOINT`: `id` UUID, `eventTo` unique, `host`, `port`, `retainedUntil`, `failureCount`; `@OneToMany(mappedBy="endpoint", cascade=ALL, orphanRemoval=true)`; static `findByEventTo`) per data-model.md
- [X] T007 Create the `Subscription` Panache entity in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/subscription/Subscription.java` (table `CETP_SUBSCRIPTION`: `id`, `subscriptionId` unique, `@ManyToOne endpoint`, `topic`, `filter`, `mandantId`, `clientSystemId`, `workplaceId`, `terminationTime`; `@Table` unique constraint on `(endpoint_id, topic, mandantId, clientSystemId, workplaceId)`; static `findValid(Instant now)`) per data-model.md (depends on T006)
- [X] T008 [P] Create the `FakeEventSink` test helper (in-process TCP listener that records received CETP frames; TLS variant accepting a configurable server cert and requesting client auth) in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/testsupport/FakeEventSink.java`
- [X] T009 [P] Add a deterministic `Clock` CDI producer (so `terminationTime`/expiry is controllable in tests) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/TimeSource.java` (default `Clock.systemUTC()`, overridable in tests)

**Checkpoint**: Entities persist to H2; `Subscription.findValid` returns only
non-expired rows; `FakeEventSink` can accept a TCP connection.

---

## Phase 3: User Story 1 — Subscribed client receives events (Priority: P1) 🎯 MVP

**Goal**: An internal `KonnektorSystemEvent` is observed asynchronously, filtered
against seeded subscriptions (topic → access → XPath), serialised to the `Event` XML,
framed, and delivered over plain TCP (CETP2) to each matching sink.

**Independent Test**: Seed one `Subscription` to `cetp://127.0.0.1:PORT` topic `CARD`,
fire `KonnektorSystemEvent("CARD/INSERTED", …)`, assert `FakeEventSink` receives one
well-formed CETP frame; a `CT/CONNECTED` event and an expired subscription receive none.

### Tests for User Story 1 (write first, must FAIL)

- [X] T010 [P] [US1] Frame codec test `test_TIP1_A_4596_frame_starts_with_CETP_and_big_endian_length()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/delivery/CetpFrameCodecTest.java`
- [X] T011 [P] [US1] Event XML test `test_TIP1_A_4596_event_body_utf8_topic_type_severity_subscriptionId()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/delivery/EventXmlWriterTest.java`
- [X] T012 [P] [US1] Topic filter test `test_TIP1_A_4598_02_topic_prefix_match_case_insensitive()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/filter/TopicPrefixFilterTest.java`
- [X] T013 [P] [US1] XPath filter test `test_TIP1_A_4598_02_xpath_filter_excludes_non_matching()` (incl. malformed-filter isolation, error 4095) in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/filter/XPathExpressionFilterTest.java`
- [X] T014 [P] [US1] Access filter test `test_TIP1_A_4598_02_access_authorization_filter()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/filter/AccessAuthorizationFilterTest.java`
- [X] T015 [P] [US1] `@QuarkusTest` delivery test `CetpClientDeliveryIT` (`test_FR_001_observer_runs_off_producer_thread`, `test_FR_002_expired_subscription_excluded`, `test_SC_001_matching_event_delivered`, `test_SC_002_non_matching_topic_not_delivered`, `test_SC_008_one_unreachable_sink_does_not_block_others`) in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/CetpClientDeliveryIT.java`

### Implementation for User Story 1

- [X] T016 [P] [US1] Implement `CetpFrameCodec` (`CETP` magic + 4-byte big-endian length) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/delivery/CetpFrameCodec.java`
- [X] T017 [P] [US1] Implement `EventXmlWriter` (marshal `de.gematik.ws.conn.eventservice.v7` `Event` to UTF-8; empty `SubscriptionID` for BOOTUP_COMPLETE) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/delivery/EventXmlWriter.java`
- [X] T018 [US1] Define `EventDeliveryFilter` interface in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/filter/EventDeliveryFilter.java`
- [X] T019 [P] [US1] Implement `TopicPrefixFilter` (case-insensitive prefix/equals) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/filter/TopicPrefixFilter.java` (depends on T018)
- [X] T020 [P] [US1] Implement `XPathExpressionFilter` (`javax.xml.xpath` over event XML, EventService v7.2 namespace; error-isolated) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/filter/XPathExpressionFilter.java` (depends on T018)
- [X] T021 [P] [US1] Implement `AccessAuthorizationFilter` (TUC_KON_000 access check per subscription context; seam injectable for tests) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/filter/AccessAuthorizationFilter.java` (depends on T018)
- [X] T022 [US1] Implement `CetpEventSender` (Netty `Bootstrap`, one connection per delivery, plain TCP, 2 s connect+write timeout, returns success/failure `CompletionStage`) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/delivery/CetpEventSender.java` (depends on T016)
- [X] T023 [US1] Implement `CetpClient` (`@ApplicationScoped`; `@ObservesAsync KonnektorSystemEvent`; select `Subscription.findValid`, apply the 3 filters in order, build XML, fan out via `CetpEventSender`) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/CetpClient.java` (depends on T017–T022)

**Checkpoint**: US1 fully functional over plain TCP — MVP deliverable.

---

## Phase 4: User Story 2 — Client manages its subscriptions (Priority: P2)

**Goal**: Subscribe/Unsubscribe/RenewSubscriptions/GetSubscription persist via
`SubscriptionService`, and `KonnektorEventService` delegates to it. Bootup starts empty.

**Independent Test**: Call `subscribe` → row persisted with `terminationTime ≈ now+25h`
and a returned id; duplicate `subscribe` returns same id; `renew` extends; `unsubscribe`
removes; after `clearOnBootup` the subscription list is empty.

### Tests for User Story 2 (write first, must FAIL)

- [X] T024 [P] [US2] `test_TIP1_A_4608_subscribe_persists_with_terminationTime_now_plus_25h()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/subscription/SubscriptionServiceTest.java`
- [X] T025 [P] [US2] `test_FR_012_duplicate_subscribe_returns_existing_id()` in the same `SubscriptionServiceTest` (uniqueness key endpoint+topic+context)
- [X] T026 [P] [US2] `test_TIP1_A_4609_unsubscribe_removes_subscription()` and `test_TIP1_A_5112_renew_extends_only_valid_subscriptions()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/subscription/SubscriptionLifecycleTest.java`
- [X] T027 [P] [US2] `test_TIP1_A_4613_bootup_starts_with_empty_subscription_list()` and `test_FR_008_retained_url_receives_bootup_complete()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/subscription/BootupClearTest.java`
- [X] T028 [P] [US2] `test_TIP1_A_4612_supports_999_subscriptions()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/subscription/SubscriptionCapacityTest.java`
- [ ] T029 [P] [US2] `@QuarkusTest` `test_KonnektorEventService_delegates_to_subscription_service()` (Subscribe→GetSubscription via SOAP impl) in `konnektor-soap-server/src/test/java/de/servicehealtherx/konnektor/soap/KonnektorEventServiceSubscriptionIT.java`

### Implementation for User Story 2

- [X] T030 [US2] Implement `SubscriptionService` (`subscribe`, `unsubscribe`, `renew`, `getSubscriptions`, `clearOnBootup`; `@Transactional`; uniqueness-key upsert; `terminationTime=now+25h`; `retainedUntil=max(...)`) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/subscription/SubscriptionService.java`
- [X] T031 [US2] Wire `KonnektorEventService.subscribe/unsubscribe/renewSubscriptions/getSubscription` to `SubscriptionService` (replace the UUID stub; map JAXB ↔ service types; checkArguments → 4000) in `konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/KonnektorEventService.java`
- [X] T032 [US2] Add a `@Observes StartupEvent` bootup hook calling `SubscriptionService.clearOnBootup()` and triggering the BOOTUP_COMPLETE send to retained URLs in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/subscription/BootupSubscriptionReset.java`

**Checkpoint**: US1 + US2 work; real subscriptions drive real deliveries.

---

## Phase 5: User Story 3 — Auto-Unsubscribe on repeated failure (Priority: P3)

**Goal**: Count consecutive failed connection/delivery attempts per sink; remove the
subscription(s) at `EVT_MAX_TRY`; reset on success; count connection-only failures when
the sink never answers.

**Independent Test**: With `cetp.evt-max-try=3` and an unreachable sink, fire matching
events — subscription removed exactly on the 3rd consecutive failure; one success
resets the counter.

### Tests for User Story 3 (write first, must FAIL)

- [X] T033 [P] [US3] `test_TIP1_A_4611_auto_unsubscribe_after_evt_max_try_failures()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/AutoUnsubscribeIT.java`
- [X] T034 [P] [US3] `test_FR_021_success_resets_failure_counter()` in `AutoUnsubscribeIT`
- [X] T035 [P] [US3] `test_FR_022_only_connection_failures_counted_when_sink_silent()` in `AutoUnsubscribeIT`

### Implementation for User Story 3

- [X] T036 [US3] Add `incrementFailures(ClientEndpoint)`, `resetFailures(ClientEndpoint)`, and `autoUnsubscribe(ClientEndpoint)` (cascade delete at `cetp.evt-max-try`) to `SubscriptionService` in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/subscription/SubscriptionService.java`
- [X] T037 [US3] In `CetpClient`, on each delivery result reset-on-success / increment-on-failure the endpoint counter and trigger `autoUnsubscribe` at threshold (read `cetp.evt-max-try`) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/CetpClient.java`

**Checkpoint**: US1–US3 work; the subscription list self-heals.

---

## Phase 6: User Story 4 — mTLS-secured delivery (Priority: P4)

**Goal**: Deliver over TLS as client using SmkCSAKAut (C.AK.AUT) for client auth and
PKIX validation of the sink's server cert against a client-system trust store; plain TCP
when not mandated.

**Independent Test**: TLS `FakeEventSink` requesting client auth + trust store with its
CA → mutually authenticated delivery succeeds; untrusted server cert → handshake refused,
counts as failure; `cetp.tls.mandatory=false` → plain TCP.

### Tests for User Story 4 (write first, must FAIL)

- [X] T038 [P] [US4] `test_TIP1_A_4595_delivery_over_tls_when_mandatory()` and `test_A_21760_02_presents_smkcsakaut_client_cert_on_request()` in `cetp-client-lib/src/test/java/de/servicehealtherx/cetp/tls/CetpMtlsIT.java`
- [X] T039 [P] [US4] `test_TIP1_A_5009_untrusted_server_cert_refused_via_pkix()` in `CetpMtlsIT`
- [X] T040 [P] [US4] `test_FR_027_plain_tcp_when_tls_not_mandatory()` in `CetpMtlsIT`
- [X] T041 [P] [US4] `test_FR_028_tls_handshake_failure_counts_as_failed_attempt()` in `CetpMtlsIT`

### Implementation for User Story 4

- [X] T042 [US4] Implement `CetpTlsContextFactory` (Netty `SslContextBuilder.forClient()` with `SmkCSAKAut` KMF; `TrustManagerFactory.getInstance("PKIX")` over the configured client-system trust store; `BouncyCastleJsseProvider`; `GematikSSLConfig.CIPHER_SUITE_LIST`) in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/tls/CetpTlsContextFactory.java`
- [X] T043 [US4] Add an `SslHandler` (from `CetpTlsContextFactory`) to the `CetpEventSender` pipeline when `cetp.tls.mandatory=true`; otherwise plain TCP in `cetp-client-lib/src/main/java/de/servicehealtherx/cetp/delivery/CetpEventSender.java` (depends on T042)

**Checkpoint**: All four stories functional; CETP1 + CETP2 supported.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T044 [P] Verify no secret key material (SmkCSAKAut, trust-store password) is logged; ensure structured logging of delivery outcomes only (Principle V) across `CetpEventSender`/`CetpClient`
- [X] T045 [P] Confirm SBOM unchanged (no new third-party dependency — research.md Principle VII) and add a short `cetp-client-lib/README.md` describing the module
- [ ] T046 Run the `quickstart.md` scenarios 1–4 end-to-end and record results
- [ ] T047 Verify unit-test coverage ≥ 80% for `cetp-client-lib` and cross-check the Afo→test matrix (every Afo in plan.md has a named passing test) before merge

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)**: no dependencies.
- **Foundational (P2)**: depends on Setup — **blocks all stories**. (T007 depends on T006.)
- **US1 (P3)**: depends on Foundational. **MVP.**
- **US2 (P4)**: depends on Foundational. Independent of US1 (tests seed/persist directly), but US2 + US1 together give the real end-to-end flow.
- **US3 (P5)**: depends on Foundational + the `CetpClient` delivery path from US1 and `SubscriptionService` from US2 (counter methods).
- **US4 (P6)**: depends on Foundational + `CetpEventSender` from US1.
- **Polish (P7)**: after all desired stories.

### Within each story

- Tests (the `### Tests…` block) are written first and MUST fail before implementation.
- Interface (T018) before its implementations (T019–T021).
- `CetpFrameCodec` (T016) before `CetpEventSender` (T022); senders/filters/writer before `CetpClient` (T023).

### Parallel opportunities

- Setup: T003 ∥ (after T001).
- Foundational: T004 ∥ T005 ∥ T008 ∥ T009; T006 → T007.
- US1 tests T010–T015 all ∥; impl T016 ∥ T017, then T019 ∥ T020 ∥ T021 (after T018).
- US2 tests T024–T029 all ∥.
- US3 tests T033–T035 ∥. US4 tests T038–T041 ∥.
- Across teams: once Foundational is done, US1/US2 can proceed in parallel; US3/US4 join after their prerequisites.

---

## Parallel Example: User Story 1

```bash
# Tests first (all parallel — distinct files):
Task: "T010 CetpFrameCodecTest"     Task: "T011 EventXmlWriterTest"
Task: "T012 TopicPrefixFilterTest"  Task: "T013 XPathExpressionFilterTest"
Task: "T014 AccessAuthorizationFilterTest"  Task: "T015 CetpClientDeliveryIT"

# Then parallel implementation of independent files:
Task: "T016 CetpFrameCodec"   Task: "T017 EventXmlWriter"
# After T018 (interface):
Task: "T019 TopicPrefixFilter"  Task: "T020 XPathExpressionFilter"  Task: "T021 AccessAuthorizationFilter"
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 → **STOP & validate** US1 over
plain TCP (quickstart Scenario 1) → demo. This is the smallest viable slice: the konnektor
delivers events to a seeded subscriber.

### Incremental delivery

US1 (deliver) → US2 (real subscriptions) → US3 (self-healing) → US4 (mTLS). Each adds value
without breaking prior stories. Per constitution: one commit per Afo
(`[BCP-xxxx] [AFO-ID] …`), each Afo covered by its named test in the same PR.

---

## Notes

- `[P]` = different files, no dependency on an incomplete task.
- Test names embed the Afo ID (Principle V) — e.g. `test_TIP1_A_4596_…` — for machine-readable traceability; integration substitutes are noted where unit-level verification is impractical (delivery/TLS over `FakeEventSink`).
- No new third-party dependency is introduced (Principle VII); SBOM unchanged.
- Commit after each task or logical Afo group; stop at any checkpoint to validate a story independently.
