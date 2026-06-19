# Feature Specification: UI5 Konnektor SOAP Frontend (ui5-conn-frontend)

**Feature Branch**: `011-ui5-conn-frontend`

**Created**: 2026-06-18

**Status**: Draft

**Input**: User description: "Implement a responsive UI5 application in an own maven module called ui5-conn-frontend that directly calls the konnektor SOAP services. It should start with a TileContainer offering a Tile for every service. When clicking on a tile it should implement a client for the service with a FlexColumnLayout. The FlexColumnLayout should list the main entity of the service and it should have action buttons for using the service. The action buttons should show dialogs for entering data for completing the soap service. The dialogs should be directly bound to an XML model that directly represents the SOAP XML. It should be possible to switch between dialog form view and raw xml view. It should be possible to modify the data in both views"

## Clarifications

### Session 2026-06-18

- Q: How does the browser reach the SOAP/XML konnektor services given CORS/TLS constraints? → A: The `ui5-conn-frontend` module ships its UI5 static files under `META-INF/resources` (served as static resources by the Quarkus server) and is added as a dependency of the `quarkus-server` project. The konnektor SOAP endpoints and the UI5 app therefore run on the **same Quarkus server / same origin**, satisfying the same-origin policy — no separate relay/proxy is required; the browser calls the same-origin SOAP endpoints directly.
- Q: What does the raw XML view show and edit? → A: The **full SOAP envelope** (`<soap:Envelope>` including headers and body), fully editable as raw text. The form view binds to fields within that same envelope so both views share one representation.
- Q: Where does the invocation context (mandant/clientSystem/workplace/user) come from? → A: **Server-configured defaults, user-overridable** — the app loads default context from the server's configuration and pre-fills it into the request; the user may edit it per request in either the form or raw XML view.
- Q: Does the application require its own authentication/access control? → A: **No** — it inherits whatever access control the host `quarkus-server` already enforces (co-hosted, same origin). This feature adds no separate auth layer.
- Q: How is the master list populated for services with no list/read operation (Encryption, Signature, Auth Signature)? → A: **Operation-derived items only** — the list shows only the results of read-type operations; services without such an operation show a permanent empty/placeholder pane and the user works entirely through the action buttons.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Discover and open a service client (Priority: P1)

A technician or integrator opens the application and is presented with a launchpad-style home screen of tiles, one per available konnektor service (Card, Card Terminal, Certificate, Event, Encryption, Signature, Auth Signature). Selecting a tile opens a dedicated client workspace for that service, showing the service's main entities on the left and detail/actions on the right within a two-/three-column responsive layout.

**Why this priority**: The home screen and navigation into a service client is the entry point for every other capability. Without it, no service can be reached. It is the smallest slice that proves the application launches, enumerates services, and routes into a working client shell.

**Independent Test**: Launch the application, confirm a tile appears for each available konnektor service with a recognizable title/icon, click a tile, and confirm the corresponding service client workspace opens with the service's main-entity area visible. Delivers value by giving operators a single, organized access point to all konnektor services.

**Acceptance Scenarios**:

1. **Given** the application has loaded, **When** the home screen is displayed, **Then** exactly one tile is shown for every konnektor service the application is configured to reach, each with a human-readable name.
2. **Given** the home screen is displayed, **When** the user selects a service tile, **Then** the application navigates to that service's client workspace using a column-based layout that lists the service's main entity.
3. **Given** a service client workspace is open, **When** the user navigates back, **Then** they return to the home tile screen with all tiles still available.

---

### User Story 2 - Invoke a service operation through a guided dialog (Priority: P2)

Inside a service client, the user sees action buttons corresponding to the operations that service offers (e.g., for the Card service: verify PIN, change PIN, get PIN status; for the Signature service: sign document, verify document). Pressing an action button opens a dialog with a form for entering the data required to complete that SOAP operation. The user fills in the form, submits, the request is sent directly to the konnektor SOAP service, and the response (or fault) is shown back to the user, with the relevant main-entity list refreshed where applicable.

**Why this priority**: This is the core purpose of the tool — actually exercising the konnektor SOAP operations. It depends on the navigation shell (P1) but delivers the primary value of performing real service calls.

**Independent Test**: From a service client workspace, press an action button, complete the presented form with valid data, submit, and confirm a response is displayed and a SOAP fault is surfaced clearly when invalid data is sent. Delivers value by letting operators run konnektor operations without hand-crafting SOAP envelopes.

**Acceptance Scenarios**:

1. **Given** a service client workspace is open, **When** the user views it, **Then** an action button is shown for each operation that service supports.
2. **Given** an action button is pressed, **When** the dialog opens, **Then** a form is presented containing the input fields required for that operation, pre-populated with sensible defaults/context where available.
3. **Given** a completed form, **When** the user submits, **Then** the request is sent to the corresponding konnektor SOAP service and the successful response is presented in a readable form.
4. **Given** a submitted request that the konnektor rejects, **When** a SOAP fault or error is returned, **Then** the fault details (code/message) are presented to the user without losing the entered data.
5. **Given** a list of the service's main entity (e.g., cards, card terminals, certificates), **When** an operation that changes that entity completes successfully, **Then** the list reflects the updated state.

---

### User Story 3 - Edit requests in both form and raw XML, kept in sync (Priority: P3)

Within an operation dialog, the user can toggle between a form view and a raw XML view of the same request. The form fields and the raw XML are bound to a single underlying representation of the SOAP request, so editing a field updates the XML and editing the XML updates the form. The user can complete and submit the operation from either view.

**Why this priority**: Power users and integrators need to inspect and tweak the exact SOAP payload (for debugging, edge cases, or fields not exposed in the form). It enhances P2 but is not required for a first usable call, hence lower priority.

**Independent Test**: Open an operation dialog, switch to raw XML view and confirm it represents the same data as the form, edit a value in the XML, switch back and confirm the form reflects the change (and vice versa), then submit from either view and confirm the sent payload matches the edited content. Delivers value by giving full transparency and control over the SOAP payload.

**Acceptance Scenarios**:

1. **Given** an operation dialog is open in form view, **When** the user switches to raw XML view, **Then** the XML shown corresponds exactly to the current form values.
2. **Given** the raw XML view is shown, **When** the user edits a value in the XML and switches back to form view, **Then** the form shows the edited value.
3. **Given** the form view is shown, **When** the user edits a field and switches to raw XML view, **Then** the XML reflects the edited field.
4. **Given** the raw XML has been edited into a malformed/invalid state, **When** the user attempts to switch view or submit, **Then** the user is warned and is not allowed to send an invalid payload.
5. **Given** valid edits in either view, **When** the user submits, **Then** the payload actually sent matches what the active representation shows.

---

### Edge Cases

- **Service unreachable / connection failure**: When the konnektor endpoint cannot be reached or times out, the user is shown a clear, non-technical-blocking error and can retry without re-entering data.
- **Authentication / context required**: Operations that require an invocation context (mandant/client system/workplace) or card handles must make those inputs available; missing required context is surfaced as a validation error before sending.
- **Empty main-entity list**: When a service has no entities to list (e.g., no cards inserted), the list area shows an explicit empty state rather than appearing broken. Services with no read/list operation at all (Encryption, Signature, Auth Signature) always present this placeholder pane and are driven solely by action buttons.
- **Long-running or asynchronous operations** (e.g., signature jobs): The user receives progress/pending feedback rather than an apparently frozen UI.
- **Malformed raw XML**: Invalid XML in the raw view blocks submission and view-switching with an explanatory message; entered data is not silently lost.
- **Sensitive data** (PINs, document content): Sensitive inputs are masked where appropriate and are not retained or logged beyond the active request.
- **Small screens**: On phone-sized viewports the multi-column layout collapses gracefully to single-column navigation; dialogs remain usable.
- **Unsupported / newly added service**: If a configured service exposes an operation the UI does not model, the user can still reach it via the raw XML view (graceful degradation) or is clearly told it is unsupported.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The application MUST present a home screen containing one selectable tile per konnektor service it is configured to reach, each tile labeled with a human-readable service name.
- **FR-002**: Selecting a service tile MUST open a dedicated client workspace for that service using a responsive, column-based master/detail layout.
- **FR-003**: Each service client workspace MUST display a list of that service's main entity, populated **only** from the results of that service's read-type operations (e.g., cards/card-terminals via the Event service, certificates via the Certificate service). Services that expose no read/list operation (Encryption, Signature, Auth Signature) MUST show a permanent empty/placeholder pane, and the user operates them entirely through the action buttons.
- **FR-004**: Each service client workspace MUST present an action control for every SOAP operation that service supports.
- **FR-005**: Activating an action MUST open a dialog containing a form with the input fields required to complete that operation.
- **FR-006**: The dialog's form fields MUST be bound to a single underlying request representation that mirrors the **full SOAP envelope** (including SOAP headers and body) for that operation.
- **FR-007**: The dialog MUST allow the user to switch between a form view and a raw XML view; the raw XML view MUST present the **complete SOAP envelope** (`<soap:Envelope>` with headers and body) as fully editable text.
- **FR-008**: Edits made in the form view MUST be reflected in the raw XML view, and edits made in the raw XML view MUST be reflected in the form view (bidirectional synchronization through the shared representation).
- **FR-009**: Submitting a completed request MUST send it directly to the corresponding konnektor SOAP service over the same origin (no intermediary translation layer that hides or alters the SOAP semantics).
- **FR-010**: The application MUST present the service response to the user in a readable form, and MUST make the raw response payload inspectable.
- **FR-011**: When the konnektor returns a SOAP fault or the call fails, the application MUST surface the fault/error details to the user and MUST preserve the user's entered request data.
- **FR-012**: When an operation successfully changes the service's main entity, the corresponding entity list MUST be refreshed to reflect the new state.
- **FR-013**: The application MUST prevent submission of a request whose raw XML is malformed or invalid, with an explanatory message.
- **FR-014**: Required inputs (including any invocation context such as mandant/client-system/workplace and card identifiers, where the operation needs them) MUST be validated before the request is sent.
- **FR-014a**: The application MUST pre-fill the invocation context from server-configured defaults and MUST allow the user to override these values per request in either the form or raw XML view.
- **FR-015**: The layout and dialogs MUST be responsive across phone, tablet, and desktop viewport sizes, collapsing to a usable single-column experience on small screens.
- **FR-016**: Sensitive inputs (e.g., PINs) MUST be masked in the form view by default and MUST NOT be persisted or logged beyond the lifetime of the active request.
- **FR-017**: The application MUST allow the user to configure or select the konnektor endpoint/connection used for service calls. *(Default endpoint MAY be pre-configured; see Assumptions.)*
- **FR-018**: The application MUST build as its own Maven module (`ui5-conn-frontend`) integrated into the existing multi-module project, packaging its UI5 static resources under `META-INF/resources`, and MUST be added as a dependency of the `quarkus-server` project so that the UI and the konnektor SOAP endpoints are served from the same Quarkus server (same origin).
- **FR-019**: The home screen MUST allow the user to return from any service client workspace back to the tile overview.

### Key Entities *(include if feature involves data)*

- **Service**: A konnektor SOAP service reachable by the application (Card, Card Terminal, Certificate, Event, Encryption, Signature, Auth Signature). Attributes: display name, icon/category, set of supported operations, endpoint reference.
- **Operation**: A single SOAP action offered by a service (e.g., verifyPin, signDocument, requestCard). Attributes: name, required inputs, request structure, response structure, whether it mutates a main entity.
- **Service Main Entity**: The primary domain object a service operates on and lists — e.g., Card, Card Terminal, Certificate, Subscription/Resource, Document/Signature Job. Attributes vary per service; used to populate the master list.
- **Request Representation**: The single, editable model of a pending SOAP request, presentable as both a form and raw XML, kept synchronized. Attributes: operation reference, field values, validity state.
- **Service Response / Fault**: The outcome of a submitted operation — either a structured success response or a SOAP fault with code and message — both inspectable in raw form.
- **Connection / Endpoint Configuration**: The konnektor target the application calls, plus any invocation context needed for calls.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From the home screen, a user can reach any service's client workspace in a single selection (one click/tap).
- **SC-002**: A user can complete and submit a typical service operation (open client → press action → fill form → submit → see response) in under 2 minutes without external documentation.
- **SC-003**: 100% of the konnektor services the application is configured for are represented by a tile, and every supported operation of an opened service is reachable via an action control.
- **SC-004**: For any operation dialog, switching between form and raw XML view preserves the data with zero discrepancies in 100% of round-trip edits (form→XML→form and XML→form→XML).
- **SC-005**: Every failed call (connection error or SOAP fault) results in a visible, understandable message and zero loss of the user's entered data.
- **SC-006**: The application is usable (all primary flows completable) on phone, tablet, and desktop viewport widths with no horizontal scrolling of primary content.
- **SC-007**: Malformed raw XML is rejected before sending in 100% of attempts, with a message that identifies the problem.

## Assumptions

- The target users are technicians, integrators, testers, and developers who understand konnektor/TI concepts — not end patients. The tool is an operational/diagnostic client, not a consumer app.
- The set of services to expose corresponds to the konnektor SOAP services already implemented in this project: Card (v8.1), Card Terminal (v1.1), Certificate (v6.0), Event (v7.2), Encryption, Signature (v7.5), and Auth Signature (v7.4). New services added later should appear as additional tiles with minimal change.
- "Directly calls the konnektor SOAP services" means the frontend communicates with the konnektor SOAP endpoints using their native SOAP/XML contracts. Same-origin is achieved by co-hosting: the `ui5-conn-frontend` module serves its UI5 assets from `META-INF/resources` on the same Quarkus server (`quarkus-server`) that exposes the konnektor SOAP endpoints, so no separate proxy/relay is needed.
- The "main entity" listed per service is the most representative domain object for that service (e.g., cards for Card/Event services, certificates for Certificate service, card terminals for Card Terminal service, documents/jobs for Signature/Encryption services); exact list contents are derived from the service's read/list operations.
- A default konnektor endpoint and invocation context MAY be pre-configured for convenience; the user can override them. Specific endpoint discovery/management beyond select/override is out of scope for v1.
- Authentication to the konnektor and TLS/mTLS material handling follow the existing project conventions and are reused rather than reinvented (security-sensitive; subject to the project's security review).
- The application adds no authentication layer of its own; access control is whatever the host `quarkus-server` already enforces. Because the UI is co-hosted on that server, the same boundary protects both the UI and the SOAP endpoints.
- Internationalization scope for v1 is the project's existing language convention; full multilingual support is not required for the first release.
- Persisting request history, saved payloads, or audit trails is out of scope for v1 (sensitive data is not retained beyond the active request).
- The application is a single-konnektor-at-a-time client; multi-tenant/multi-konnektor dashboards are out of scope for v1.
