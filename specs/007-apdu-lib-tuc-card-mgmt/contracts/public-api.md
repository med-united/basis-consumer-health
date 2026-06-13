# Public API Contract: apdu-lib Module

**Module**: `de.servicehealtherx:apdu-lib`
**Java Version**: 21
**Last Updated**: 2026-06-10

---

## Contract Principles

1. `apdu-lib` generates APDUs; it does not execute them.
2. Public APDU type is `javax.smartcardio.CommandAPDU`.
3. API output always includes expected response-status metadata.
4. No public API in this module depends on `sicct-lib`.

---

## Shared API Types

```java
public record GeneratedApduStep(
    javax.smartcardio.CommandAPDU command,
    ExpectedStatusSet expectedStatuses,
    String semanticLabel
) {}

public record ExpectedStatusSet(
    Set<Integer> acceptedSw,
    boolean allowWarnings
) {}

public record TucGenerationResult(
    List<GeneratedApduStep> steps,
    Map<String, Object> semanticOutputHints
) {}
```

---

## Representative TUC Signatures

```java
// TUC_KON_200 - Send APDU (generation only)
public final class TucKon200SendApdu {
    public GeneratedApduStep generate(CardSession cardSession, byte[] commandApduBytes);
}

// TUC_KON_208 - Send Secured APDU (generation only)
public final class TucKon208SendSecuredApdu {
    public TucGenerationResult generateSecuredScenario(
        CardSession cardSession,
        UUID sessionId,
        byte[] signedScenario,
        int expectedSequenceNumber
    );
}

// Example file operation TUC
public final class TucKon202ReadFile {
    public TucGenerationResult generateReadFile(
        CardSession cardSession,
        short fileIdentifier,
        int offset,
        int length
    );
}
```

---

## Error Code Contract

`TucException` keeps gematik numeric codes (4001-4094) for precondition and semantic errors detected during generation.

Transport-time errors are outside this module and handled by execution layers.

---

## Execution Handoff Contract

Execution layers (`sicct-lib`, `crypto-pcsc-lib`) consume `GeneratedApduStep` outputs and are responsible for:

- terminal/channel handling
- APDU transmission
- raw response retrieval
- retries/timeouts at transport level
