# Quickstart Validation Guide: P12 CryptoProvider

**Feature**: `003-p12-crypto-provider` | **Date**: 2026-06-09

This guide describes how to validate that `P12CryptoProvider` is correctly implemented using the Zeta SMC-B test keystores.

---

## Prerequisites

- Java 21 JDK installed
- Maven 3.9+ installed
- Test keystores present in `crypto-p12-lib/src/test/resources/80276688311000300107-Zeta/`
  - `80276688311000300107-Zeta-C_SMCB_AUT_E256_X509/` (authentication key, password: `00`)
  - `80276688311000300107-Zeta-C_SMCB_ENC_E256_X509/` (encryption key, password: `00`)
  - `80276688311000300107-Zeta-C_SMCB_OSIG_E256_X509/` (signature key, password: `00`)

---

## Test Configuration

`crypto-p12-lib/src/test/resources/application.properties` must configure the three Zeta keystores:

```properties
quarkus.crypto.p12[0].alias=p12/smcb-aut
quarkus.crypto.p12[0].path=src/test/resources/80276688311000300107-Zeta/80276688311000300107-Zeta-C_SMCB_AUT_E256_X509/80276688311000300107-Zeta-C_SMCB_AUT_E256_X509.p12
quarkus.crypto.p12[0].keystore-password=00

quarkus.crypto.p12[1].alias=p12/smcb-enc
quarkus.crypto.p12[1].path=src/test/resources/80276688311000300107-Zeta/80276688311000300107-Zeta-C_SMCB_ENC_E256_X509/80276688311000300107-Zeta-C_SMCB_ENC_E256_X509.p12
quarkus.crypto.p12[1].keystore-password=00

quarkus.crypto.p12[2].alias=p12/smcb-osig
quarkus.crypto.p12[2].path=src/test/resources/80276688311000300107-Zeta/80276688311000300107-Zeta-C_SMCB_OSIG_E256_X509/80276688311000300107-Zeta-C_SMCB_OSIG_E256_X509.p12
quarkus.crypto.p12[2].keystore-password=00
```

---

## Run All Tests

```bash
mvn -pl crypto-p12-lib test
```

Expected: All tests green, no `UnsupportedOperationException` from the provider methods.

---

## Validation Scenarios

### 1. Startup — all three keystores load successfully

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#listKeyStores_returns_three_descriptors_all_available`

**Expected**: `listKeyStores()` returns exactly 3 descriptors, each with `AVAILABLE` status. `getAvailabilities()` map has 3 entries.

**Verifies**: FR-001, FR-004, FR-006, SC-002

---

### 2. Sign with AUT key

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#sign_with_aut_key_returns_valid_signature`

**Expected**:
- `sign()` returns a non-empty byte array
- The returned `certificate` is not null and has subject matching the AUT certificate CN
- Verifying the signature against the returned certificate's public key yields `true`

**Verifies**: FR-001, FR-002, SC-001, SC-003

---

### 3. Verify — valid and tampered signature

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#verify_valid_signature_returns_true`
**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#verify_tampered_signature_returns_false`

**Expected**: First returns `true`; second returns `false` (no exception).

**Verifies**: FR-002, SC-001

---

### 4. Decrypt with ENC key

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#decrypt_with_enc_key_recovers_plaintext`

**Expected**: Test encrypts a known plaintext with the ENC certificate's public key, then calls `decrypt`. Returned bytes match original plaintext.

**Verifies**: FR-002, SC-001, SC-003

---

### 5. `encrypt` throws UnsupportedOperationException

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#encrypt_throws_unsupported`

**Expected**: `UnsupportedOperationException` is thrown with message containing "certificate's public key".

**Verifies**: FR-007, SC-003

---

### 6. Error isolation — bad path does not affect other adapters

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#bad_path_marks_error_other_adapters_available`

**Expected**: Provider starts; descriptor for bad-path alias is `ERROR`; descriptors for valid aliases are `AVAILABLE`; `sign` still works on valid aliases.

**Verifies**: FR-008, SC-004

---

### 7. Unknown alias throws IllegalArgumentException

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#sign_unknown_alias_throws_illegal_argument`

**Expected**: `IllegalArgumentException` with descriptive message.

**Verifies**: FR-002, FR-003

---

### 8. Wrong SourceType alias throws IllegalArgumentException

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#sign_pcsc_alias_throws_illegal_argument`

**Expected**: `IllegalArgumentException` noting this provider handles `p12/` aliases only.

**Verifies**: FR-003

---

### 9. Passwords do not appear in logs

**Command**: `mvn -pl crypto-p12-lib test -Dtest=P12CryptoProviderTest#load_failure_password_not_in_log`

**Expected**: Log output captured during a failed load does not contain the password string `00`.

**Verifies**: FR-009, SC-005

---

## Build Verification

```bash
# Full module build including tests
mvn -pl crypto-p12-lib verify

# From repo root (all modules)
mvn verify
```

Both commands MUST succeed with zero test failures and zero `UnsupportedOperationException` from the P12 provider.
