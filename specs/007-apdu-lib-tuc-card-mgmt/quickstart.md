# Quickstart & Validation Guide: apdu-lib Module

**Feature Branch**: `007-apdu-lib-tuc-card-mgmt`
**Module**: `apdu-lib` (`de.servicehealtherx:apdu-lib`)
**Last Updated**: 2026-06-10

---

## Purpose

Validate that `apdu-lib` correctly generates `javax.smartcardio.CommandAPDU` objects and expected status metadata for TUC flows, without sending APDUs.

---

## Prerequisites

- Java 21
- Maven 3.9+
- Checked-out workspace

No card terminal, no simulator, and no SICCT runtime are needed.

---

## Build

```bash
mvn -pl apdu-lib clean install -DskipTests
```

---

## Run Tests

```bash
mvn -pl apdu-lib test
```

---

## Validation Scenario 1: Verify PIN generation (TUC_KON_012)

Goal:
- generated step uses INS `0x20` (VERIFY)
- P2 matches `PinRef`
- expected status includes `0x9000` and PIN retry warning class

Example command:

```bash
mvn -pl apdu-lib test -Dtest="*TucKon012*"
```

---

## Validation Scenario 2: Read file generation (TUC_KON_202)

Goal:
- first generated step is SELECT (`0xA4`)
- second generated step is READ BINARY (`0xB0`)
- offset/length are encoded correctly in P1/P2/Le

Example command:

```bash
mvn -pl apdu-lib test -Dtest="*TucKon202*"
```

---

## Validation Scenario 3: Secured scenario generation (TUC_KON_208)

Goal:
- sequence mismatch is rejected before generation
- generated steps preserve scenario order
- every step has expected status metadata

Example command:

```bash
mvn -pl apdu-lib test -Dtest="*TucKon208*"
```

---

## Expected Outcome

- tests validate APDU generation only
- no transport mocks required
- no runtime dependency on `sicct-lib`
