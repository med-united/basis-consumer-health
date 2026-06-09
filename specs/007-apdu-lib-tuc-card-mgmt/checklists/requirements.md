# Specification Quality Checklist: APDU Library — Card Management TUCs

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-09
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

## TUC Coverage

- [x] TUC_KON_026 — Provide Card Session (TIP1-A_4567)
- [x] TUC_KON_012 — Verify PIN (TIP1-A_4566)
- [x] TUC_KON_019 — Change PIN (TIP1-A_4568)
- [x] TUC_KON_021 — Unblock PIN (TIP1-A_4569-02)
- [x] TUC_KON_022 — Provide PIN Status (TIP1-A_4570)
- [x] TUC_KON_027 — Enable/Disable PIN Protection (TIP1-A_5486)
- [x] TUC_KON_023 — Reserve Card (TIP1-A_4571-03)
- [x] TUC_KON_005 — Card-to-Card Authentication (TIP1-A_4572)
- [x] TUC_KON_202 — Read File (TIP1-A_4573)
- [x] TUC_KON_203 — Write File (TIP1-A_4574)
- [x] TUC_KON_204 — Clear File Content (TIP1-A_4576)
- [x] TUC_KON_209 — Read Record (TIP1-A_4575)
- [x] TUC_KON_210 — Write Record (TIP1-A_4576)
- [x] TUC_KON_211 — Clear Record Content (TIP1-A_4577)
- [x] TUC_KON_214 — Append Record (TIP1-A_4577)
- [x] TUC_KON_215 — Search Record (TIP1-A_4578)
- [x] TUC_KON_018 — Check eGK Blocking Status (TIP1-A_4579-02)
- [x] TUC_KON_006 — Write Data Access Audit to eGK (TIP1-A_4580)
- [x] TUC_KON_219 — Sign (TIP1-A_4581)
- [x] TUC_KON_220 — Decrypt (TIP1-A_4582)
- [x] TUC_KON_223 — Start Card Session (A_26067)
- [x] TUC_KON_208 — Send Secured APDU (A_26069-01)
- [x] TUC_KON_224 — Stop Card Session (A_26068)
- [x] TUC_KON_200 — Send APDU (TIP1-A_4583-02)
- [x] TUC_KON_024 — Reset Card (TIP1-A_4584-02)
- [x] TUC_KON_216 — Read Certificate (TIP1-A_4585)
- [x] TUC_KON_036 — Provide Professional Role (TIP1-A_5478)

## Notes

- The gematik specification lists 27 TUCs in section 4.1.5.4 of gemSpec_Kon V5.27.0 (not 28 as approximated in the feature request — section 4.1.5.3 contains TUC_KON_001 "Karte öffnen" as a non-Fachmodul-accessible TUC which was excluded).
- TUC_KON_220 "Entschlüssele" identifier was inferred from context (the TOC entry following TUC_KON_219 "Signiere"); must be verified against the online spec at gemspec.gematik.de before implementation.
- TUC_KON_204 and TUC_KON_210 share requirement base TIP1-A_4576; TUC_KON_211 and TUC_KON_214 share base TIP1-A_4577 — sub-suffixes (-1/-2) used in the inventory table for disambiguation.
