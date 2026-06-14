# Implementation Plan: Card Handle

**Branch**: `006-card-handle` | **Date**: 2026-06-07 | **Spec**: [spec.md](spec.md)

## Summary

Implement the in-memory **CM_CARD_LIST** card-management list and `CardObject`/`CardHandle` lifecycle (TUC_KON_001 / TUC_KON_056 / TUC_KON_057) as a **transport-agnostic** capability that works identically for directly PC/SC-connected card readers and SICCT-connected card terminals. The card-management domain objects and CM_CARD_LIST live in **`apdu-lib`**; a single shared CM_CARD_LIST instance is part of both `PcscCryptoProvider` (`crypto-pcsc-lib`) and `SicctCryptoProvider` (`crypto-sicct-lib`), each implementing the `apdu-lib` `CardReaderPort` transport boundary over its respective transport. Scope includes: data model, the shared CM_CARD_LIST CDI bean, insertion/removal handling over both transports, startup reconstruction, disconnect invalidation/rebuild, card session subtypes (eGK / SM-B / HBAx), eGK session lock/unlock (TUC_KON_223/224), comfort-signature state (TUC_KON_171–173), CDI event publication (CARD/INSERTED, CARD/REMOVED, CERT/CARD/STATUS, CARD/SESSION/TIMEOUT), G2.0 pseudonym admin log, and the `GetCards`, `RequestCard`, `EjectCard` SOAP operations — all returning a unified view across both transports.

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
| Principle I — Code Quality: no single-implementation interfaces | ✅ PASS | `CmCardList` is a concrete CDI bean (no interface). `CardReaderPort` is an interface with **two** real implementations (`PcscCardReaderPort`, `SicctCardReaderPort`), justifying the abstraction. |
| Principle (apdu-lib transport neutrality) | ✅ PASS | CM_CARD_LIST + domain objects in `apdu-lib`; no dependency on `sicct-lib` or PC/SC types; `apdu-lib` constructs APDUs but does not transmit (transmission lives behind `CardReaderPort` in the providers). |
| Module wiring | ⚠ ACTION | `crypto-sicct-lib/pom.xml` adds `apdu-lib` dependency; both providers inject the single shared `CmCardList` bean. |
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

Transport-agnostic core (`apdu-lib`) — domain objects + CM_CARD_LIST + transport port. No `sicct-lib` or PC/SC dependency:

```text
apdu-lib/src/main/java/de/servicehealtherx/apdu/card/
├── CardObject.java               ← CardObject/CardHandle (CM_CARD_LIST entry; relocated from sicct.models)
├── CardVersion.java              ← value object
├── CardType.java                 ← enum (EGK, KVK, HBAx, SMB, SMC_KT, UNKNOWN …)
├── CardSession.java              ← abstract base
├── CardSession_eGK.java          ← key: cardHandle; adds sessionID, timer
├── CardSession_SMB.java          ← key: cardHandle + mandantId
├── CardSession_HBAx.java         ← key: cardHandle + csid + userId; adds comfort sig state
├── AuthState.java                ← value object (C2C or CHV entry)
├── CmCardList.java               ← @ApplicationScoped CM_CARD_LIST; ONE shared instance for both providers
├── CardObjectFactory.java        ← TUC_KON_001: reads card attributes via CardReaderPort, returns CardObject
└── transport/
    └── CardReaderPort.java       ← transport-neutral port (transmit APDU, observe insert/remove, capabilities)
```

Provider adapters — each implements `CardReaderPort` over its transport and shares the injected CM_CARD_LIST:

```text
crypto-pcsc-lib/src/main/java/de/servicehealtherx/crypto/pcsc/
├── PcscCryptoProvider.java       ← existing; inject shared CmCardList; drive PcscCardReaderPort
└── PcscCardReaderPort.java       ← new; javax.smartcardio reader; poll insert/remove → CmCardList

crypto-sicct-lib/src/main/java/de/servicehealtherx/crypto/sicct/
├── SicctCryptoProvider.java      ← existing; inject shared CmCardList; drive SicctCardReaderPort
└── SicctCardReaderPort.java      ← new; sicct-lib terminal; insert/remove events → CmCardList
```
> `crypto-sicct-lib/pom.xml` MUST add a dependency on `apdu-lib` (crypto-pcsc-lib already has it).

SICCT-only event/JPA helpers and SOAP wiring (unchanged module homes):

```text
sicct-lib/src/main/java/de/servicehealtherx/sicct/
├── jpa/G2CardLog.java            ← JPA entity (pseudonym admin log, A_25801)
└── event/                        ← CDI event records: CardInsertedEvent, CardRemovedEvent,
                                     CardSessionTimeoutEvent, CertCardStatusEvent

quarkus-sicct-extension/runtime/src/main/java/de/servicehealtherx/quarkus/sicct/runtime/
├── SicctEventPublisher.java      ← new (CDI @Observes → TUC_KON_256 log/dispatch)
├── SicctChannelHandler.java      ← existing; route card insert/remove into SicctCardReaderPort
└── SicctTerminalManager.java     ← existing; @PostConstruct startup reconstruction (→ CmCardList),
                                     onTerminalDisconnected invalidation

konnektor-soap-server/src/main/java/de/servicehealtherx/konnektor/soap/
├── KonnektorEventService.java    ← existing; implement getCards() over unified CmCardList
└── KonnektorCardTerminalService.java ← existing; implement requestCard(), ejectCard() over both transports
```

## Complexity Tracking

> No constitution violations requiring justification.
