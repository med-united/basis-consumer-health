# system-tests

Black-box SOAP **system tests** that drive the running Konnektor and Basis-Consumer endpoints over
plain HTTP. No generated stubs and no Quarkus runtime — each step sends a hand-written SOAP
envelope, so the test reflects exactly what an external primary system puts on the wire.

> **Basis-Consumer suite:** the `consumer/*` tests (Certificate, Encryption, Signature basisdienste)
> are organized as an ISO/IEC 25000 test concept with gematik-AFO traceability — see
> [`TESTKONZEPT.md`](TESTKONZEPT.md).

## `EPrescriptionSoapFlowTest`

Exercises every SOAP request needed to create (sign) an e-prescription, in order:

| # | Service | Operation | Notes |
|---|---------|-----------|-------|
| 1 | `EventService`         | `GetCards`             | discovers cards, picks a `CardHandle` |
| 2 | `CertificateService`   | `ReadCardCertificate`  | `<CertRef>C.AUT</CertRef>` |
| 3 | `AuthSignatureService` | `ExternalAuthenticate` | signs a SHA-256 hash with C.AUT |
| 4 | `SignatureService`     | `GetJobNumber`         | obtains the batch job number |
| 5 | `SignatureService`     | `SignDocument`         | qualified signature over the prescription |

The card handle from step 1 flows into steps 2, 3 and 5; the job number from step 4 flows into
step 5.

## Running

These tests need a **live server with a card inserted**. Start one, e.g.:

```bash
mvn -pl quarkus-server quarkus:dev      # serves SOAP under http://localhost:8080/ws
```

then run the system tests:

```bash
mvn -pl system-tests test
```

When no server is reachable the whole class **skips** itself (JUnit assumption) instead of failing,
so it is safe inside the reactor build.

### Configuration (system properties)

| Property | Default | Purpose |
|----------|---------|---------|
| `systemtest.base.url`   | `http://localhost:8080/ws` | base URL of the CXF endpoints (`quarkus.cxf.path`) |
| `systemtest.card.handle`| _(from GetCards)_          | force a specific card handle instead of discovering one |

Card handles are server-assigned UUIDs (from `GetCards`), so by default the card-bound flow tests
discover one rather than assuming a fixed value; when no card is reachable they skip themselves.
Pass an explicit handle only to pin a specific card:

```bash
mvn -pl system-tests test \
    -Dsystemtest.base.url=http://my-konnektor:8080/ws \
    -Dsystemtest.card.handle=6fbf3289-fa66-4c1b-a16c-ed99706ebd2a
```
