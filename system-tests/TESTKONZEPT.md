# Testkonzept — Basis-Consumer SOAP-Basisdienste (System-Tests)

**Produkttyp:** Basis-Consumer (PTV 1.10.2)
**Prüfgegenstand (SUT):** `consumer-soap-server` — Zertifikats-, Verschlüsselungs- und Signaturdienst
**Teststufe:** System-/Black-Box-Test (SOAP über HTTP, ohne generierte Stubs)
**Qualitätsmodell:** ISO/IEC 25000 (SQuaRE), insbesondere ISO/IEC 25010 (Produktqualität) und der
Bewertungsprozess nach ISO/IEC 25040
**Anforderungsbasis:** `afo-matrix/gemProdT_Basis-Consumer_PTV_1.10.2-0_V1.0.0.xlsx`
**Rückverfolgbarkeit:** gematik **AfoReporter** (`@Afo`-Annotation)

---

## 1. Zweck und Geltungsbereich

Dieses Testkonzept beschreibt die automatisierten System-Tests für die SOAP-Basisdienste des
Basis-Consumers. Die Tests treiben einen **laufenden** Server über handgeschriebene SOAP-1.1-
Envelopes an (kein generierter Client, keine Quarkus-Laufzeit), damit der Test exakt das abbildet,
was ein externes Primärsystem auf die Leitung legt.

Im Geltungsbereich sind die drei an der Clientsystemschnittstelle exponierten Basisdienste:

| Dienst | Endpunkt (`@CXFEndpoint`) | WSDL-Namensraum | Operationen |
|---|---|---|---|
| CertificateService | `/consumer/CertificateService` | `…/CertificateService/WSDL/v3.0` | `ReadCertificate`, `VerifyCertificate` |
| EncryptionService  | `/consumer/EncryptionService`  | `…/EncryptionService/WSDL/v3.0`  | `EncryptDocument`, `DecryptDocument` |
| SignatureService   | `/consumer/SignatureService`   | `…/SignatureService/WSDL/v3.2`   | `SignDocument`, `SignPlain`, `ExternalAuthenticate`, `VerifyDocument` |

Nicht im Geltungsbereich: KOM-LE/KIM (eigenständiger `openkim-server`), Namens-/DNS-Dienste,
Konnektor-Kompatibilitätsendpunkte (separat in `EPrescriptionSoapFlowTest` /
`KimEncryptionSoapFlowTest`).

## 2. Methodik (ISO/IEC 25000)

Die Test-Suite folgt dem Bewertungsprozess nach **ISO/IEC 25040** und strukturiert die Testfälle
nach den Produktqualitätsmerkmalen der **ISO/IEC 25010**. Jeder Testfall ist über die `@Afo`-
Annotation auf eine oder mehrere gematik-Anforderungen rückverfolgbar — das schließt den
Regelkreis *Anforderung → Messung → Bewertung → Bericht*.

### 2.1 Abbildung ISO/IEC 25010 ↔ gematik-Prüfverfahren

| ISO/IEC 25010 Merkmal | Untermerkmal | gematik-Prüfverfahren | System-Test-Abdeckung |
|---|---|---|---|
| **Functional Suitability** | Completeness, Correctness, Appropriateness | Funktionale Eignung „Produkttest“ | ✅ Kern — Operationen aller drei Basisdienste |
| **Security** | Integrity, Confidentiality, Accountability | Sicherheitstechnische Eignung | ✅ Fault-Leakage, Integritätsprüfung (Tamper) |
| **Reliability** | Maturity, Fault Tolerance | gemKPT_Test | ✅ Negativpfade, definierte Fehler statt Absturz |
| **Compatibility** | Interoperability | WSDL-/Schemakonformität | ✅ WSDL-Veröffentlichung + Namensräume |
| **Performance Efficiency** | Time Behaviour | gemSpec_Perf | ◻ optional (Latenzbudget) — derzeit nicht umgesetzt |
| Usability / Maintainability / Portability | — | Herstellererklärung | ✗ nicht system-testbar (Erklärung) |

## 3. Testarchitektur

```text
system-tests/
├── TESTKONZEPT.md                         # dieses Dokument
├── afo/requirements.json                  # AfoReporter-Anforderungen (aus der afo-matrix erzeugt)
└── src/test/java/de/servicehealtherx/systemtests/consumer/
    ├── support/
    │   ├── ConsumerNamespaces.java         # NS, Endpunkte, SOAPAction-Konstanten (aus WSDL/XSD)
    │   ├── ConsumerEnvelopes.java          # die 8 SOAP-Request-Builder
    │   ├── ConsumerSoapClient.java         # HTTP-POST/GET, Erreichbarkeit, Assertions
    │   └── ConsumerSystemTest.java         # Basisklasse: Skip wenn kein Server erreichbar
    ├── functional/                         # ISO 25010 Functional Suitability
    │   ├── CertificateServiceTest.java
    │   ├── EncryptionServiceTest.java
    │   └── SignatureServiceTest.java
    ├── security/                           # ISO 25010 Security
    │   ├── SoapFaultLeakageTest.java
    │   └── CryptoRoundTripIntegrityTest.java
    ├── reliability/                        # ISO 25010 Reliability
    │   └── NegativePathTest.java
    └── interoperability/                   # ISO 25010 Compatibility/Interoperability
        └── WsdlConformanceTest.java
```

**Skip-Verhalten (deterministisch & CI-sicher):**
- Ist **kein Server** erreichbar, überspringt sich die gesamte Klasse via JUnit-Assumption
  (`ConsumerSystemTest#serverMustBeReachable`).
- Benötigt eine Operation **Karten-/Schlüsselmaterial**, das in der Umgebung fehlt, antwortet der
  Consumer mit einem gematik-SOAP-Fault; der betroffene funktionale Testfall überspringt sich dann
  (`assumeFalse(isSoapFault(...))`), statt fehlzuschlagen.
- Security-/Reliability-Negativtests **erwarten** einen Fault und laufen daher in jeder Umgebung mit
  laufendem Server.

## 4. Testfall-Katalog mit AFO-Rückverfolgbarkeit

| Testfall (Methode) | ISO 25010 | Dienst/Operation | AFO(s) | Bestehenskriterium |
|---|---|---|---|---|
| `CertificateServiceTest#readCertificate_returnsX509Certificate` | Functional | ReadCertificate | A_24782-02, A_17408 | HTTP 200, `X509Certificate` vorhanden |
| `CertificateServiceTest#verifyCertificate_reportsResult` | Functional | VerifyCertificate | A_17429-02 | `VerificationResult ∈ {VALID,INVALID,INCONCLUSIVE}` |
| `EncryptionServiceTest#encryptThenDecrypt_recoversPlaintext` | Functional | Encrypt→DecryptDocument | A_17466, A_17467, A_17477, A_17510-05, A_17515-03 | Klartext byte-identisch zurückgewonnen |
| `SignatureServiceTest#signDocument_thenVerifyDocument` | Functional | SignDocument + VerifyDocument | A_17517, A_17523, A_17525-03/-04, A_17526-03, A_17577 | Signatur erzeugt; `HighLevelResult` gültig |
| `SignatureServiceTest#signPlain_returnsSignature` | Functional | SignPlain | A_17518 | `SignatureObject`/`Base64Signature` vorhanden |
| `SignatureServiceTest#externalAuthenticate_signsHash` | Functional | ExternalAuthenticate | A_17518, A_17578-03/-04 | `Base64Signature` über SHA-256-Hash |
| `SoapFaultLeakageTest#failingOperation_returnsCleanGematikFault` | Security | alle (erzwungener Fehler) | A_15237, GS-A_3796, GS-A_4547 | SOAP-Fault ohne Stacktrace/Schlüssel/PIN |
| `SoapFaultLeakageTest#fault_carriesStructuredGematikError` | Security | VerifyCertificate (Fehler) | GS-A_4547 | strukturierte Fehlerangabe (Reason/Error) |
| `CryptoRoundTripIntegrityTest#tamperedCiphertext_isRejected` | Security | DecryptDocument | A_17467 | manipuliertes CMS wird abgelehnt; kein Klartext |
| `NegativePathTest#unknownCardHandle_yieldsDefinedFault` | Reliability | DecryptDocument | GS-A_4547 | definierter Fault statt Absturz |
| `NegativePathTest#malformedEnvelope_yieldsFaultAndServerStaysUp` | Reliability | CertificateService | GS-A_4547 | Fehlerantwort; Server bleibt erreichbar |
| `NegativePathTest#wrongSoapAction_handledGracefully` | Reliability | CertificateService | GS-A_4547 | Antwort; Server bleibt erreichbar |
| `WsdlConformanceTest#certificateService_publishesWsdl` | Interoperability | CertificateService `?wsdl` | A_17408 | WSDL mit korrektem Zielnamensraum |
| `WsdlConformanceTest#encryptionService_publishesWsdl` | Interoperability | EncryptionService `?wsdl` | A_17477 | WSDL mit korrektem Zielnamensraum |
| `WsdlConformanceTest#signatureService_publishesWsdl` | Interoperability | SignatureService `?wsdl` | A_17523 | WSDL mit korrektem Zielnamensraum |

## 5. Rückverfolgbarkeit & Reporting (AfoReporter)

Die Verknüpfung Testfall ↔ Anforderung erfolgt mit der gematik-`@Afo`-Annotation
(`de.gematik.idp.tests.Afo`, Maven `de.gematik.idp.aforeporter:aforeporter`). Mehrere AFOs pro
Testmethode sind als wiederholte `@Afo` erlaubt.

```java
@Test
@Afo("A_17517")
@Afo("A_17577")
void signDocument_thenVerifyDocument() { ... }
```

Die Anforderungstexte liegen in [`afo/requirements.json`](afo/requirements.json), erzeugt aus der
`afo-matrix`. Der AfoReporter rendert daraus zusammen mit den Surefire-Testergebnissen einen
Abdeckungsbericht:

```bash
# 1) Tests gegen einen laufenden Server ausführen (erzeugt target/surefire-reports/*.xml)
mvn -pl system-tests test

# 2) AfoReporter-Bericht erzeugen (aforeporter*.jar separat beziehen)
java -jar aforeporter.jar \
     -tr system-tests/src/test/java \
     -rr system-tests/target/surefire-reports \
     -f  system-tests/afo/requirements.json \
     -o  system-tests/target/site/aforeport.html
```

Die Spalten *Testfall-ID* / *Testergebnis (OK/NOK)* der `afo-matrix` lassen sich aus diesem Bericht
befüllen.

## 6. Ausführung

```bash
# Server starten (bedient /ws/consumer/*)
mvn -pl quarkus-server quarkus:dev

# System-Tests ausführen
mvn -pl system-tests test \
    -Dsystemtest.base.url=http://localhost:8080/ws \
    -Dsystemtest.card.handle=card-1
```

| System-Property | Default | Zweck |
|---|---|---|
| `systemtest.base.url`   | `http://localhost:8080/ws` | Basis-URL der CXF-Endpunkte |
| `systemtest.card.handle`| `card-1` | Kartenhandle für kartengebundene Krypto-Operationen (→ Alias `sicct/<handle>`) |

## 7. Offene Punkte / Ausbaustufen

- **Performance Efficiency:** Latenzbudget-Assertion (gemSpec_Perf, A_… mit `Test=ja`) als optionale
  Teststufe ergänzen.
- **Schema-Validierung:** Antworten zusätzlich gegen die Consumer-XSDs validieren (derzeit
  Element-/Namensraum-Prüfung), z. B. via `javax.xml.validation` gegen `api-telematik/consumer/*.xsd`.
- **VerifyCertificate-Negativfälle:** abgelaufene/zurückgerufene Zertifikate (A_17429-02 Detailcodes
  `CERT_EXPIRED`, `CERT_REVOKED`) mit Testvektoren abdecken.
- **AfoReporter-Integration in CI:** Bericht als Build-Artefakt veröffentlichen.
