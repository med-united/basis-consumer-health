# Quickstart: UI5 Konnektor SOAP Frontend

Validation guide proving the feature works end-to-end. Implementation details live in
`tasks.md` and the source; this is a run/verify guide.

## Prerequisites
- JDK 21, Maven (`./mvnw`).
- Network access at **build time** for `frontend-maven-plugin` to provision Node + fetch
  OpenUI5 (no network needed at runtime — self-contained bundle).
- `api-telematik` submodule checked out (`git submodule update --init api-telematik`) for
  authoring envelope templates (not required to run the built app).

## Build
```bash
# Build the new module (runs ui5 build self-contained + JS tests via frontend-maven-plugin)
./mvnw -pl ui5-conn-frontend -am install

# Build the server that hosts it
./mvnw -pl quarkus-server -am install
```

## Run (dev profile — OIDC disabled)
```bash
./mvnw -pl quarkus-server quarkus:dev
# open the SPA (same origin as the SOAP endpoints):
#   http://localhost:8080/conn-ui/
```

## Scenario 1 — Discover & open a service (User Story 1)
1. Open `http://localhost:8080/conn-ui/`.
2. **Expect**: one tile per service (Card, Card Terminal, Certificate, Event, Encryption,
   Signature, Auth Signature). (SC-003)
3. Click **Event Service** → a FlexibleColumnLayout client opens.
4. **Expect**: master list area visible; back navigation returns to the tiles. (FR-002/FR-019)

## Scenario 2 — Populate the list & invoke an operation (User Story 2)
1. In the Event Service client, press **Get Cards** (read op).
2. **Expect**: master list fills with cards (or an explicit empty state if none). (FR-003)
3. Open the **Card Service** client, press **Verify PIN**.
4. **Expect**: a dialog with a form; context fields pre-filled from `/conn-ui/config.json`. (FR-005/FR-014a)
5. Fill the masked PIN reference / values; submit.
6. **Expect**: success response shown with raw XML inspectable; on a rejected PIN, the
   `soap:Fault` (code/message) is shown and entered data is preserved. (FR-010/FR-011)

## Scenario 3 — Form ↔ raw XML editing (User Story 3)
1. In the Verify PIN dialog (form view), change a field.
2. Toggle to **Raw XML**.
3. **Expect**: the raw `<soap:Envelope>` reflects the change (full envelope, headers+body). (FR-007/FR-008)
4. Edit a value directly in the raw XML; toggle back to **Form**.
5. **Expect**: the form shows the raw edit. (FR-008, SC-004)
6. Corrupt the XML (delete a closing tag); try to toggle/submit.
7. **Expect**: blocked with a parse-error message; data not lost. (FR-013/SC-007)

## Automated validation
```bash
# JS unit (QUnit) + integration (OPA5) with coverage gate (>=80%)
./mvnw -pl ui5-conn-frontend test

# Server config endpoint
./mvnw -pl ui5-conn-frontend test -Dtest=ConnUiConfigResourceTest
```
Key tests map to success criteria: round-trip lossless (SC-004), malformed rejected
(SC-007), one tile per service (SC-003), fault shown without data loss (SC-005).

## Responsive check (SC-006 / FR-015)
Open the app at phone/tablet/desktop widths (browser devtools): the FlexibleColumnLayout
collapses to single-column, dialogs remain usable, no horizontal scroll of primary content.
