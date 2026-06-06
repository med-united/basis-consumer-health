# Data Model: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Branch**: `001-quarkus-basis-consumer` | **Date**: 2026-06-05

This feature has no persistent storage. All entities below are runtime-only, held in `@ApplicationScoped` CDI beans. Configuration entities are read from MicroProfile Config at startup (and on hot-reload).

---

## Runtime Entities

### KeyAlias

A unique identifier for a private key within the unified provider.

| Field | Type | Description |
|-------|------|-------------|
| raw | String | Full alias string, format: `<sourceType>/<userDefinedName>` |
| sourceType | SourceType | Enum: `P12`, `PKCS11`, `PCSC`, `SICCT` |
| localName | String | User-defined name segment after the `/` |

**Validation rules**:
- `raw` must match pattern `^(p12|pkcs11|pcsc|sicct)/[a-z0-9\-_]+$`
- `raw` must be unique across all configured key sources at startup; duplicate → startup failure with descriptive error
- Max length: 128 characters

**State transitions**: None (immutable value type).

---

### KeySourceDescriptor

A registered key source at runtime, regardless of its current availability.

| Field | Type | Description |
|-------|------|-------------|
| alias | KeyAlias | The unique alias for this key source |
| sourceType | SourceType | Enum: P12, PKCS11, PCSC, SICCT |
| availability | KeySourceAvailability | AVAILABLE / UNAVAILABLE / ERROR |
| errorMessage | String? | Non-null only when availability = ERROR; human-readable cause |
| lastUpdated | Instant | Last time availability changed |

**State transitions**:

```
         startup-load-success       hardware-reconnected
             ┌──────────────────────────────────────┐
             ▼                                      │
     [AVAILABLE] ─── error / removal ──→ [UNAVAILABLE] ─── retry-failed ──→ [ERROR]
             ▲                                      │
             └──────── auto-recovery ───────────────┘
```

- `AVAILABLE → UNAVAILABLE`: card removal, PKCS#11 session closed, SICCT TCP disconnect, P12 file deleted post-load
- `UNAVAILABLE → AVAILABLE`: card re-inserted, PKCS#11 session re-established, SICCT reconnected, P12 hot-reloaded
- `UNAVAILABLE → ERROR`: maximum retry attempts exceeded (SICCT only); operator intervention required
- `ERROR → UNAVAILABLE`: operator triggers reset via management endpoint

---

### CryptoOperationRequest

Input to any `CryptoProvider` operation.

| Field | Type | Description |
|-------|------|-------------|
| alias | KeyAlias | Target key source alias |
| operationType | OperationType | SIGN or DECRYPT |
| data | byte[] | Raw input bytes (document to sign or ciphertext to decrypt) |
| algorithm | String | JCA algorithm name, e.g., `SHA256withECDSA`, `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` |
| callerIdentity | String | Tenant ID + service name for audit logging |

---

### CryptoOperationResult

Output from any `CryptoProvider` operation.

| Field | Type | Description |
|-------|------|-------------|
| alias | KeyAlias | Echo of the request alias |
| operationType | OperationType | Echo of the request type |
| result | byte[] | Signature bytes or plaintext bytes |
| certificate | X509Certificate | The certificate corresponding to the signing key (for SIGN operations) |
| timestampUtc | Instant | Completion timestamp (audit) |

---

## SICCT-Specific Runtime Entities

### SicctTerminalDescriptor

Represents one configured SICCT terminal endpoint.

| Field | Type | Description |
|-------|------|-------------|
| terminalId | String | User-defined unique name from config (e.g., `terminal-1`) |
| host | String | IP address or hostname |
| port | int | TCP port (default 4876) |
| connectionState | SicctConnectionState | See state machine below |
| activeCardSlots | List\<SicctCardSlot\> | Currently occupied card slots |
| retryCount | int | Current reconnect attempt count |
| nextRetryAt | Instant? | Non-null when in backoff state |

**State machine** (`SicctConnectionState`):

```
                start
                  │
                  ▼
           [CONNECTING] ──── connect-failed ──→ [RECONNECTING]
                  │                                    │
           connect-success                     backoff-expired
                  │                                    │
                  ▼                                    ▼
           [CONNECTED] ──── disconnect ──────→ [RECONNECTING]
                  │                                    │
           graceful-shutdown               max-retries-exceeded
                  │                                    │
                  ▼                                    ▼
          [DISCONNECTED]                       [FAILED]
```

- `CONNECTING`: Initial TCP handshake + SICCT INIT CT SESSION in progress
- `CONNECTED`: Session established; card slot monitoring active
- `RECONNECTING`: Backoff wait between reconnect attempts
- `DISCONNECTED`: Graceful shutdown; no further reconnects
- `FAILED`: Max retries exceeded; alert logged; no auto-recovery

---

### SicctCardSlot

Represents one card slot within a connected SICCT terminal.

| Field | Type | Description |
|-------|------|-------------|
| slotId | int | SICCT slot index |
| terminalId | String | Parent terminal ID |
| cardState | SicctCardState | EMPTY / CARD_PRESENT / CARD_ACTIVE |
| associatedAlias | KeyAlias? | Non-null when card is active and key alias is resolved |
| cardType | String? | SMC-B or eHBA (if detectable from ATR) |

**State transitions** (`SicctCardState`):
```
[EMPTY] ──── card-inserted ──→ [CARD_PRESENT] ──── pin-verified / key-resolved ──→ [CARD_ACTIVE]
  ▲                                 │                                                    │
  └──── card-ejected ───────────────┴──────────────── card-ejected ─────────────────────┘
```

---

## Configuration Entities (MicroProfile Config-backed, read-only)

### P12KeySourceConfig

One entry in `quarkus.crypto.p12[*]` config.

| Field | Config key | Type | Required |
|-------|-----------|------|----------|
| alias | `.alias` | String | Yes |
| path | `.path` | Path | Yes |
| password | `.password` | char[] / secret | Yes |

### Pkcs11KeySourceConfig

One entry in `quarkus.crypto.pkcs11[*]` config.

| Field | Config key | Type | Required |
|-------|-----------|------|----------|
| alias | `.alias` | String | Yes |
| libraryPath | `.library-path` | Path | Yes |
| tokenPin | `.token-pin` | char[] / secret | Yes |
| slotListIndex | `.slot-list-index` | int | No (default 0) |
| name | `.name` | String | Yes (unique per JVM) |

### PcscKeySourceConfig

One entry in `quarkus.crypto.pcsc[*]` config.

| Field | Config key | Type | Required |
|-------|-----------|------|----------|
| alias | `.alias` | String | Yes |
| readerName | `.reader-name` | String | Yes (substring match on reader name) |
| pin | `.pin` | char[] / secret | No |

### SicctTerminalConfig

One entry in `quarkus.sicct.terminals[*]` config.

| Field | Config key | Type | Required |
|-------|-----------|------|----------|
| id | `.id` | String | Yes |
| host | `.host` | String | Yes |
| port | `.port` | int | No (default 4876) |
| connectTimeoutMs | `.connect-timeout-ms` | int | No (default 5000) |
| apduTimeoutMs | `.apdu-timeout-ms` | int | No (default 10000) |
| maxRetries | `.max-retries` | int | No (default -1 = unlimited) |
| initialBackoffMs | `.initial-backoff-ms` | int | No (default 1000) |
| maxBackoffMs | `.max-backoff-ms` | int | No (default 30000) |

---

## Entity Relationships

```
CryptoOperationRequest ──── targets ──→ KeyAlias
                                             │
                           ┌─────────────────┼──────────────────┐
                           ▼                 ▼                  ▼
                   KeySourceDescriptor  KeySourceDescriptor  KeySourceDescriptor
                   (type=P12)           (type=PKCS11)         (type=SICCT)
                                                                │
                                                    SicctTerminalDescriptor
                                                          │
                                                    SicctCardSlot ──→ associatedAlias (KeyAlias)
```
