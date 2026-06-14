# Quickstart Validation Guide: Card Handle

**Feature**: Card Handle (specs/002-card-handle)
**Date**: 2026-06-14 (transport-agnostic PC/SC + SICCT; per-provider CM_CARD_LIST)

This guide describes how to validate the Card Handle feature end-to-end without physical hardware, over **both** transports — directly PC/SC-connected readers and SICCT terminals.

> **Transport parity**: Scenarios 1–7 are written for the SICCT path but MUST also pass over the PC/SC path. Run each as a parameterised test across both transports via a fake `CardReaderPort` (SC-018). Scenarios 10–12 cover PC/SC-specific and cross-transport behaviour.

---

## Prerequisites

- Java 21, Maven 3.9+
- The SICCT emulator built into the IT test framework (`EmbeddedChannel` test double in `quarkus-sicct-extension`)
- A fake `CardReaderPort` (transport-neutral test double in `apdu-lib` tests) and/or a PC/SC reader simulator for the PC/SC path
- No physical card terminal or smart card required

---

## Scenario 1 — Card Handle created on simulated SICCT insertion

**What it validates**: FR-001, FR-003–FR-013, SC-001, SC-002, FR-046

**Test class**: `CmCardListIT` in `quarkus-sicct-extension/runtime/src/it/java/`

**Steps**:
1. Start `@QuarkusTest` with embedded H2 and the SICCT `EmbeddedChannel` double
2. Send a simulated SICCT `CT/SLOT_IN_USE` event (TAG 84) for `ctId=test-kt-01`, `slotNo=1`
3. Assert within 2 seconds:
   - `GetCards(mandantId=M1, ctId=test-kt-01)` returns exactly one `Card` element
   - `Card.CardHandle` is a non-empty UUID string not previously issued
   - `Card.CardType` matches the card type injected by the simulator
   - `Card.CertificateExpirationDate` is present and matches the simulated certificate
4. Assert `CARD/INSERTED` appears in the log at INFO level with correct parameters

---

## Scenario 2 — Card Handle invalidated on card removal

**What it validates**: FR-018, FR-047, SC-006

**Steps**:
1. Insert a card (Scenario 1)
2. Send a simulated `CT/SLOT_FREE` event (TAG 85) for the same slot
3. Assert within 1 second:
   - `GetCards` returns zero cards for that slot
   - `CARD/REMOVED` appears in the log
4. Call any operation referencing the now-invalidated `cardHandle` → assert appropriate error response

---

## Scenario 3 — Startup reconstruction

**What it validates**: FR-042, FR-043, SC-013

**Steps**:
1. Pre-configure the SICCT double to report slot 1 as occupied on the initial status query
2. Start the Quarkus application (`SicctTerminalManager.initialize()`)
3. Assert within 10 seconds of the terminal connection being established:
   - `GetCards` returns a card for slot 1 with a fresh `cardHandle`
   - `cardSessionList` on that handle is empty (no session state restored)

---

## Scenario 4 — Terminal disconnect invalidates and reconnect rebuilds

**What it validates**: FR-044, FR-045, SC-014

**Steps**:
1. Insert a card (Scenario 1)
2. Close the `EmbeddedChannel` to simulate terminal TCP disconnect
3. Assert within 1 second: `GetCards` returns zero cards; `CARD/REMOVED` logged
4. Reconnect the `EmbeddedChannel`; double reports slot 1 occupied
5. Assert within 10 seconds: new handle visible in `GetCards`; new UUID differs from the pre-disconnect one

---

## Scenario 5 — RequestCard: empty slot, card inserted within timeout

**What it validates**: FR-033–FR-040, SC-011

**Steps**:
1. Ensure slot 2 is empty in the SICCT double
2. Call `RequestCard(mandantId=M1, ctId=test-kt-01, slotId=2, timeOut=5)`
3. After 1 second, simulate card insertion via SICCT double
4. Assert:
   - Response contains `Status=OK`, `AlreadyInserted=false`, `Card` element with valid `CardHandle`
   - Same handle appears in subsequent `GetCards`

---

## Scenario 6 — RequestCard: wrong card type

**What it validates**: FR-038

**Steps**:
1. Configure SICCT double to insert an eGK card
2. Call `RequestCard(cardType=SM-B, slotId=2, timeOut=5)`
3. Simulate card insertion
4. Assert response contains error code 4051; no Card element; `GetCards` for that slot returns the card (handle was created, but RequestCard itself returned error)

---

## Scenario 7 — EjectCard: by CardHandle, lock check

**What it validates**: FR-051–FR-056

**Steps**:
1. Insert a card (Scenario 1); note the `cardHandle`
2. Acquire an eGK session lock by calling `StartCardSession` with that handle
3. Call `EjectCard(cardHandle=<handle>)` from a **different** client context
4. Assert error 4093 (card reserved)
5. Stop the session; repeat `EjectCard` → assert `Status=OK`; `GetCards` returns zero cards for that slot

---

## Scenario 8 — eGK session timeout fires CARD/SESSION/TIMEOUT event

**What it validates**: FR-027, FR-049

**Steps**:
1. Insert an eGK card
2. Call `StartCardSession` → assert `sessionID` UUID returned
3. Set `CARD_SESSION_TIMEOUT` to 2 seconds for test
4. Do NOT call `SecureSendAPDU` within 2 seconds
5. Assert:
   - `CARD/SESSION/TIMEOUT` event logged with correct `SessionID` and `CardType=EGK`
   - eGK session lock is released (a subsequent `StartCardSession` succeeds)

---

## Scenario 9 — Comfort signature activate / deactivate (HBA)

**What it validates**: FR-029–FR-032, SC-010

**Steps**:
1. Insert an HBAx card
2. Call `ActivateComfortSignature` → assert `signMode = Comfort` on session; `countRemaining` initialized
3. Simulate 3 signing operations → assert `countRemaining` decremented by 3
4. Call `DeactivateComfortSignature` → assert `signMode = PIN`; timer cancelled; PIN.QES removed from `authState`

---

## Scenario 10 — PC/SC reader insertion parity (transport-agnostic core)

**What it validates**: FR-002, FR-061, FR-063, SC-016, SC-018, SC-019

**Test class**: `CmCardListTest` in `apdu-lib/src/test/java/` (no transport, fake `CardReaderPort`)

**Steps**:
1. Drive a fake `CardReaderPort` configured as a PC/SC reader (`hasMechanicalEject=false`, `hasDisplay=false`) to signal an insertion
2. Assert `PcscCryptoProvider`'s `CmCardList` now contains one `CardObject` whose `ctid` = `UUID.nameUUIDFromBytes(readerName)` (FR-068)
3. Assert the `CardObject` field set/types are identical to a SICCT-originated one (field-by-field, SC-016)
4. Assert `apdu-lib` test module has no compile dependency on `sicct-lib` or any PC/SC type (SC-019)

---

## Scenario 11 — Unified GetCards aggregated across both providers

**What it validates**: FR-062, FR-064, FR-067, SC-017

**Steps**:
1. Insert one card via the SICCT path (Scenario 1) and one card via the PC/SC path (Scenario 10) simultaneously
2. Call `GetCards(mandantId=M1)` once
3. Assert both `Card` elements are returned, with distinct `ctid` values, aggregated from the two providers' separate `CmCardList` instances
4. Assert each `cardHandle` resolves to exactly one provider (no handle appears in both lists)

---

## Scenario 12 — EjectCard logical eject on a PC/SC reader (no mechanical throwout)

**What it validates**: FR-070, FR-071, FR-069

**Steps**:
1. Insert a card via the PC/SC path (Scenario 10); note the `cardHandle`
2. Call `EjectCard(cardHandle=<handle>)`
3. Assert `Status=OK` and **no** error 4203 (logical eject — handle invalidated, CardObject removed from CM_CARD_LIST)
4. Assert no display prompt was attempted (reader `hasDisplay=false`, FR-071)
5. Unplug the reader (fake port reports terminal-absent) → assert all its handles invalidated within 1 s (FR-069); re-plug → assert fresh handle with a new `cardHandle` but the same `ctid`

---

## Log Verification Checklist

After running all scenarios, assert these log lines are present at the correct level:

| Event | Level | Topic |
|---|---|---|
| Card Handle created | INFO | `CARD/INSERTED` |
| Card Handle invalidated | INFO | `CARD/REMOVED` |
| Certificate not good (SMC-B / HBA) | WARN | `CERT/CARD/STATUS` |
| eGK session timed out | INFO | `CARD/SESSION/TIMEOUT` |

See [contracts/](contracts/) for full parameter lists expected in each log line.
