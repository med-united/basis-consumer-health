# Contract: Generic APDU Executor

**New class**: `de.servicehealtherx.apdu.card.ApduExecutor` (`apdu-lib`)

## Why it is needed

The `apdu-lib` TUC classes (`TucKonNNN*`) are **step generators**: each returns a
`TucGenerationResult` = an ordered `List<GeneratedApduStep>` (`CommandAPDU` +
`ExpectedStatusSet` + label) plus a `hints` map. They do **not** transmit APDUs.
Today only `CardCertificateReader` actually executes APDUs, and it bypasses the
generator pattern. `ReadVsdService` must orchestrate several multi-step TUCs
(TUC_KON_026/023/018/005/202/006), so a single, tested executor is the foundational
building block.

## Responsibility

Run a `TucGenerationResult` against a resolved `CardReaderPort` and validate each
step, returning the per-step responses. **Single responsibility:** transmit + verify
status words; it does not know about VSDM, C2C, or files.

## Interface (intended)

```text
ApduExecutor(CardReaderPort port)

List<ResponseAPDU> execute(TucGenerationResult steps)
        throws ApduExecutionException
```

Semantics:
- For each `GeneratedApduStep` in order: `port.transmit(step.commandApdu())`, then
  assert the `ResponseAPDU` status word is in `step.expectedStatusSet()`.
- On an unexpected status word → throw `ApduExecutionException(step.label(), sw)`;
  callers (e.g. `ReadVsdService`) translate this into the proper VSDM code
  (3011 read failure, 3041/3042 PIN, etc.).
- Returns the list of responses (so a reader can pull data fields out of READ BINARY
  responses, challenges out of GET CHALLENGE, etc.).
- Stateless and side-effect-free beyond the card transmission; no logging of response
  data bodies (may carry card material / PII) — only labels + status words at debug.

## Reuse

- `CardReaderPort` (transport SPI, PCSC + SICCT impls) — **reused**, not modified.
- The multi-block READ-BINARY loop (256-byte blocks, `0x6282` end-of-file) currently
  inside `CardCertificateReader.readBinaryFull` is generalised into `EgkFileReader`
  (see `egk-vsdm-files.md`), which uses `ApduExecutor` for the SELECT/READ steps.

## Testing

- Fake `CardReaderPort` returning canned `ResponseAPDU`s → assert ordering, status
  validation, and exception on an unexpected SW. Deterministic, no hardware.
