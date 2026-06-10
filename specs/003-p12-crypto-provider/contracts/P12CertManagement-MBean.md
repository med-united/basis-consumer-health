# Contract: P12CertManagement JMX MBean

**Feature**: `003-p12-crypto-provider` | **Date**: 2026-06-10

JMX object name: `de.servicehealtherx:module=crypto-p12-lib,name=P12CertManagement`

Interface: `de.servicehealtherx.crypto.p12.P12CertManagementMBean`

---

## `uploadCertificate(String alias, byte[] p12Data, String password): void`

### Purpose
Upload a new PKCS#12 certificate to the running application without restart. The cert is persisted to the configured certs directory and immediately loaded as a new `P12KeyStoreAdapter`.

### Pre-conditions
- `alias` conforms to `KeyAlias` format `p12/<path>` (e.g. `p12/smcb/new-aut`)
- `p12Data` is a non-null, non-empty byte array representing a valid PKCS#12 container
- `password` is the password required to open `p12Data`

### Execution sequence
1. **Validate alias format** — construct `new KeyAlias(alias)`. Throws `IllegalArgumentException` on invalid format or non-P12 source type.
2. **Validate P12 data** — open `p12Data` as a PKCS12 `KeyStore` with `password`. If this fails, throw `IllegalArgumentException("Invalid P12 data or wrong password")`. No file is written.
3. **Compute target paths**:
   - Strip `p12/` prefix from alias to get relative path (e.g., `smcb/new-aut`)
   - P12 target: `<certs-dir>/smcb/new-aut.p12`
   - Password target: `<certs-dir>/smcb/password.txt` (sibling of the P12 file)
4. **Create directories** — `Files.createDirectories(targetP12.getParent())`
5. **Write P12 atomically** — write to `<target>.tmp`, then `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`
6. **Write password file** — write trimmed password to `password.txt` sibling (UTF-8, newline-terminated)
7. **Register adapter**:
   - Construct `P12KeyStoreAdapter(alias, targetP12Path, password, password)`
   - Call `adapter.engineLoad(null, null)`
   - Add to `P12CryptoProvider`'s `CopyOnWriteArrayList` and `ConcurrentHashMap` under the write lock
   - If the alias already exists, replace the existing entry
8. **Return** void on success

### Post-conditions (success)
- `P12CryptoProvider.listKeyStores()` includes the new alias with `AVAILABLE` state
- `P12CryptoProvider.sign(...)` with the new alias succeeds
- Files `<certs-dir>/<path>.p12` and `<certs-dir>/<path-parent>/password.txt` exist on disk
- A restart will re-discover the cert via `P12CertScanner`

### Error behaviour
| Condition | Throws | Side effects |
|-----------|--------|--------------|
| Invalid `alias` format | `IllegalArgumentException` | None |
| `p12Data` cannot be opened with `password` | `IllegalArgumentException` | No file written |
| Filesystem write failure | `RuntimeException` wrapping `IOException` | Partial write possible; adapter NOT registered |
| Adapter `engineLoad` failure | `RuntimeException` | File written to disk; adapter in ERROR state and registered |

### Security note
`password` MUST NOT appear in any log message or exception message at any log level.

---

## `listCertificates(): String`

### Purpose
Return a JSON array of alias strings for all P12 keystores currently managed by `P12CryptoProvider`.

### Returns
JSON array of alias strings (type `String`), e.g.:
```json
["p12/zeta/smcb-aut","p12/zeta/smcb-enc","p12/zeta/smcb-osig"]
```

### Post-conditions
- Return value reflects the state at the moment of the call
- Aliases in `ERROR` state are included (operators need to see broken entries)
- Result is always valid JSON (empty array `[]` if no adapters loaded)
