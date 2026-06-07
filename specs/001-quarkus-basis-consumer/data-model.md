# Data Model: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Branch**: `001-quarkus-basis-consumer` | **Date**: 2026-06-05 (updated 2026-06-06)

**Persistence layer**: JPA + H2. JPA entities live in `sicct-lib`. Runtime-only state is held in `@ApplicationScoped` CDI beans. Configuration entities are read from MicroProfile Config at startup.

---

## Persistent Entities (JPA — `sicct-lib`)

### CardTerminal

Persisted registration of a SICCT card terminal. Managed via the Hawtio console; MUST NOT be stored in config files (FR-028).

| Field                       | Type          | Column                           | Constraints                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| --------------------------- | ------------- | -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| id                          | Long          | `id`                             | PK, auto-generated                                                                                                                                                                                                                                                                                                                                                                                                                           |
| terminalId                  | String        | `terminal_id`                    | UNIQUE, NOT NULL, max 64 chars                                                                                                                                                                                                                                                                                                                                                                                                               |
| host                        | String        | `host`                           | NOT NULL, max 255 chars                                                                                                                                                                                                                                                                                                                                                                                                                      |
| port                        | int           | `port`                           | NOT NULL, default 4876                                                                                                                                                                                                                                                                                                                                                                                                                       |
| description                 | String        | `description`                    | nullable, max 512 chars                                                                                                                                                                                                                                                                                                                                                                                                                      |
| discoveredAt                | Instant       | `discovered_at`                  | NOT NULL (set on first UDP discovery or manual add)                                                                                                                                                                                                                                                                                                                                                                                          |
| lastSeenAt                  | Instant       | `last_seen_at`                   | nullable; updated on each successful TCP connection                                                                                                                                                                                                                                                                                                                                                                                          |
| pairingStatus               | PairingStatus | `pairing_status`                 | ENUM: UNKNOWN, PAIRED, PAIRING_FAILED                                                                                                                                                                                                                                                                                                                                                                                                        |
| sealedSharedSecret          | String        | `sealed_shared_secret`           | nullable (null until first successful pairing); base64url-encoded TPM 2.0 `TPM2B_PRIVATE` + `TPM2B_PUBLIC` sealed blob of the 16-byte ShS.KT.AUT; raw secret recoverable only by the same TPM with matching PCR 0/1/7 values (FR-139); raw secret MUST NOT appear in any other column, log, or heap dump                                                                                                                                     |
| backupEncryptedSharedSecret | String        | `backup_encrypted_shared_secret` | nullable (null outside an active backup preparation window); base64url-encoded `IV (12 bytes) ‖ AES-256-GCM ciphertext (16 bytes) ‖ GCM tag (16 bytes)` of the `ShS.KT.AUT`; encryption key = HKDF-SHA-256(IKM=backupPassword, salt=terminalId as UTF-8, info="ShS.KT.AUT backup"); populated only during backup preparation (FR-180); automatically cleared after the backup retention window; MUST NOT contain unencrypted secret material |
| connectTimeoutMs            | int           | `connect_timeout_ms`             | NOT NULL, default 5000; TCP connect timeout per terminal                                                                                                                                                                                                                                                                                                                                                                                     |
| apduTimeoutMs               | int           | `apdu_timeout_ms`                | NOT NULL, default 10000; SICCT APDU round-trip timeout                                                                                                                                                                                                                                                                                                                                                                                       |
| maxRetries                  | int           | `max_retries`                    | NOT NULL, default -1 (unlimited reconnects)                                                                                                                                                                                                                                                                                                                                                                                                  |
| initialBackoffMs            | int           | `initial_backoff_ms`             | NOT NULL, default 1000                                                                                                                                                                                                                                                                                                                                                                                                                       |
| maxBackoffMs                | int           | `max_backoff_ms`                 | NOT NULL, default 30000                                                                                                                                                                                                                                                                                                                                                                                                                      |

**Validation rules**:
- `terminalId` must match `^[a-z0-9][a-z0-9\-_]{0,62}[a-z0-9]$`
- `port` must be in range 1–65535
- `host` must be a valid IP address or resolvable hostname

**State transitions** (`PairingStatus`):
```
[UNKNOWN] ──── EHEALTH AUTHENTICATE CREATE success ──→ [PAIRED]
[UNKNOWN] ──── EHEALTH AUTHENTICATE CREATE failure ──→ [PAIRING_FAILED]
[PAIRED]  ──── SAK.AUT key rotation + ADD success   ──→ [PAIRED]          (stays)
[PAIRED]  ──── TPM unseal: PCR mismatch             ──→ [PAIRING_FAILED]  (re-pair required)
[PAIRING_FAILED] ──── operator retry ───────────────→ [UNKNOWN]
```

Note: `PAIRING_FAILED` reached via PCR mismatch clears `sealedSharedSecret`; the terminal must go through a full CREATE cycle to re-establish a new sealed secret.

---

---

## Runtime Entities

### KeyAlias

A unique identifier for a private key within the unified provider.

| Field      | Type       | Description                                                 |
| ---------- | ---------- | ----------------------------------------------------------- |
| raw        | String     | Full alias string, format: `<sourceType>/<userDefinedName>` |
| sourceType | SourceType | Enum: `P12`, `PKCS11`, `PCSC`, `SICCT`                      |
| localName  | String     | User-defined name segment after the `/`                     |

**Validation rules**:
- `raw` must match pattern `^(p12|pkcs11|pcsc|sicct)/[a-z0-9\-_]+$`
- `raw` must be unique across all configured key sources at startup; duplicate → startup failure with descriptive error
- Max length: 128 characters

**State transitions**: None (immutable value type).

---

### KeyStoreDescriptor

A registered key source at runtime, regardless of its current availability.

| Field        | Type                 | Description                                                   |
| ------------ | -------------------- | ------------------------------------------------------------- |
| alias        | KeyAlias             | The unique alias for this key source                          |
| sourceType   | SourceType           | Enum: P12, PKCS11, PCSC, SICCT                                |
| availability | KeyStoreAvailability | AVAILABLE / UNAVAILABLE / ERROR                               |
| errorMessage | String?              | Non-null only when availability = ERROR; human-readable cause |
| lastUpdated  | Instant              | Last time availability changed                                |

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

| Field          | Type          | Description                                                                               |
| -------------- | ------------- | ----------------------------------------------------------------------------------------- |
| alias          | KeyAlias      | Target key source alias                                                                   |
| operationType  | OperationType | SIGN, VERIFY, ENCRYPT, or DECRYPT                                                         |
| data           | byte[]        | Raw input bytes (document to sign/verify, plaintext to encrypt, or ciphertext to decrypt) |
| signature      | byte[]        | Signature bytes to verify (VERIFY operations only; null otherwise)                        |
| algorithm      | String        | JCA algorithm name, e.g., `SHA256withECDSA`, `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`      |
| callerIdentity | String        | Tenant ID + service name for audit logging                                                |

---

### CryptoOperationResult

Output from any `CryptoProvider` operation.

| Field         | Type            | Description                                                                 |
| ------------- | --------------- | --------------------------------------------------------------------------- |
| alias         | KeyAlias        | Echo of the request alias                                                   |
| operationType | OperationType   | Echo of the request type                                                    |
| result        | byte[]          | Signature bytes (SIGN), plaintext bytes (DECRYPT/ENCRYPT), or null (VERIFY) |
| verified      | Boolean         | true/false for VERIFY operations; null for all other operations             |
| certificate   | X509Certificate | The certificate corresponding to the signing key (SIGN/VERIFY operations)   |
| timestampUtc  | Instant         | Completion timestamp (audit)                                                |

---

## SICCT-Specific Runtime Entities

### SicctTerminalDescriptor

Represents one configured SICCT terminal endpoint.

| Field           | Type                  | Description                                               |
| --------------- | --------------------- | --------------------------------------------------------- |
| terminalId      | String                | User-defined unique name from config (e.g., `terminal-1`) |
| host            | String                | IP address or hostname                                    |
| port            | int                   | TCP port (default 4876)                                   |
| connectionState | SicctConnectionState  | See state machine below                                   |
| activeCardSlots | List\<SicctCardSlot\> | Currently occupied card slots                             |
| retryCount      | int                   | Current reconnect attempt count                           |
| nextRetryAt     | Instant?              | Non-null when in backoff state                            |

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

| Field           | Type           | Description                                            |
| --------------- | -------------- | ------------------------------------------------------ |
| slotId          | int            | SICCT slot index                                       |
| terminalId      | String         | Parent terminal ID                                     |
| cardState       | SicctCardState | EMPTY / CARD_PRESENT / CARD_ACTIVE                     |
| associatedAlias | KeyAlias?      | Non-null when card is active and key alias is resolved |
| cardType        | String?        | SMC-B or eHBA (if detectable from ATR)                 |

**State transitions** (`SicctCardState`):
```
[EMPTY] ──── card-inserted ──→ [CARD_PRESENT] ──── pin-verified / key-resolved ──→ [CARD_ACTIVE]
  ▲                                 │                                                    │
  └──── card-ejected ───────────────┴──────────────── card-ejected ─────────────────────┘
```

---

## Configuration Entities (MicroProfile Config-backed, read-only)

### P12KeyStoreConfig

One entry in `quarkus.crypto.p12[*]` config. A PKCS#12 file may contain multiple key entries; both the file-level password and the per-entry password must be supplied independently.

| Field            | Config key           | Type            | Required | Description                                                           |
| ---------------- | -------------------- | --------------- | -------- | --------------------------------------------------------------------- |
| alias            | `.alias`             | String          | Yes      | Unique key source alias (e.g. `p12/signing-key`)                      |
| path             | `.path`              | Path            | Yes      | Filesystem path to the PKCS#12 file                                   |
| entryAlias       | `.entry-alias`       | String          | Yes      | Alias of the private key entry within the keystore                    |
| keystorePassword | `.keystore-password` | char[] / secret | Yes      | Password to open the PKCS#12 file                                     |
| entryPassword    | `.entry-password`    | char[] / secret | Yes      | Password to unlock the private key entry (may equal keystorePassword) |

### Pkcs11KeyStoreConfig

One entry in `quarkus.crypto.pkcs11[*]` config.

| Field         | Config key         | Type            | Required             |
| ------------- | ------------------ | --------------- | -------------------- |
| alias         | `.alias`           | String          | Yes                  |
| libraryPath   | `.library-path`    | Path            | Yes                  |
| tokenPin      | `.token-pin`       | char[] / secret | Yes                  |
| slotListIndex | `.slot-list-index` | int             | No (default 0)       |
| name          | `.name`            | String          | Yes (unique per JVM) |

### PcscKeyStoreConfig

One entry in `quarkus.crypto.pcsc[*]` config.

| Field      | Config key     | Type            | Required                             |
| ---------- | -------------- | --------------- | ------------------------------------ |
| alias      | `.alias`       | String          | Yes                                  |
| readerName | `.reader-name` | String          | Yes (substring match on reader name) |
| pin        | `.pin`         | char[] / secret | No                                   |

### AppConfigProperty

Stores MicroProfile Config properties in JavaDB, read by the `configsource-db` extension (`microprofile-extensions/config-ext`) in production. This table makes the database the single source of truth for all application configuration, ensuring a full DB dump is a complete system backup.

| Field | Column      | Type   | Constraints                                                     |
| ----- | ----------- | ------ | --------------------------------------------------------------- |
| name  | `PROPNAME`  | String | PK; MicroProfile Config property key (e.g. `quarkus.http.port`) |
| value | `PROPVALUE` | String | NOT NULL; property value as string                              |

Config properties for secrets (passwords, PINs, keys) MUST be stored here encrypted at the application level; the configsource-db extension delivers the raw string value, and the application is responsible for decrypting it before use.

---

## Entity Relationships

```
CardTerminal (JPA — persisted)
        │
        │ 1:1 runtime correlation (by terminalId)
        ▼
SicctTerminalDescriptor (runtime — CDI bean)
        │
        │ 1:N
        ▼
SicctCardSlot ──→ associatedAlias (KeyAlias)
                             │
                             ▼
                        KeyAlias
                             │
                ┌────────────┼────────────┐
                ▼            ▼            ▼
        KeyStoreDescriptor  ...   KeyStoreDescriptor
        (type=P12)                 (type=SICCT)

CryptoOperationRequest ──── targets ──→ KeyAlias
```
