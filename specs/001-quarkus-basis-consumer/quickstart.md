# Quickstart Validation Guide: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Branch**: `001-quarkus-basis-consumer` | **Date**: 2026-06-05

Scenarios 1–6 cover the cryptographic provider layer. Additional scenarios for SOAP services and LDAP proxy are in the plan's quickstart section.

This guide proves the feature works end-to-end using only software tools (no physical HSM or card required). For contract details, see [contracts/](contracts/). For entity definitions, see [data-model.md](data-model.md).

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 21 LTS | Build and run |
| Maven | 3.9+ | Build |
| SoftHSM2 | 2.6+ | PKCS#11 emulator (install via `apt install softhsm2` or `brew install softhsm`) |
| pcscd | any | PC/SC daemon (install via `apt install pcscd`; required for PC/SC scenario only) |
| jnasmartcardio | bundled in test scope | Virtual PC/SC reader for unit tests |
| Netty mock server | bundled in test scope | In-process TCP stub for SICCT tests |

---

## Scenario 1: P12 Software Keystore Signing

**Goal**: Verify that a signing request through the `CryptoProvider` using a P12-backed alias produces a valid ECDSA signature.

### Setup

```bash
# Generate a test ECC P12 keystore
keytool -genkeypair -alias test-ecc -keyalg EC -keysize 256 \
  -keystore /tmp/test.p12 -storetype PKCS12 -storepass changeit \
  -validity 365 -dname "CN=Test, O=Test"

# Configure the application
cat >> src/test/resources/application.properties << 'EOF'
quarkus.crypto.p12[0].alias=p12/test-ecc
quarkus.crypto.p12[0].path=/tmp/test.p12
quarkus.crypto.p12[0].password=changeit
EOF
```

### Run

```bash
mvn test -pl crypto-provider -Dtest=P12CryptoProviderTest
```

### Expected outcome

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

Test assertions:
1. `CryptoProvider.getAvailability(alias("p12/test-ecc"))` returns `AVAILABLE`
2. `CryptoProvider.sign(request)` returns a non-empty byte array
3. `Signature.getInstance("SHA256withECDSA").verify(result.result)` returns `true` using the certificate from `result.certificate`
4. No log lines contain the string `changeit` or any key material (assert via log capture)

---

## Scenario 2: PKCS#11 HSM (SoftHSM2) Signing

**Goal**: Verify ECC signing via SunPKCS11 provider backed by SoftHSM2.

### Setup

```bash
# Initialize a SoftHSM2 token
softhsm2-util --init-token --slot 0 --label CI --pin 1234 --so-pin 0000

# Generate an ECC key in the token
keytool -genkeypair -alias pkcs11-ecc -keyalg EC -keysize 256 \
  -storetype PKCS11 -providerName SunPKCS11-SoftHSM \
  -storepass 1234 -dname "CN=SoftHSM CI, O=Test"

# Configure the application
cat >> src/test/resources/application.properties << 'EOF'
quarkus.crypto.pkcs11[0].alias=pkcs11/softhsm-ci
quarkus.crypto.pkcs11[0].name=SoftHSM
quarkus.crypto.pkcs11[0].library-path=/usr/lib/softhsm/libsofthsm2.so
quarkus.crypto.pkcs11[0].token-pin=1234
quarkus.crypto.pkcs11[0].slot-list-index=0
EOF
```

### Run

```bash
mvn test -pl crypto-provider -Dtest=Pkcs11CryptoProviderTest -Ppkcs11-ci
```

### Expected outcome

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

Test assertions:
1. Provider reports `AVAILABLE` after SunPKCS11 registers successfully
2. Sign operation returns a valid ECDSA signature verifiable against the cert
3. Calling `KeyStore.getKey("pkcs11-ecc", null)` on the PKCS#11 KeyStore returns a `PrivateKey` whose `getEncoded()` returns `null` (key not extractable)
4. Two concurrent sign requests for the same alias complete without `CKR_SESSION_COUNT` errors

---

## Scenario 3: SICCT Networked Card Terminal (Mock)

**Goal**: Verify that the SICCT Quarkus extension establishes a TCP/IP client connection to a mock terminal, detects a simulated card insertion, and successfully routes a signing request.

### Setup

No external tools required; the test uses an in-process Netty-based mock SICCT server.

```bash
# Run the SICCT extension tests
mvn test -pl sicct-quarkus-extension/runtime -Dtest=SicctTerminalManagerTest
```

### Expected outcome

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

Test sequence (verified in `SicctTerminalManagerTest`):
1. `SicctTerminalManager` starts and attempts TCP connection to `localhost:<dynamicPort>`
2. Mock server accepts; extension logs `[SICCT] terminal-1 CONNECTED`
3. Mock server sends simulated card-insertion event for slot 0
4. Within 5 seconds: `CryptoProvider.listAliases()` includes `sicct/terminal-1-slot0`
5. Sign request for `sicct/terminal-1-slot0` → mock server responds with test signature bytes → `CryptoOperationResult.result` is non-empty
6. Test closes mock server → within 10 seconds: `CryptoProvider.getAvailability("sicct/terminal-1-slot0")` returns `UNAVAILABLE`
7. Mock server restarts → within 15 seconds: alias returns to `AVAILABLE`

---

## Scenario 4: Simultaneous All-Four-Provider Operation

**Goal**: Prove all four key source types operate concurrently without interference.

### Setup

Combine setups from Scenarios 1 and 2. PC/SC scenario uses the `jnasmartcardio` virtual reader registered as `QuarkusTestResource`. SICCT uses the in-process mock server.

```bash
mvn test -pl crypto-provider -Dtest=CryptoProviderIntegrationTest -Ppkcs11-ci
```

### Expected outcome

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

Test sequence:
1. Start: all four providers configured; all four aliases listed as AVAILABLE
2. Submit four concurrent signing requests (one per alias) via `CompletableFuture`
3. All four `CompletableFuture`s complete within 3 seconds
4. All four signatures are valid and each verifies against its respective certificate
5. Each signature is distinct (no cross-provider key confusion)

---

## Scenario 5: Key Source Removal Hot-Reload (P12)

**Goal**: Verify that removing a P12 key source from config takes effect within 60 seconds without restarting.

### Run

```bash
mvn test -pl crypto-provider -Dtest=P12HotReloadTest
```

### Expected outcome

1. Alias `p12/test-ecc` starts as AVAILABLE
2. Test removes the key source config entry and triggers config reload
3. Within 60 seconds: `getAvailability("p12/test-ecc")` returns `UNAVAILABLE`
4. Sign request for `p12/test-ecc` returns `KeySourceUnavailableException`
5. Other aliases (P12, PKCS#11, etc.) remain AVAILABLE throughout

---

## Scenario 6: Health Check Endpoint

**Goal**: Verify the SmallRye Health readiness endpoint reflects provider availability.

### Run

```bash
# Start the app with all four providers configured
mvn quarkus:dev -pl app

# In another terminal:
curl -s http://localhost:8080/q/health/ready | jq .
```

### Expected outcome (all healthy)

```json
{
  "status": "UP",
  "checks": [{
    "name": "crypto-provider",
    "status": "UP",
    "data": { "totalAliases": 4, "availableAliases": 4 }
  }, {
    "name": "sicct-terminals",
    "status": "UP",
    "data": { "connectedTerminals": 1 }
  }]
}
```

Simulate a SICCT terminal disconnection (stop mock server or unplug network). Within 10 seconds:

```json
{
  "status": "DOWN",
  "checks": [{
    "name": "crypto-provider",
    "status": "DOWN",
    "data": { "totalAliases": 4, "availableAliases": 3,
              "unavailable": ["sicct/terminal-1-slot0"] }
  }]
}
```
