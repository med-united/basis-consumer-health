# Research: apdu-lib Module — TUC Card Management

**Feature Branch**: `007-apdu-lib-tuc-card-mgmt`
**Phase**: 0 — Pre-Design Research
**Last Updated**: 2026-06-10
**Constitution Version**: 1.7.0

---

## 1. Technical Context Decisions

### Language and Build

- **Decision**: Java 21 with Maven wrapper (`mvnw`).
- **Rationale**: Parent build already enforces Java 21 and Maven-based multi-module structure.

### APDU Type Library

- **Decision**: **ADOPT `javax.smartcardio` for APDU object modeling**.
- **Rationale**: Constitution Principle VIII prefers established JDK interfaces and types when suitable.
- **Scope**:
  - Use `javax.smartcardio.CommandAPDU` inside `apdu-lib` as the canonical generated APDU type.
  - `apdu-lib` may optionally use `javax.smartcardio.ResponseAPDU` only for status-word mapping helpers and tests.
  - `apdu-lib` does **not** open terminals, channels, or execute APDUs.

### Transport Boundary

- **Decision**: `apdu-lib` remains a **generation-only** library.
- **Rationale**: APDU execution is an infrastructure concern and belongs to execution layers (`sicct-lib`, `crypto-pcsc-lib`).
- **Constraint**:
  - No APDU send logic in `apdu-lib`.
  - No dependency from `apdu-lib` to `sicct-lib`.
  - No transport interface such as `CardTerminalTransport` in `apdu-lib`.

---

## 2. Dependency and Architecture Decisions

### Module Dependency Graph

```text
apdu-lib -> crypto-lib (for cryptographic helper integration where needed)
apdu-lib -X-> sicct-lib (forbidden dependency)
```

### Package Layout

```text
de.servicehealtherx.apdu.model     - domain types and APDU generation result types
de.servicehealtherx.apdu.tuc       - TUC builders that generate APDU plans
de.servicehealtherx.apdu.constant  - ISO/gematik constants not already provided elsewhere
```

### Test Strategy

- Validate generated `CommandAPDU` byte arrays, INS/P1/P2/Lc/Le semantics, and expected status metadata.
- No terminal emulator and no mock transport required.

---

## 3. Alternatives Considered

- **Custom `CommandApdu`/`ResponseApdu` records**: rejected because `javax.smartcardio` already provides mature APDU value types.
- **Embedding send/transmit into `apdu-lib`**: rejected; violates separation of concerns and user requirement.
- **Depending on `sicct-lib` for constants**: rejected; required constants must live in `apdu-lib` if needed.

---

## 4. Final Decision Summary

| Topic | Decision |
|-------|----------|
| APDU object library | Use `javax.smartcardio.CommandAPDU` |
| APDU transport | Not in `apdu-lib` |
| `sicct-lib` dependency | Forbidden |
| Testing | Byte-level APDU generation verification |
| Standard interface adoption | Principle VIII satisfied by JDK smartcardio types |
