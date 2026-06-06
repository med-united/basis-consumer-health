# Contract: JMX Management Beans

**Feature**: `001-quarkus-basis-consumer` | **Date**: 2026-06-06

This document defines the JMX MBean interface contracts for all management beans exposed by the Basis Consumer (FR-220–FR-234). All beans follow the JDK standard MBean pattern (JSR 3, `javax.management.*`) and are registered on `ManagementFactory.getPlatformMBeanServer()`. Hawtio (embedded) discovers them automatically at domain `de.servicehealtherx`.

See [research.md — Topic 18](../research.md) for the implementation pattern and security constraints.

---

## ObjectName Convention

```
de.servicehealtherx:module=<module-folder-name>,name=<ClassName>
```

---

## `TslManagement` — `crypto-lib`

**ObjectName**: `de.servicehealtherx:module=crypto-lib,name=TslManagement`
**MBean interface**: `de.servicehealtherx.crypto.jmx.TslManagementMBean`

### Attributes (read-only)

| Attribute | Type | Description |
|-----------|------|-------------|
| `TslUrl` | `String` | Configured TSL download URL (from `AppConfigProperty`) |

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `reloadTsl` | — | `String` (JSON) | Forces an immediate TSL download. Returns `{ "status": "OK\|FAILED", "sequenceNumber": N, "expiry": "ISO", "downloadedAt": "ISO", "error": "null\|message" }`. Blocks until download completes or 60 s timeout. |
| `getTslStatus` | — | `String` (JSON) | Returns current TSL state: `{ "sequenceNumber": N, "expiry": "ISO", "downloadedAt": "ISO", "status": "VALID\|EXPIRED\|UNAVAILABLE" }`. |

---

## `CryptoProviderManagement` — `crypto-lib`

**ObjectName**: `de.servicehealtherx:module=crypto-lib,name=CryptoProviderManagement`
**MBean interface**: `de.servicehealtherx.crypto.jmx.CryptoProviderManagementMBean`

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `listKeyStores` | — | `String` (JSON array) | All registered `KeyStoreAdapter` instances: `[{ "storeType": "P12\|PKCS11\|PCSC\|SICCT", "aliases": [...], "availability": "AVAILABLE\|UNAVAILABLE\|ERROR" }]` |
| `listKeyReferences` | — | `String` (JSON array) | All `KeyReference` objects across all adapters: `[{ "alias": "...", "storeType": "...", "algorithm": "...", "keyUsage": "...", "certificateSubject": "...", "certificateExpiry": "ISO" }]` |
| `getAvailability` | `alias: String` | `String` | `AvailabilityStatus` enum name or `"KEY_NOT_FOUND"` |
| `getAvailabilities` | — | `String` (JSON object) | Map of alias → `AvailabilityStatus` for all registered aliases |

---

## `KeyStoreReloadManagement` — `crypto-lib`

**ObjectName**: `de.servicehealtherx:module=crypto-lib,name=KeyStoreReloadManagement`
**MBean interface**: `de.servicehealtherx.crypto.jmx.KeyStoreReloadManagementMBean`

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `reloadKeyStore` | `storeType: String`, `alias: String` | `String` (JSON) | Calls `engineLoad(null, null)` on the matching adapter. Returns `{ "alias": "...", "availability": "AVAILABLE\|UNAVAILABLE\|ERROR", "error": "null\|message" }`. For SICCT, this is a no-op (connections managed by extension). |
| `reloadAllKeyStores` | — | `String` (JSON object) | Calls `engineLoad(null, null)` on all adapters. Returns map of alias → result. |

---

## `BackupRestoreManagement` — `sicct-lib`

**ObjectName**: `de.servicehealtherx:module=sicct-lib,name=BackupRestoreManagement`
**MBean interface**: `de.servicehealtherx.sicct.jmx.BackupRestoreManagementMBean`

> **Security**: All mutating operations on this bean MUST require Hawtio `admin` role (FR-232).

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `exportBackup` | — | `String` | The backup password (plaintext, shown once). Triggers backup preparation (FR-180): generates random 256-bit password, AES-256-GCM-encrypts each terminal's `ShS.KT.AUT` under it, persists `backupEncryptedSharedSecret`. Returns password string. **Password MUST NOT be stored by the system.** |
| `importBackup` | `backupPassword: String` | `String` (JSON) | Triggers restore (FR-181). Returns `[{ "terminalId": "...", "result": "restored\|failed: <reason>" }]`. Emits CRITICAL audit entries. |
| `getBackupStatus` | — | `String` (JSON) | `{ "pairedTerminals": N, "terminalsWithBackup": N, "retentionWindowExpiry": "ISO\|null" }` |

---

## `SicctTerminalDiscoveryManagement` — `quarkus-sicct-extension`

**ObjectName**: `de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalDiscoveryManagement`
**MBean interface**: `de.servicehealtherx.quarkus.sicct.runtime.jmx.SicctTerminalDiscoveryManagementMBean`

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `triggerDiscovery` | — | `void` | Sends the SICCT UDP broadcast (FR-091, FR-222). Newly discovered terminals are persisted as `CardTerminal` entities and connection attempts initiated. |
| `getLastDiscoveryResult` | — | `String` (JSON array) | Terminals from the most recent discovery: `[{ "terminalId": "...", "host": "...", "port": 4876, "discoveredAt": "ISO" }]` |

---

## `SicctTerminalConnectionManagement` — `quarkus-sicct-extension`

**ObjectName**: `de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalConnectionManagement`
**MBean interface**: `de.servicehealtherx.quarkus.sicct.runtime.jmx.SicctTerminalConnectionManagementMBean`

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `connect` | `terminalId: String` | `String` | Initiates TCP connection to the terminal. No-op if already CONNECTED. Returns new connection state name. |
| `disconnect` | `terminalId: String` | `String` | Gracefully closes TCP connection (in-flight APDUs complete or timeout). Suspends auto-reconnect. Returns resulting state name. |
| `getTerminalStatus` | `terminalId: String` | `String` (JSON) | `{ "terminalId": "...", "connectionState": "CONNECTED\|...", "host": "...", "port": N, "pairingStatus": "PAIRED\|...", "activeSlots": N }` |
| `listAllTerminals` | — | `String` (JSON array) | All registered terminals with current state, host, port, and slot count. |

---

## `CardPinManagement` — `quarkus-sicct-extension`

**ObjectName**: `de.servicehealtherx:module=quarkus-sicct-extension,name=CardPinManagement`
**MBean interface**: `de.servicehealtherx.quarkus.sicct.runtime.jmx.CardPinManagementMBean`

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `verifyPin` | `terminalId: String`, `slotId: int`, `pinType: String` | `String` (JSON) | Triggers SICCT VERIFY PIN on the terminal's trusted PIN pad. `pinType` ∈ `{ "HBA.PIN.CH", "HBA.PIN.QES", "SMC-B.PIN.SMC" }`. PIN is entered on the card terminal display — it MUST NOT pass through JVM memory. Returns `{ "result": "OK\|REJECTED\|BLOCKED", "retriesRemaining": N }`. |
| `getPinStatus` | `terminalId: String`, `slotId: int`, `pinType: String` | `String` (JSON) | Reads PIN status without requiring entry. Returns `{ "pinType": "...", "status": "ACTIVE\|BLOCKED", "retriesRemaining": N }`. |

---

## `DnsServiceDiscoveryManagement` — `quarkus-ldap-proxy-server-extension`

**ObjectName**: `de.servicehealtherx:module=quarkus-ldap-proxy-server-extension,name=DnsServiceDiscoveryManagement`
**MBean interface**: `de.servicehealtherx.quarkus.ldap.proxy.server.runtime.jmx.DnsServiceDiscoveryManagementMBean`

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `triggerServiceDiscovery` | `domain: String` | `String` (JSON array) | Re-executes DNS-SD PTR query for the given TI domain (FR-125). Empty/null `domain` re-discovers all configured zones. Returns `[{ "service": "_ldap._tcp.vzd...", "host": "...", "port": 636, "priority": 0, "weight": 0 }]`. |
| `getDiscoveredServices` | — | `String` (JSON object) | Snapshot of all cached DNS-SD results per configured zone. |

---

## `SignatureServiceManagement` — `crypto-services-lib`

**ObjectName**: `de.servicehealtherx:module=crypto-services-lib,name=SignatureServiceManagement`
**MBean interface**: `de.servicehealtherx.crypto.services.jmx.SignatureServiceManagementMBean`

All binary parameters and return values are Base64-encoded (standard, no line breaks).
All operations emit an audit log entry identical to SOAP-layer invocations (FR-016).

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `sign` | `alias: String`, `algorithm: String`, `dataBase64: String` | `String` (Base64) | Invokes `CryptoProvider.sign()`. Returns DER signature in Base64. |
| `verify` | `alias: String`, `algorithm: String`, `dataBase64: String`, `signatureBase64: String` | `boolean` | Invokes `CryptoProvider.verify()`. |
| `externalAuthenticate` | `alias: String`, `signatureType: String`, `hashBase64: String` | `String` (Base64) | Invokes the ExternalAuthenticate hash-signing flow. Returns ECDSA/RSA signature in Base64. |
| `getSignatureMode` | — | `String` | Current comfort-signature mode (`"ACTIVE"`, `"INACTIVE"`, or `"NOT_IMPLEMENTED"` for deferred ops per FR-157). |

---

## `EncryptionServiceManagement` — `crypto-services-lib`

**ObjectName**: `de.servicehealtherx:module=crypto-services-lib,name=EncryptionServiceManagement`
**MBean interface**: `de.servicehealtherx.crypto.services.jmx.EncryptionServiceManagementMBean`

All binary parameters and return values are Base64-encoded.
All operations emit audit log entries per FR-016.

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `encryptDocument` | `alias: String`, `algorithm: String`, `documentBase64: String`, `recipientCertBase64: String` | `String` (Base64) | Invokes hybrid encryption. `recipientCertBase64` is the DER-encoded X.509 recipient cert in Base64; for multi-recipient, pass a JSON array of Base64 DER certs. Returns the encrypted document in Base64. |
| `decryptDocument` | `alias: String`, `ciphertextBase64: String` | `String` (Base64) | Invokes hybrid decryption. Returns plaintext document in Base64. |

---

## `CertificateServiceManagement` — `crypto-services-lib`

**ObjectName**: `de.servicehealtherx:module=crypto-services-lib,name=CertificateServiceManagement`
**MBean interface**: `de.servicehealtherx.crypto.services.jmx.CertificateServiceManagementMBean`

### Operations

| Operation | Parameters | Returns | Description |
|-----------|-----------|---------|-------------|
| `readCertificate` | `alias: String`, `certRef: String`, `crypt: String` | `String` (Base64) | Reads the X.509 certificate from the key source. `certRef` ∈ `{ "C.AUT", "C.OSIG" }`; `crypt` ∈ `{ "ECC", "RSA" }`. Returns DER-encoded certificate in Base64. |
| `verifyCertificate` | `certificateBase64: String` | `String` (JSON) | Runs TI PKI verification (OCSP, chain, temporal). Returns `{ "result": "VALID\|INVALID\|INCONCLUSIVE", "detail": "..." }`. |

---

## Security Summary (FR-232)

| Rule | Value |
|------|-------|
| MBeanServer | Platform MBeanServer only (`ManagementFactory.getPlatformMBeanServer()`) |
| Remote JMX connector | Disabled in production |
| JVM flag | `-Dcom.sun.jndi.rmi.object.trustURLCodebase=false` |
| Hawtio RBAC | All mutating operations require `admin` role |
| State-modifying operations | `reloadTsl`, `connect`, `disconnect`, `verifyPin`, `exportBackup`, `importBackup`, `reloadKeyStore`, `reloadAllKeyStores`, `triggerDiscovery`, `triggerServiceDiscovery` |
