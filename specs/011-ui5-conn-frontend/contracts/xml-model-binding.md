# Contract: XML Model Binding & Form ↔ Raw Synchronization

Defines the single-source-of-truth behavior every operation dialog must satisfy
(FR-006, FR-007, FR-008, FR-013; SC-004, SC-007).

## Single source of truth
- The dialog owns exactly one `sap.ui.model.xml.XMLModel` whose document is the **full SOAP
  envelope** (headers + body) for the operation.
- The envelope is created by filling the operation's `envelopeTemplate` with the current
  `InvocationContext` and empty/default field values.

## Form view
- Each form control is bound to a node via the field's `path` (two-way binding).
- Editing a control updates the XMLModel node directly.
- Required + typed validation (FR-014) runs on the bound values before submit.
- `sensitive` fields use masked input (`type=password`) and are excluded from logs (FR-016).

## Raw view
- A `sap.ui.codeeditor.CodeEditor` (type `xml`) shows `XMLSerializer.serializeToString(model.getData())`.
- On raw edit + apply/toggle: parse the text with `DOMParser`.
  - If parse yields a `parsererror` node → **invalid**: block toggle/submit, show the parse
    message, keep the user in raw view with their text (FR-013, SC-007). No data lost.
  - If valid → `model.setData(parsedDocument)`; the form reflects the change on next render.

## Synchronization guarantees
- **form → raw**: control change → model node updated → serialize shows the change.
- **raw → form**: valid raw edit → `setData` → bound controls re-read from the model.
- **Round-trip integrity (SC-004)**: form→raw→form and raw→form→raw MUST produce a
  byte-equivalent envelope (modulo insignificant whitespace) — zero semantic drift.

## Validity gate (single chokepoint)
A `isEnvelopeValid()` helper is the only path to:
- switching from raw to form view, and
- submitting.
It returns false (with a reason) for malformed XML, empty required nodes, or type
violations, and the UI refuses the action. (FR-013, FR-014, SC-007)

## Test obligations (QUnit)
- `verify_pin_round_trips_form_and_raw_xml` — edit form, toggle raw, edit raw, toggle back; assert values on both sides.
- `rejects_malformed_envelope_before_send` — corrupt raw XML; assert submit blocked + message + data retained.
- `context_defaults_prefilled_into_envelope` — assert `{{context.*}}` filled from config.
- `serialize_parse_roundtrip_is_lossless` — assert SC-004 byte-equivalence.
