# Research: Card Handle

**Feature**: Card Handle (specs/002-card-handle)
**Date**: 2026-06-14 (transport-agnostic PC/SC + SICCT; per-provider CM_CARD_LIST)

## Decision 1 — In-memory CM_CARD_LIST data structure (per provider)

**Decision**: `ConcurrentHashMap<String, CardObject>` as the primary store inside the `CmCardList` class (in `apdu-lib`), supplemented by a secondary `Map<UUID, List<CardObject>>` keyed by `ctid` for fast per-reader/terminal invalidation. **Each provider owns its own `CmCardList` instance** (not a shared singleton — see Decision 10).

**Rationale**: `ConcurrentHashMap` provides O(1) lookup by `cardHandle` (the hottest read path for every SOAP operation) and lock-striped concurrent writes for simultaneous insertion events. A secondary inverse index by `ctid` avoids full-map scans during the invalidate-all-for-reader operation (FR-044/FR-069). Defining `CmCardList` in `apdu-lib` keeps it transport-neutral; each provider holding its own instance keeps PC/SC and SICCT card state self-contained.

**Alternatives considered**:
- Single `ConcurrentHashMap` with full-scan for terminal invalidation — rejected: O(n) scan under lock on disconnect is acceptable for small n but unnecessary given a secondary index is cheap.
- JPA-backed storage — rejected: CardHandles are explicitly not persisted (rebuilt on reconnect, FR-042/FR-045); adding JPA would add latency, H2 lock contention, and schema coupling.

---

## Decision 2 — cardHandle identifier generation and 48-hour blacklist

**Decision**: Generate `cardHandle` as a random UUID (`UUID.randomUUID().toString()`). Maintain a bounded `ConcurrentLinkedDeque<BlacklistedEntry>` (max 10,000) where each entry holds the UUID string and its expiry `Instant`. Entries older than 48 hours are lazily pruned on each new handle generation.

**Rationale**: Random UUID provides global uniqueness without coordination. The 48-hour reuse prohibition (FR-003, TUC_KON_001) is a spec requirement; a lazy-pruning deque gives O(1) amortised insertion and O(k) expiry check where k is the number of recently expired entries — negligible under normal load.

**Alternatives considered**:
- Bloom filter for the 48h blacklist — rejected: overkill; the number of invalidated handles per 48h window is bounded by the number of physical slots (≤ 200), making a simple deque more than sufficient.
- Scheduled pruning thread — rejected: adds complexity; lazy pruning on each generation is simpler and equivalent in effect.

---

## Decision 3 — CDI Event<T> for CARD/INSERTED, CARD/REMOVED, CERT/CARD/STATUS, CARD/SESSION/TIMEOUT

**Decision**: Use standard CDI `Event<T>` (`@Inject Event<CardInsertedEvent> cardInserted; cardInserted.fire(...)`) with dedicated immutable event records per topic. `SicctEventPublisher` observes all four event types and writes the canonical gemSpec_Kon log entry (topic, severity, parameters) to the Quarkus log at INFO/WARNING as appropriate.

**Rationale**: CDI `Event<T>` is the standard Java EE/Jakarta EE decoupling mechanism (Principle VIII — standard interface adoption). It keeps `CardHandleFactory` and `SicctChannelHandler` free of publishing concerns and allows future subscribers (e.g., a CETP event forwarder) to `@Observes` without modifying producers.

**Alternatives considered**:
- Direct log calls in `CardHandleFactory` — rejected: couples event production to a specific logging format; future CETP forwarding would require modifying factory code.
- Quarkus reactive messaging — rejected: heavyweight for local in-process events; CDI synchronous fire is sufficient and avoids broker dependency.

---

## Decision 4 — CardSession subtype hierarchy

**Decision**: Abstract base class `CardSession` with three concrete subclasses: `CardSession_eGK`, `CardSession_SMB`, `CardSession_HBAx`. Sessions held in a `List<CardSession>` on `CardHandle`; lookup uses `instanceof` + identity key comparison.

**Rationale**: Spec defines three distinct session types (FR-021) with different identity keys and different optional attributes. Concrete subclasses make type-specific fields visible and avoid null-optional fields on a flat model. No interface is needed because there is no substitution requirement across session types — each SOAP operation targets a specific subtype.

**Alternatives considered**:
- Single `CardSession` class with nullable fields — rejected: violates Principle I (code clarity) and forces null-checks scattered across callers.
- Java sealed interface `CardSession` — considered valid but adds verbosity for simple value classes; abstract class sufficient here.

---

## Decision 5 — Startup reconstruction and terminal-disconnect invalidation

**Decision**: Extend `SicctTerminalManager.initialize()` (@PostConstruct) to call a new `CmCardList.rebuildFromTerminal(CardTerminal, List<SlotInfo>)` after each successful TLS connection. Extend `onTerminalDisconnected(String hostname)` to call `CmCardList.invalidateAllForTerminal(UUID ctid)`, which removes all handles, fires `CardRemovedEvent` for each, and marks them as recently invalidated.

**Rationale**: The terminal manager already owns the connection lifecycle (startup + reconnect). Placing reconstruction/invalidation triggers there avoids a separate lifecycle component and keeps the event sequence deterministic: connect → reconstruct → CARD/INSERTED × N; disconnect → CARD/REMOVED × N → invalidate.

**Alternatives considered**:
- Separate `CardHandleReconstructionService` with its own `@Observes` on terminal connect events — valid but adds an extra layer; since `SicctTerminalManager` already has the terminal list and connection state, direct delegation is simpler.

---

## Decision 6 — G2CardLog persistence

**Decision**: JPA entity `G2CardLog` in `sicct-lib` with a unique constraint on `iccsn`. `ValidFrom` is updated (upsert pattern via `merge`) on each insertion of the same ICCSN. The admin log is queryable via a JMX MBean (`CardG2LogManagement`) exposing `listG2Cards()`.

**Rationale**: A_25801 requires persistence of the admin overview while the card is "known to the system". JPA + H2 (already present for `CardTerminal`) is the simplest approach. Uniqueness on ICCSN implements the "update on re-insertion" semantics cleanly.

**Alternatives considered**:
- In-memory only (`@ApplicationScoped` map) — rejected: spec says the log must persist ("solange die Betriebszustände aktiv sind" — while the operational state is active); a Konnektor restart should not lose G2.0 card history.
- Separate admin REST endpoint — rejected: existing pattern uses JMX MBeans for admin access (FR-220–FR-234 in plan.md for feature 001).

---

## Decision 7 — RequestCard / EjectCard SICCT command dispatch

**Decision**: `KonnektorCardTerminalService.requestCard()` delegates to a new `CardRequestService` which: (1) calls `TUC_KON_000` access check, (2) sends `SICCT_REQUEST_ICC` via `SicctTerminalConnection.sendRequestIcc(ctId, slotId, cardType, displayMessage, timeoutSec)`, (3) awaits the `CardHandle` either from the already-present handle or from the `CardInsertedEvent` that `CardHandleFactory` fires synchronously after `TUC_KON_001`, (4) sets `AlreadyInserted` from whether a handle was present before the call.

**Rationale**: The existing `SicctTerminalConnection` already has the channel for sending APDUs. Wrapping the flow in a dedicated service class keeps SOAP endpoint thin, makes unit testing straightforward, and centralises timeout handling.

**Alternatives considered**:
- Inline in `KonnektorCardTerminalService` — rejected: mixes SOAP parameter handling with SICCT protocol logic, making both harder to test.

---

## Decision 8 — eGK session lock implementation

**Decision**: `CmCardList` maintains a `ConcurrentHashMap<String, EgkSessionLock>` (keyed by `cardHandle`) where `EgkSessionLock` holds the `sessionID` (UUID), lock-holder identity, and the `ScheduledFuture` for `CARD_SESSION_TIMEOUT`. `StartCardSession` acquires the lock via `putIfAbsent`; `StopCardSession` removes it by matching `sessionID`.

**Rationale**: `putIfAbsent` provides the atomic "at-most-one session" guarantee (FR-022 / Constraint C1) without explicit synchronization. The session lock and the timer live together, making cleanup straightforward on both explicit stop (FR-028) and timeout (FR-027 / A_25860-01).

**Alternatives considered**:
- `synchronized` block on the `CardHandle` — rejected: coarser lock would serialize all handle operations, not just eGK session management.
- Storing the lock inside `CardSession_eGK` — rejected: the lock must be checked before a session object is created (to enforce the "at most one" rule); placing it externally avoids the chicken-and-egg problem.

---

## Decision 9 — Transport abstraction via `CardReaderPort` in `apdu-lib`

**Decision**: Define a transport-neutral interface `CardReaderPort` in `apdu-lib` (`de.servicehealtherx.apdu.card.transport`). It exposes: `transmit(CommandAPDU) → ResponseAPDU`, insert/remove observation (callback or poll), reader/slot identity (`ctid`, `slotNo`), and capability flags (`hasDisplay`, `hasMechanicalEject`, `hasSlotSelection`). `PcscCardReaderPort` (in `crypto-pcsc-lib`, over `javax.smartcardio`) and `SicctCardReaderPort` (in `crypto-sicct-lib`, over `sicct-lib`) implement it. `apdu-lib` constructs APDUs and owns CM_CARD_LIST but never transmits.

**Rationale**: Two real implementations justify the interface (Principle I). Keeping the port and CM_CARD_LIST in `apdu-lib` lets every card-handle use case (FR-063) run unchanged over either transport; transport-specific code is confined to the two provider modules. `apdu-lib` keeps its constitution constraint of not depending on `sicct-lib` and not transmitting APDUs.

**Alternatives considered**:
- Duplicate the card-handle logic in each provider — rejected: two divergent copies, doubles test surface, violates DRY.
- Put the abstraction in `crypto-lib` — rejected: card-handle/APDU concerns belong with `apdu-lib`; `crypto-lib` stays focused on key/crypto primitives.

---

## Decision 10 — Per-provider CM_CARD_LIST + card-service aggregation (not a shared singleton)

**Decision**: `CmCardList` is a plain class instantiated once per provider; `PcscCryptoProvider` and `SicctCryptoProvider` each hold their own. The card service (GetCards in `KonnektorEventService`) concatenates each provider's `findAll` to form the unified, transport-spanning response and resolves a `cardHandle` by probing each provider in turn. System-wide `cardHandle` uniqueness (random UUID, Decision 2) keeps the union duplicate-free.

**Rationale**: Requested explicitly (each provider owns its list). Avoids cross-provider coupling and a contended shared singleton, lets each transport own its own lifecycle (startup scan, disconnect invalidation) independently, while aggregation still presents a single logical card view to higher layers.

**Alternatives considered**:
- Single `@ApplicationScoped` shared `CmCardList` injected into both providers — rejected per updated requirement (FR-062); would couple the two transports' state and contend on one map.
- A façade registry that delegates to per-provider lists — viable, but the card service performing aggregation is simpler and avoids an extra indirection layer.

---

## Decision 11 — PC/SC insertion/removal detection

**Decision**: `PcscCardReaderPort` uses `javax.smartcardio.TerminalFactory.getDefault().terminals()` and a background poll on `CardTerminals.waitForChange(timeout)` (plus `CardTerminal.isCardPresent()`) to detect insert/remove. On change it invokes `CardObjectFactory` (insert → `CmCardList.add`) or `CmCardList.removeBySlot` (remove). Poll cadence is configured so detection-to-handle latency stays within the 2 s budget (FR-001).

**Rationale**: `javax.smartcardio` is the JDK-standard PC/SC binding (Principle VIII — standard interface adoption) and needs no third-party native library. `waitForChange` is event-like (blocks until a change), giving low-latency detection without a tight busy-loop. Unplugging a reader surfaces as a `CardException`/terminal-absent, mapped to `removeAllForTerminal` (FR-069).

**Alternatives considered**:
- Third-party PC/SC libraries (e.g. jnasmartcardio) — rejected: extra dependency + SBOM entry; the JDK binding is sufficient.
- Pure fixed-interval polling of `isCardPresent()` — rejected: higher latency/CPU than `waitForChange`; kept only as a fallback when `waitForChange` is unsupported.

---

## Decision 12 — PC/SC reader `ctid` synthesis and logical eject

**Decision**: Derive a stable `ctid` for a PC/SC reader as `UUID.nameUUIDFromBytes(readerName)` (deterministic v3 UUID), and register the reader as a `CardTerminal` entry so `ctid` has the same UUID/FK shape as a SICCT terminal (FR-068). EjectCard on a reader whose `hasMechanicalEject` capability is false performs a **logical eject** — remove the CardObject from CM_CARD_LIST and return success, no error 4203 (FR-070). RequestCard/EjectCard skip the display prompt when `hasDisplay` is false (FR-071).

**Rationale**: A name-derived UUID is stable across reconnects of the same reader (same name → same `ctid`), satisfying FR-068 without persistent state. Logical eject is the only meaningful behaviour for readers without a throwout mechanism and matches the graceful-degradation rule (FR-063).

**Alternatives considered**:
- Random UUID per reader connection — rejected: would change `ctid` on every reconnect, breaking handle/terminal correlation (FR-068 "same reader → same ctid").
- Returning a "not supported" error for eject on non-ejecting readers — rejected by clarification; callers expect the handle to be invalidated and success returned.
