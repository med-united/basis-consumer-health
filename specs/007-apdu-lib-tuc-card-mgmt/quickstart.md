# Quickstart & Validation Guide: apdu-lib Module

**Feature Branch**: `007-apdu-lib-tuc-card-mgmt`  
**Module**: `apdu-lib` (`de.servicehealtherx:apdu-lib`)  
**Last Updated**: 2026-06-09  
**Spec Reference**: gemSpec_Kon V5.27.0, Section 4.1.5.4

---

## Purpose

This guide describes how to build, test, and validate the `apdu-lib` module end-to-end using only the unit test suite (no physical card hardware required). Each validation scenario uses a mock `CardTerminalTransport` that returns pre-scripted `ResponseApdu` values, enabling deterministic, repeatable verification of all TUC logic.

---

## 1. Prerequisites

| Requirement | Minimum Version | Verification Command |
|-------------|-----------------|----------------------|
| Java JDK | 21 | `java -version` |
| Apache Maven | 3.9.0 | `mvn --version` |
| Git | any recent | `git --version` |

The parent project must have been checked out and the upstream modules must be available in either the local Maven repository or the reactor build:

- `de.servicehealtherx:sicct-lib` (provides `CardHandle`, `ISO7816`, `SICCT`, `SicctCodec`)
- `de.servicehealtherx:crypto-lib` (provides `CryptoProvider`, BouncyCastle integration)

If these have not been installed yet, install them first:

```bash
mvn -pl sicct-lib,crypto-lib clean install -DskipTests
```

---

## 2. Building the Module

Compile and package the `apdu-lib` module without running tests:

```bash
mvn -pl apdu-lib clean install -DskipTests
```

**Expected output**

```
[INFO] --- maven-jar-plugin:...:jar (default-jar) @ apdu-lib ---
[INFO] Building jar: .../apdu-lib/target/apdu-lib-1.0.0-SNAPSHOT.jar
[INFO] BUILD SUCCESS
```

If the build fails with a compilation error, confirm that the `apdu-lib` module declares the correct dependencies in its `pom.xml`:

```xml
<dependency>
    <groupId>de.servicehealtherx</groupId>
    <artifactId>sicct-lib</artifactId>
</dependency>
<dependency>
    <groupId>de.servicehealtherx</groupId>
    <artifactId>crypto-lib</artifactId>
</dependency>
```

---

## 3. Running the Unit Test Suite

Execute the full test suite for the module:

```bash
mvn -pl apdu-lib test
```

All tests run in-process using mock transports. No card terminal, no SICCT daemon, and no network connection are required.

**Expected output**

```
[INFO] Tests run: NN, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

To run a single test class during development:

```bash
mvn -pl apdu-lib test -Dtest=TucKon012VerifyPinTest
```

To run all tests whose names match a TUC requirement pattern:

```bash
mvn -pl apdu-lib test -Dtest="*TucKon*"
```

---

## 4. Validation Scenario 1 — PIN Verify (TUC_KON_012)

**Requirement**: TIP1-A_4566  
**Test class**: `TucKon012VerifyPinTest`  
**Test method**: `test_TIP1_A_4566_verify_pin_returns_ok_for_correct_pin`

### What the test does

1. Constructs a mock `CardTerminalTransport` that returns `ResponseApdu(data=[], sw1=0x90, sw2=0x00)` for any `VERIFY` APDU (`INS = 0x20`).
2. Creates a `CardSession` with `CardType.EGK`, `CardVersion.GENERATION_2_0`, and a fresh `AuthState`.
3. Calls `TucKon012.verifyPin(cardSession, workplaceId="WS-01", pinRef=PinRef.PIN_CH)`.
4. Asserts that:
   - The returned `PinResult` is `PinResult.OK`.
   - `cardSession.authState().isPinVerified(PinRef.PIN_CH)` returns `true`.
   - The mock transport was called exactly once with an APDU whose `INS` byte is `0x20` and whose `P2` byte is `0x01` (PIN.CH reference).

### How to verify locally

```bash
mvn -pl apdu-lib test -Dtest="TucKon012VerifyPinTest#test_TIP1_A_4566_verify_pin_returns_ok_for_correct_pin"
```

**Console confirmation**

```
[INFO] Running de.servicehealtherx.apdu.tuc.TucKon012VerifyPinTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

---

## 5. Validation Scenario 2 — Read File (TUC_KON_202)

**Requirement**: TIP1-A_4573  
**Test class**: `TucKon202ReadFileTest`  
**Test method**: `test_TIP1_A_4573_read_file_returns_content_bytes`

### What the test does

1. Configures a mock transport with two responses in sequence:
   - First call (`SELECT`, `INS = 0xA4`): returns `ResponseApdu(data=[], sw1=0x90, sw2=0x00)`.
   - Second call (`READ BINARY`, `INS = 0xB0`): returns `ResponseApdu(data=[0x01, 0x02, 0x03], sw1=0x90, sw2=0x00)`.
2. Creates a `CardSession` with `AuthState` that already has `PinRef.PIN_CH` verified (pre-populated to simulate a preceding TUC_KON_012 call).
3. Calls `TucKon202.readFile(cardSession, fileIdentifier=0x2F01, offset=0, length=3)`.
4. Asserts that:
   - The returned `byte[]` equals `{0x01, 0x02, 0x03}`.
   - The mock transport was called twice (SELECT then READ BINARY).
   - No `TucException` was thrown.

### How to verify locally

```bash
mvn -pl apdu-lib test -Dtest="TucKon202ReadFileTest#test_TIP1_A_4573_read_file_returns_content_bytes"
```

**Console confirmation**

```
[INFO] Running de.servicehealtherx.apdu.tuc.TucKon202ReadFileTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

---

## 6. Validation Scenario 3 — Sign (TUC_KON_219)

**Requirement**: TIP1-A_4581  
**Test class**: `TucKon219SignTest`  
**Test method**: `test_TIP1_A_4581_sign_returns_signature_bytes`

### What the test does

1. Defines a dummy signature: `byte[] dummySignature = {0xDE, 0xAD, 0xBE, 0xEF}`.
2. Configures a mock transport with three responses in sequence:
   - First call (`MSE SET`, `INS = 0x22`): returns `ResponseApdu(data=[], sw1=0x90, sw2=0x00)` — sets the key reference.
   - Second call (`MSE SET`, `INS = 0x22`): returns `ResponseApdu(data=[], sw1=0x90, sw2=0x00)` — sets the algorithm.
   - Third call (`PSO:CDS`, `INS = 0x2A`): returns `ResponseApdu(data=dummySignature, sw1=0x90, sw2=0x00)`.
3. Creates a `CardSession` for an `HBA` card with `PIN_QES` already verified in `AuthState`.
4. Calls `TucKon219.sign(cardSession, pinRef=PinRef.PIN_QES, keyRef=KeyRef.C_QES, algorithmUsId=AlgorithmId.ECDSA_SHA256, dataToBeSigned=new byte[]{0x01})`.
5. Asserts that:
   - The returned `byte[]` equals `dummySignature`.
   - The mock transport was called exactly three times.
   - No `TucException` was thrown.

### How to verify locally

```bash
mvn -pl apdu-lib test -Dtest="TucKon219SignTest#test_TIP1_A_4581_sign_returns_signature_bytes"
```

**Console confirmation**

```
[INFO] Running de.servicehealtherx.apdu.tuc.TucKon219SignTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

---

## 7. Validation Scenario 4 — Card Reserved Error Handling (TUC_KON_023)

**Requirement**: TIP1-A_4571-03  
**Test class**: `TucKon023ReserveCardTest`  
**Test method**: `test_TIP1_A_4571_03_lock_already_locked_card_throws_4093`

### What the test does

1. Creates a `CardSession` that already has `lockOwner = "session-A"` (simulating a card reserved by another caller).
2. Calls `TucKon023.reserveCard(cardSession, doLock=true)` with caller identity `"session-B"`.
3. Asserts that:
   - A `TucException` is thrown.
   - `exception.getErrorCode()` equals `4093`.
   - `exception.getTucIdentifier()` equals `"TUC_KON_023"`.
   - The mock transport is never called (the error is raised before any APDU is sent).

### Why this matters

Error code 4093 is the gate that prevents data corruption when two functional modules attempt to operate on the same card simultaneously. This test verifies the precondition check fires before any card communication occurs.

### How to verify locally

```bash
mvn -pl apdu-lib test -Dtest="TucKon023ReserveCardTest#test_TIP1_A_4571_03_lock_already_locked_card_throws_4093"
```

**Console confirmation**

```
[INFO] Running de.servicehealtherx.apdu.tuc.TucKon023ReserveCardTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

---

## 8. Coverage Check

Generate a JaCoCo line coverage report for the module:

```bash
mvn -pl apdu-lib jacoco:report
```

The HTML report is written to:

```
apdu-lib/target/site/jacoco/index.html
```

Open it in a browser to inspect per-class and per-method coverage.

**Minimum coverage threshold**: 80% line coverage across the module (SC-002).

To enforce the threshold automatically as part of the build, the `apdu-lib` `pom.xml` should contain the following JaCoCo check goal:

```xml
<execution>
    <id>jacoco-check</id>
    <goals><goal>check</goal></goals>
    <configuration>
        <rules>
            <rule>
                <element>BUNDLE</element>
                <limits>
                    <limit>
                        <counter>LINE</counter>
                        <value>COVEREDRATIO</value>
                        <minimum>0.80</minimum>
                    </limit>
                </limits>
            </rule>
        </rules>
    </configuration>
</execution>
```

With this in place, running `mvn -pl apdu-lib verify` will fail the build if coverage falls below 80%.

---

## 9. Afo Traceability Check

Every test method that exercises a TUC requirement MUST reference its requirement ID in the method name, following the pattern:

```
test_<requirementId>_<description>
```

**Examples**:

```java
test_TIP1_A_4566_verify_pin_returns_ok_for_correct_pin()
test_TIP1_A_4573_read_file_returns_content_bytes()
test_TIP1_A_4581_sign_returns_signature_bytes()
test_TIP1_A_4571_03_lock_already_locked_card_throws_4093()
test_A_26067_start_card_session_assigns_session_id()
```

### Verifying traceability with grep

From the repository root, run:

```bash
grep -rn "void test_TIP1_A_\|void test_A_26" apdu-lib/src/test/java/
```

Each of the 27 TUC requirement IDs from `spec.md` must appear at least once in the output.

### Verifying traceability with the IDE

In IntelliJ IDEA or Eclipse, run the test suite with the test runner view open and filter by `test_TIP1` or `test_A_26` to confirm all requirement IDs are represented.

### Afo Test Matrix

Before release, populate the Afo_Testmatrix (in the `specs/007-apdu-lib-tuc-card-mgmt/` directory) with a row for each of the 27 TUC requirement IDs:

| Requirement ID | TUC | Test Class | Test Method |
|----------------|-----|------------|-------------|
| TIP1-A_4567 | TUC_KON_026 | `TucKon026ProvideCardSessionTest` | `test_TIP1_A_4567_...` |
| TIP1-A_4566 | TUC_KON_012 | `TucKon012VerifyPinTest` | `test_TIP1_A_4566_...` |
| ... | ... | ... | ... |
| A_26067 | TUC_KON_223 | `TucKon223StartCardSessionTest` | `test_A_26067_...` |

This table is a precondition for SC-007 (all 27 requirement IDs in the Afo_Testmatrix before release).

---

## 10. Integration into Parent Build

Confirm that adding `apdu-lib` to the reactor does not break any existing module:

```bash
mvn clean install
```

This builds all modules in dependency order. The `apdu-lib` module must not introduce dependency cycles or override any managed dependency version that another module relies on.

**Expected output**

```
[INFO] Reactor Summary for basis-consumer-parent ...:
[INFO]   api-telematik ........................ SUCCESS
[INFO]   crypto-lib ........................... SUCCESS
[INFO]   sicct-lib ............................ SUCCESS
[INFO]   apdu-lib ............................. SUCCESS
[INFO]   quarkus-sicct-extension .............. SUCCESS
[INFO]   crypto-services-lib .................. SUCCESS
[INFO]   quarkus-ldap-proxy-server-extension .. SUCCESS
[INFO]   consumer-soap-server ................. SUCCESS
[INFO]   konnektor-soap-server ................ SUCCESS
[INFO]   quarkus-server ....................... SUCCESS
[INFO] BUILD SUCCESS
```

If any downstream module fails after `apdu-lib` is added, check:

1. The `apdu-lib` `pom.xml` parent declaration points to `de.servicehealtherx:basis-consumer-parent`.
2. The `<module>apdu-lib</module>` entry in the root `pom.xml` is placed after `sicct-lib` and `crypto-lib`.
3. No transitive dependency version conflict is introduced (run `mvn dependency:tree -pl apdu-lib` to inspect).

---

## Quick Reference: All Validation Commands

```bash
# 1. Build without tests
mvn -pl apdu-lib clean install -DskipTests

# 2. Run full test suite
mvn -pl apdu-lib test

# 3. Run a single scenario
mvn -pl apdu-lib test -Dtest="TucKon012VerifyPinTest#test_TIP1_A_4566_verify_pin_returns_ok_for_correct_pin"

# 4. Coverage report
mvn -pl apdu-lib jacoco:report
open apdu-lib/target/site/jacoco/index.html

# 5. Afo traceability grep
grep -rn "void test_TIP1_A_\|void test_A_26" apdu-lib/src/test/java/

# 6. Full reactor build
mvn clean install
```
