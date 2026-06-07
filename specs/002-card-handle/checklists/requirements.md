# Specification Quality Checklist: Card Handle

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-07
**Feature**: [spec.md](../spec.md)

## Content Quality

- [X] No implementation details (languages, frameworks, APIs)
- [X] Focused on user value and business needs
- [X] Written for non-technical stakeholders
- [X] All mandatory sections completed

## Requirement Completeness

- [X] No [NEEDS CLARIFICATION] markers remain
- [X] Requirements are testable and unambiguous
- [X] Success criteria are measurable
- [X] Success criteria are technology-agnostic (no implementation details)
- [X] All acceptance scenarios are defined
- [X] Edge cases are identified
- [X] Scope is clearly bounded
- [X] Dependencies and assumptions identified

## Feature Readiness

- [X] All functional requirements have clear acceptance criteria
- [X] User scenarios cover primary flows
- [X] Feature meets measurable outcomes defined in Success Criteria
- [X] No implementation details leak into specification

## Notes

- 56 functional requirements covering: card handle creation (FR-001–FR-013), card sessions (FR-014–FR-020), session subtypes (FR-021–FR-025), eGK session lifecycle (FR-026–FR-028), comfort signature (FR-029–FR-032), active card request/RequestCard/TUC_KON_056 (FR-033–FR-041), card ejection/EjectCard/TUC_KON_057 (FR-051–FR-056), startup reconstruction + disconnect (FR-042–FR-045), event notifications (FR-046–FR-050)
- SICCT is explicitly P1, PC/SC is P2
- Certificate validation (certStatus, certOcspResponse) is async by design — SC-003 captures the timing expectation
- eGK-specific rules (KVNR, MRPIN exclusion, authBy, sessionID, CARD_SESSION_TIMEOUT) are captured in FR-010, FR-016, FR-020, FR-026–FR-028
- HBA comfort signature mode scoped per-session in FR-017, FR-029–FR-032
- Session subtype constraints C1 (eGK single session), C2 (HBAx identity), C3 (SM-B identity), C16 (HBAx userId mandatory) captured in FR-022–FR-025
- WorkplaceId excluded from session creation per spec clarification — documented in Assumptions
- Error codes 4093 (card reserved), 4278 (max comfort sessions), 4288 (unknown sessionID) captured in FR-022, FR-029, FR-028
- Comfort signature limits: SAK_COMFORT_SIGNATURE_MAX (configurable) and 250 per HBA logical channel (hardware cap) captured in FR-032
- RequestCard (TUC_KON_056) error codes: 4039 (terminal display busy), 4051 (wrong card type), 4097 (invalid slot), 4202 (timeout), 4221 (terminal not active), 4222 (not connected) captured in FR-038–FR-040
- AlreadyInserted flag, default timeout (20 s), and GetCards read-only constraint captured in FR-036, FR-039, FR-041
- Default display messages per TIP1-A_5408 captured in FR-034; slot-reference omission rule included
- EjectCard: address by cardHandle OR ctId+slotId, lock check (4093), always-send behavior, 4203/4101 errors, 20 s default timeout — FR-051–FR-056
- Startup rebuild (FR-042–FR-043) and disconnect invalidation+rebuild (FR-044–FR-045) clarified
- Event topics from TUC_KON_256: CARD/INSERTED, CARD/REMOVED, CERT/CARD/STATUS, CARD/SESSION/TIMEOUT — FR-046–FR-049
- G2.0 SMC-B/HBA pseudonym admin log (A_25801) — FR-050
- cardHandle 48-hour reuse prohibition — FR-003
- No software cap on concurrent handles — bounded by terminal hardware only (Assumptions)
