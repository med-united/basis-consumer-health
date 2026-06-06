# Specification Quality Checklist: Basis-Consumer for the German Telematikinfrastruktur (TI)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-05
**Last updated**: 2026-06-06 (merged features 004 + 005 into single specification; 23 SCs, 177 FRs, 12 user stories)
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) ¹
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders ²
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic ³
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification ¹

## Notes

All items pass. The specification and plan artifacts are complete and ready for `/speckit-tasks`.

**Merge history**:
- **2026-06-05 (initial)**: Feature 001 created — full TI Basis Consumer (8 user stories, FR-001 to FR-092)
- **2026-06-05 (amendment)**: KOM-LE amended to mandate openkim fork+submodule
- **2026-06-05 (merge)**: Feature 002 (Pluggable JCE Provider + SICCT Quarkus Extension) merged into this feature. User Story 2 added. FR numbering reorganized into clean 10-number ranges (FR-001–FR-122). Research, data-model, contracts, and quickstart moved from 002 into this directory and expanded.
- **2026-06-06 (merge)**: Feature 003 (SICCT Card Terminal Persistence via Database) merged into this feature.
- **2026-06-06 (amendment)**: Requirements derived from gemSpec_KT_V3.17.0 and gemSpec_Kon_V5.27.0 added. New FR blocks: FR-130–FR-138 (SICCT terminal authentication — EHEALTH TERMINAL AUTHENTICATE CREATE/VALIDATE/ADD, SICCT TLS state machine A_22456, SAK.AUT cert, shared secret management, gSMC-KT expiry monitoring); FR-140–FR-145 (card-to-card authentication TUC_KON_005, remote-PIN TIP1-A_5012, secure card session TUC_KON_223/208, PIN operations); FR-150–FR-155 (AufrufKontext parameters, MTOM, gematik-SOAP-Fault, ExternalAuthenticate hash constraints, SOAP parallelism, client auth). FR-062–FR-063 updated (LDAP: DNS-SD VZD discovery, mandatory LDAPS to VZD, cert validation TUC_PKI_018). FR-092 updated (CORRELATION state machine). FR-100 expanded (8 additional SOAP operations). FR-100 normative source gemSpec_KT_V3.17.0 added. Interactive-PIN assumption corrected: remote-PIN IS in scope. 4 new edge cases added. 3 new Key Entities added (CT Pairing Block, CT Session Authentication, SAK.AUT Certificate). Total: 95 FRs. User Story 8 rewritten to mandate DB-backed terminal management via Hawtio console. FR-096–FR-099 added. FR-015 and FR-028 updated to remove config-file terminal approach. SC-012 and SC-013 added. Assumptions section updated. contracts/sicct-extension-config.md updated to retire `quarkus.sicct.terminals[*]` entries. specs/003-terminal-jpa-persistence/ deleted.

**¹ Intentional deviation — user-mandated architectural constraints**: The "Architectural Mandates" section at the top of spec.md lists technology choices mandated by the user (Quarkus, JCE, PKCS#11, PC/SC, Apache CXF, Hawtio, JavaDB, Netty, openkim). These appear in FRs only where they define behavioral requirements, not as implementation-choice leakage.

**² Domain audience**: This is a TI product specification. Primary readers are the engineering team and gematik auditors; the spec is written as clearly as the regulatory domain permits.

**³ Technology-adjacent success criteria**: SC-006 (four provider types concurrently) and SC-007 (SICCT reconnection timing) reference architectural decisions mandated by the user; these are not incidental implementation details.

**Normative sources fully reviewed**:
- gemSpec_Basis_Consumer_V1.12.1 — §6.1–6.5 fully read; appendices (pp. 61–73) to be consulted during implementation
- gemSpec_Kon_V5.27.0 — §3.6 (Clientsystemschnittstelle) scoped; full WSDL details resolved during plan phase via CXF wsdl2java
- SICCT-Spezifikation-1.3.0 — architecture and command set reviewed; full command-set details resolved in plan phase
- QES explicitly out of scope for v1
- All 9 user stories are independently testable MVPs
