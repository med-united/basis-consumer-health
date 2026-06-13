# Data Model: apdu-lib Module

**Feature Branch**: `007-apdu-lib-tuc-card-mgmt`
**Module**: `apdu-lib` (`de.servicehealtherx:apdu-lib`)
**Last Updated**: 2026-06-10

---

## Overview

`apdu-lib` models card-management TUCs as APDU generation use-cases. Each TUC builds one or more `javax.smartcardio.CommandAPDU` objects plus response expectations. The module does not execute APDUs.

---

## Core Entities

### 1. CardSession

Represents logical card context used for APDU generation decisions.

```java
public record CardSession(
    String cardHandle,
    CardType cardType,
    CardVersion cardVersion,
    AuthState authState,
    UUID sessionId,
    String lockOwner
) {}
```

Notes:
- `cardHandle` is opaque and transport-agnostic.
- No `sicct-lib` type is referenced.

### 2. GeneratedApduStep

One generated APDU command with expected response constraints.

```java
public record GeneratedApduStep(
    javax.smartcardio.CommandAPDU command,
    ExpectedStatusSet expectedStatuses,
    String semanticLabel
) {}
```

### 3. ExpectedStatusSet

Defines accepted SW values for a generated APDU step.

```java
public record ExpectedStatusSet(Set<Integer> acceptedSw, boolean allowWarnings) {}
```

### 4. TucGenerationResult

Common return envelope for TUCs that generate multiple steps.

```java
public record TucGenerationResult(
    List<GeneratedApduStep> steps,
    Map<String, Object> semanticOutputHints
) {}
```

### 5. Enums and Value Types

- `CardType`, `CardVersion`, `PinRef`, `PinStatus`, `PinResult`, `PukResult`, `AuthMode`, `AlgorithmId`
- `AuthState` tracks verified PIN/key state used for generation preconditions.

---

## Removed From Model

The following are intentionally not part of `apdu-lib`:

- `CardTerminalTransport`
- transport `transmit/send` contracts
- transport-owned response lifecycle

---

## Validation Rules

- TUC preconditions are validated before APDU generation.
- KVK restrictions are enforced at generation time for write/clear/crypto operations.
- TUC_KON_208 sequence checks are validated before secured scenario APDU generation.
- Every generated step must carry expected SW metadata.

---

## Relationship Summary

```text
CardSession -> TUC builder -> TucGenerationResult -> List<GeneratedApduStep>
GeneratedApduStep -> CommandAPDU + ExpectedStatusSet
Execution layers (sicct-lib / crypto-pcsc-lib) consume generated steps externally
```
