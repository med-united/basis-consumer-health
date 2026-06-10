# Quickstart Validation Guide: P12 CryptoProvider

**Feature**: `003-p12-crypto-provider` | **Date**: 2026-06-10

This guide describes how to validate that `P12CryptoProvider` is correctly implemented using the Zeta SMC-B test keystores. The test P12 files are already committed at `crypto-p12-lib/src/test/resources/certs/` and are discovered automatically by `P12CertScanner` — no per-cert configuration is needed.

---

## Prerequisites

- Java 21 JDK installed
- Maven 3.9+ installed
- Test keystores present at:
  ```
  crypto-p12-lib/src/test/resources/certs/
  └── 80276688311000300107-Zeta/
      ├── 80276688311000300107-Zeta-C_SMCB_AUT_E256_X509/
      │   ├── *.p12          (EC key, password: 00)
      │   └── password.txt
      ├── 80276688311000300107-Zeta-C_SMCB_ENC_E256_X509/
      │   ├── *.p12          (EC key, password: 00)
      │   └── password.txt
      └── 80276688311000300107-Zeta-C_SMCB_OSIG_E256_X509/
          ├── *.p12          (EC key, password: 00)
          └── password.txt
  ```

---

## Test Configuration

`crypto-p12-lib/src/test/resources/application.properties` configures only the certs directory. No per-cert entries are needed:

```properties
# Path to the test certs folder (relative to project working directory)
quarkus.crypto.p12.certs-dir=src/test/resources/certs
```

`P12CertScanner` discovers all `.p12` files recursively and reads `password.txt` siblings automatically.

The derived aliases for the Zeta test keystores will follow the normalization rule (lowercase, dots→hyphens, slashes preserved):
- `p12/80276688311000300107-zeta/80276688311000300107-zeta-c-smcb-aut-e256-x509/80276688311000300107-zeta-c-smcb-aut-e256-x509`
- (similarly for ENC and OSIG)

Tests reference these by alias or use `P12CryptoProvider.listKeyStores()` to discover them dynamically.

---

## Run All Tests

```bash
mvn -pl crypto-p12-lib test
```

Expected: All tests green, no `UnsupportedOperationException` from any provider method other than `encrypt`.

---

## Validation Scenarios

### 1. Startup — all three keystores load successfully

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#listKeyStores_returns_three_descriptors_all_available`

**Expected**: `listKeyStores()` returns exactly 3 descriptors, each with `AVAILABLE` status. `getAvailabilities()` returns a map with 3 entries.

**Verifies**: FR-001, FR-002, FR-006, SC-002

---

### 2. Sign with AUT key — ECDSA auto-detected

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#sign_with_aut_key_auto_detects_ecdsa`

**Expected**:
- `sign()` called with `request.algorithm = null`
- Returns non-empty signature bytes
- `result.algorithm` equals `"SHA256withECDSA"`
- `result.certificate` is the AUT leaf certificate
- Verifying the signature against `result.certificate.getPublicKey()` returns `true`

**Verifies**: FR-015, SC-001

---

### 3. Sign with explicit algorithm override

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#sign_with_explicit_algorithm_uses_it`

**Expected**:
- `sign()` called with `request.algorithm = "SHA256withECDSA"` (EC key, explicit match)
- Returns valid signature; `result.algorithm` echoes `"SHA256withECDSA"`

**Verifies**: FR-015, User Story 1 scenario 4

---

### 4. Verify — valid and tampered signature

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#verify_valid_signature_returns_true`
**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#verify_tampered_signature_returns_false`

**Expected**: First returns `true`; second returns `false` (no exception thrown).

**Verifies**: FR-004, SC-001

---

### 5. `encrypt` and `decrypt` throw UnsupportedOperationException

**Commands**:
```
mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#encrypt_throws_unsupported
mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#decrypt_throws_unsupported
```

**Expected**: Both throw `UnsupportedOperationException` with a message referencing the ECIES feature (gemSpec_Krypt §4.7). TI ECIES (ECDH + HKDF/SHA-256/X9.63 + AES-256-CBC + CMAC) is implemented in a dedicated future feature.

**Verifies**: FR-009, SC-003

---

### 7. Error isolation — missing password.txt does not affect other adapters

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#missing_password_txt_marks_error_others_available`

**Expected**: Provider starts; descriptor for the alias whose `password.txt` is missing is `ERROR`; other descriptors are `AVAILABLE`; `sign()` on a valid alias succeeds.

**Verifies**: FR-002, FR-010, SC-004

---

### 8. Unknown alias throws IllegalArgumentException

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#sign_unknown_alias_throws_illegal_argument`

**Expected**: `IllegalArgumentException` with descriptive message.

**Verifies**: FR-004

---

### 9. Wrong SourceType alias throws IllegalArgumentException

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#sign_pcsc_alias_throws_illegal_argument`

**Expected**: `IllegalArgumentException` noting this provider handles `p12/` aliases only.

**Verifies**: FR-005

---

### 10. Passwords do not appear in logs

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#load_failure_password_not_in_log`

**Expected**: Log output captured during a failed load does not contain the password string `00`.

**Verifies**: FR-011, SC-005

---

### 11. MBean upload — new cert available without restart

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CertManagementTest#upload_valid_p12_registers_alias_as_available`

**Expected**:
- Before upload: `listKeyStores()` returns 3 entries
- Call `uploadCertificate(alias, p12Bytes, "00")`
- After upload: `listKeyStores()` returns 4 entries; new alias is `AVAILABLE`
- `sign()` with the new alias returns a valid signature

**Verifies**: FR-013, SC-006

---

### 12. MBean upload — invalid P12 rejected, nothing written

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CertManagementTest#upload_invalid_p12_throws_no_file_written`

**Expected**: `IllegalArgumentException` thrown; no file written to certs dir; `listKeyStores()` count unchanged.

**Verifies**: FR-014

---

## Build Verification

```bash
# Full module build including tests
mvn -pl crypto-p12-lib verify

# From repo root (all modules)
mvn verify
```

Both commands MUST succeed with zero test failures.
