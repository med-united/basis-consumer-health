# Implementation Plan: Card Handle

**Branch**: `006-card-handle` | **Date**: 2026-06-07 | **Spec**: [spec.md](spec.md)

## Summary

Implement the in-memory `CardHandle` lifecycle (TUC_KON_001 / TUC_KON_056 / TUC_KON_057) including: data model, `CardHandleRegistry` CDI bean, SICCT insertion/removal event handling, startup reconstruction, disconnect invalidation/rebuild, card session subtypes (eGK / SM-B / HBAx), eGK session lock/unlock (TUC_KON_223/224), comfort-signature state (TUC_KON_171–173), CDI event publication (CARD/INSERTED, CARD/REMOVED, CERT/CARD/STATUS, CARD/SESSION/TIMEOUT), G2.0 pseudonym admin log, and the `GetCards`, `RequestCard`, `EjectCard` SOAP operations.

## Technical Context

**Language/Version**: Java 21 LTS

**Primary Dependencies**:

| Dependency | Purpose |
|---|---|
| Quarkus 3.x + quarkus-cxf | CDI lifecycle, SOAP endpoint hosting |
| Netty 4.x | SICCT TCP channel; card insert/remove events arrive via `SicctChannelHandler` |
| JPA + H2 | G2CardLog admin log (persisted); CardHandleRegistry is in-memory only |
| gemLibPki 4.0.2 | TUC_KON_037 OCSP validation for certStatus/certOcspResponse |
| javax.management (JDK) | JMX MBean for admin card overview (G2.0 pseudonym log) |
| JUnit 5 + Quarkus Test | Unit and integration tests; Netty EmbeddedChannel for SICCT simulation |
| api-telematik (submodule) | WSDL/XSD-generated types for EventService v7.2, CardTerminalService v1.1 |

**Storage**:
- **CardHandle / CardSession / AuthState** — runtime in-memory only; held in `CardHandleRegistry` (`ConcurrentHashMap<String, CardHandle>`); never persisted to DB. Rebuilt from terminal query on startup and reconnect.
- **G2CardLog** — JPA entity persisted to H2; tracks G2.0 SMC-B/HBA pseudonym + last insertion timestamp per ICCSN (A_25801).
- **cardHandle 48-hour blacklist** — in-memory `ConcurrentLinkedDeque<BlacklistedHandle>` inside `CardHandleRegistry`; bounded to 10,000 entries; cleared on restart (acceptable — restart itself issues new handles).

**Testing**:
- Unit: JUnit 5; direct construction of `CardHandleRegistry`, `CardHandleFactory` with mocked dependencies
- Integration: `@QuarkusTest`; Netty `EmbeddedChannel` to simulate SICCT card-insertion events; embedded H2 for G2CardLog; CXF test client for GetCards / RequestCard / EjectCard SOAP operations
- All SICCT hardware interactions replaced by `EmbeddedChannel`-based test doubles

**Target Platform**: Linux server, JVM mode, Kubernetes pod

**Project Type**: Extension to existing multi-module Maven project (see `specs/001-quarkus-basis-consumer/plan.md`)

**Performance Goals**:
- Card Handle creation from SICCT insertion event: ≤ 2 s (FR-001 / SC-001)
- GetCards response: ≤ 100 ms (in-memory lookup only, FR-041)
- Startup reconstruction (all terminals): ≤ 10 s after last terminal connects (SC-013)
- Disconnect invalidation: ≤ 1 s (SC-014)
- CARD/INSERTED event publication: within Card Handle creation path (synchronous CDI fire)

**Constraints**:
- CardHandle identifiers MUST NOT be reused within 48 hours of invalidation (FR-003)
- No software cap on concurrent handles; bounded by physical slot count (Assumptions)
- G2.0 pseudonym = first 10 chars of SHA-256(ICCSN ‖ expirationDate) as hex string (A_25801)
- Card type checks / OCSP validation run asynchronously after handle creation (A_23702)

## Constitution Check

| Gate | Status | Notes |
|---|---|---|
| Principle I — Code Quality: no single-implementation interfaces | ✅ PASS | `CardHandleRegistry` is a concrete CDI bean; no `ICardHandleRegistry` wrapper needed (single impl) |
| Principle I — Code Quality: no `Impl` suffix | ✅ PASS | All classes named by role: `CardHandleRegistry`, `CardHandleFactory`, `SicctEventPublisher` |
| Principle II — Testing discipline | ✅ PASS | Unit tests for registry ops; IT tests for SOAP + SICCT event flow |
| Principle III — Security: no handle bytes in logs | ✅ PASS | cardHandle logged only at DEBUG, never in ERROR/WARN |
| Principle IV — Resource constraints (150 MB ceiling) | ✅ PASS | ConcurrentHashMap per-entry ≈ 2 KB; 200 slots × 2 KB = 400 KB — negligible |
| Principle V — CDI standard DI | ✅ PASS | All new beans `@ApplicationScoped`; events via CDI `Event<T>` |
| Principle VIII — Standard Interface Adoption | ✅ PASS | CDI `Event<T>` (standard CDI) for events; JPA for G2CardLog; SOAP interface from WSDL-generated `CardTerminalServicePortType` / `EventServicePortType` already implemented |

## Project Structure

### Documentation (this feature)

```text
specs/002-card-handle/
├── plan.md              ← this file
├── research.md          ← Phase 0 decisions
├── data-model.md        ← entity definitions
├── quickstart.md        ← validation guide
├── contracts/
│   ├── GetCards.md
│   ├── RequestCard.md
│   └── EjectCard.md
├── diagrams/
│   ├── README.md
│   ├── state-card-handle.puml
│   ├── state-card-session.puml
│   ├── sequence-card-inserted.puml
│   ├── sequence-request-card.puml
│   └── sequence-eject-card.puml
└── tasks.md             ← generated by /speckit-tasks
```

### Source Code

```text
sicct-lib/src/main/java/de/servicehealtherx/sicct/
├── models/
│   ├── CardHandle.java            ← fill existing empty class
│   ├── CardVersion.java           ← new value object
│   ├── CardType.java              ← new enum (EGK, KVK, HBAx, SMB, SMC_KT, UNKNOWN …)
│   ├── CardSession.java           ← new abstract base
│   ├── CardSession_eGK.java       ← new (key: cardHandle; adds sessionID, timer)
│   ├── CardSession_SMB.java       ← new (key: cardHandle + mandantId)
│   ├── CardSession_HBAx.java      ← new (key: cardHandle + csid + userId; adds comfort sig state)
│   └── AuthState.java             ← new value object (C2C or CHV entry)
├── jpa/
│   ├── CardTerminal.java          ← existing (no changes)
│   └── G2CardLog.java             ← new JPA entity (pseudonym admin log, A_25801)
└── event/
    ├── CardInsertedEvent.java     ← new CDI event record
    ├── CardRemovedEvent.java      ← new CDI event record
    ├── CardSessionTimeoutEvent.java ← new CDI event record
    └── CertCardStatusEvent.java   ← new CDI event record

quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/
├── CardHandleRegistry.java        ← new @ApplicationScoped (CM_CARD_LIST)
├── CardHandleFactory.java         ← new (reads card attributes via APDU, returns CardHandle)
├── SicctEventPublisher.java       ← new (CDI @Observes → TUC_KON_256 log/dispatch)
├── SicctChannelHandler.java       ← existing; extend to fire CDI events on card insert/remove
└── SicctTerminalManager.java      ← existing; extend @PostConstruct for startup reconstruction,
                                       onTerminalDisconnected for handle invalidation

konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/
├── KonnektorEventService.java     ← existing; implement getCards() properly
└── KonnektorCardTerminalService.java ← existing; implement requestCard(), ejectCard() properly
```

## Complexity Tracking

> No constitution violations requiring justification.
