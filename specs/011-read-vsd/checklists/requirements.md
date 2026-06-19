# Specification Quality Checklist: VSDService — Local ReadVSD (Card-to-Card + eGK Read)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-18
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Five clarifications were resolved in the 2026-06-18 session and recorded in the
  spec's Clarifications section: (1) `PerformOnlineCheck=true` is rejected with a SOAP
  fault; (2) the Prüfungsnachweis (`ReadOnlineReceipt=true`) is out of scope and
  rejected; (3) hard 30 s timeout with no card-I/O p95 target (FR-027, SC-008); (4)
  decoded VSD/PII never logged or persisted (FR-028, SC-009); (5) concurrent read of
  the same eGK fails fast with a card-busy fault (FR-029, SC-010). No open
  [NEEDS CLARIFICATION] markers remain.
- The spec references gematik Afo IDs (`VSDM-A_xxxx`) and konnektor use-case IDs
  (`TUC_KON_xxx`) purely for requirement traceability per Constitution Principle V
  (Afo traceability); these are normative requirement identifiers, not implementation
  details, consistent with the existing `010-cetp-client` spec.
- "Local-only" boundary (no UFS/CMS/VSDD/Intermediär) is asserted as FR-006 and made
  measurable by SC-004 (zero external TI connections).
