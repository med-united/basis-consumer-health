# Contract: SICCT Quarkus Extension Configuration Schema

**Feature**: `001-quarkus-basis-consumer` | **Date**: 2026-06-06

This document defines the MicroProfile Config keys exposed by the `sicct-quarkus-extension` runtime module and the `crypto-provider` module.

---

## SICCT Extension Config (`quarkus.sicct.*`)

### Terminal Management

**Terminal host/port records are NOT configured in the application config file.**

Individual terminal configurations (name, host, port, per-terminal timeouts) are stored exclusively in the database and managed via the Hawtio management console Card Terminals panel (see FR-096–FR-099). The `quarkus.sicct.terminals[*]` entries from earlier drafts are retired.

SICCT key aliases auto-register when cards are present; see the **SICCT Key Source Alias Naming Convention** section below.

### Global SICCT Settings

```properties
# Netty worker thread count (default: 2)
quarkus.sicct.worker-threads=2

# Reconnect watchdog interval (default: 30s; use Quarkus duration format)
quarkus.sicct.reconnect-watchdog-interval=30s

# Default connect timeout used if the CardTerminalRecord has no per-terminal override
quarkus.sicct.default-connect-timeout-ms=5000

# Default APDU timeout used if the CardTerminalRecord has no per-terminal override
quarkus.sicct.default-apdu-timeout-ms=10000

# Default reconnect backoff settings
quarkus.sicct.initial-backoff-ms=1000
quarkus.sicct.max-backoff-ms=30000

# Enable SmallRye Health readiness check for SICCT terminals (default: true)
quarkus.sicct.health.enabled=true
```

---

## Crypto Provider Config (`quarkus.crypto.*`)

### P12 Key Sources

```properties
quarkus.crypto.p12[0].alias=p12/konnektor-smcb
quarkus.crypto.p12[0].path=/run/secrets/konnektor.p12
quarkus.crypto.p12[0].password=${P12_PASSWORD_0}

quarkus.crypto.p12[1].alias=p12/test-rsa
quarkus.crypto.p12[1].path=/etc/crypto/test-rsa.p12
quarkus.crypto.p12[1].password=changeit
```

### PKCS#11 HSM Key Sources

```properties
quarkus.crypto.pkcs11[0].alias=pkcs11/utimaco-slot0
quarkus.crypto.pkcs11[0].name=Utimaco
quarkus.crypto.pkcs11[0].library-path=/opt/utimaco/libcs_pkcs11_R3.so
quarkus.crypto.pkcs11[0].token-pin=${PKCS11_PIN_0}
quarkus.crypto.pkcs11[0].slot-list-index=0

quarkus.crypto.pkcs11[1].alias=pkcs11/softhsm-ci
quarkus.crypto.pkcs11[1].name=SoftHSM
quarkus.crypto.pkcs11[1].library-path=/usr/lib/softhsm/libsofthsm2.so
quarkus.crypto.pkcs11[1].token-pin=1234
quarkus.crypto.pkcs11[1].slot-list-index=0
```

### PC/SC Key Sources

```properties
quarkus.crypto.pcsc[0].alias=pcsc/reader-a-slot0
quarkus.crypto.pcsc[0].reader-name=ACS ACR38U
quarkus.crypto.pcsc[0].pin=${PCSC_PIN_0}
```

### Alias Uniqueness

The system validates at startup that all alias values across all four source types are globally unique. If duplicates are detected, the application fails to start with a message listing the conflicting aliases and their source types.

---

## SICCT Key Source Alias Naming Convention

When the SICCT extension detects a card in a terminal slot, it auto-registers a `KeyAlias` using the naming convention:

```
sicct/<terminalName>-slot<slotIndex>
```

Where `terminalName` is the `name` field of the `CardTerminalRecord` stored in the database.

Examples:
- `sicct/praxis-a-terminal-1-slot0`
- `sicct/praxis-a-terminal-2-slot1`

These aliases are NOT configured statically; they appear and disappear dynamically as cards are inserted and removed. Callers querying `CryptoProvider.listAliases()` will see them only when a card is present.

---

## Hot-Reload Behavior

| Config key group | Hot-reload supported | Behavior on change |
|-----------------|---------------------|--------------------|
| `quarkus.sicct.*` (global settings) | No | Requires restart |
| SICCT terminals (DB-managed) | Yes, immediately | Via management console CRUD — no config change needed; see FR-096–FR-099 |
| `quarkus.crypto.p12[*]` | Yes (add/remove) | New sources loaded; removed sources marked UNAVAILABLE within 60s |
| `quarkus.crypto.pkcs11[*]` | No | Requires restart (PKCS#11 session teardown is unsafe hot) |
| `quarkus.crypto.pcsc[*]` | Yes (add/remove) | New sources start monitoring; removed sources unregistered |

---

## Secrets Management

All password/pin/token-pin fields support MicroProfile Config expression substitution (`${ENV_VAR}`) and are compatible with Quarkus Vault (`quarkus-vault` extension) for production secret injection. The values MUST NOT be logged at any level; the `@ConfigMapping` interface for these fields is annotated to mask them in config health/dev-ui endpoints.
