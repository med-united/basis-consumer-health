<!--
SYNC IMPACT REPORT
==================
Version change: 1.4.0 → 1.5.0
Modified principles: none (existing principles I–VI unchanged)
Added sections:
  - Principle VII — External Library Evaluation & Security Review (new)
    Mandates evaluating established external libraries (e.g., Bouncy Castle for
    cryptography, gematik/ref-GemLibPki for TI PKI) before writing custom
    implementations. Requires a documented security review for every adopted
    dependency. Library MUST be used when the security review is satisfactory.
    Review outcome MUST appear in planning artifacts and in the SBOM.
Removed sections: none
Templates requiring updates:
  - .specify/templates/plan-template.md ⚠ Constitution Check placeholder already
    dynamically references constitution at plan time; no structural change needed.
    The /speckit-plan command will surface Principle VII gates automatically.
  - .specify/templates/spec-template.md ⚠ No mandatory sections conflict; no change needed
  - .specify/templates/tasks-template.md ⚠ No change needed; library evaluation is a
    Phase 0 research artifact captured in research.md, not a task-template concern.
Previous sync report (1.3.0 → 1.4.0):
  - Principle VI — UML Documentation Standards added
  - .specify/templates/plan-template.md ✅ diagrams/ added to project structure tree
  - README.md ✅ Created with plantuml-for-github recommendation
  - specs/001-quarkus-basis-consumer/diagrams/ ✅ All 8 .puml files created
Deferred TODOs:
  - Proxy image URLs in diagrams/README.md use placeholder "your-org" — must be replaced
    with the actual GitHub organization name when the repo is pushed to GitHub.
-->

# Basis Consumer Health Constitution

## Core Principles

### I. Code Quality

All code merged into the main branch MUST meet the following non-negotiable standards:

- **Readability first**: Every module, function, and variable MUST have a name that communicates intent without requiring comments to explain *what* it does. Comments are reserved for *why* non-obvious decisions were made.
- **Single responsibility**: Each module, class, and function MUST have one clearly stated purpose. Violations require explicit justification in the PR description.
- **No dead code**: Unused imports, commented-out blocks, and orphaned utilities MUST be removed before merge. Code is deleted, not archived in-place.
- **Consistent style**: All code MUST pass the project linter and formatter at zero warnings. No linter rule suppressions are permitted without a documented rationale in the suppression comment.
- **Dependency hygiene**: Every new external dependency MUST be justified by capability gap (not convenience) and reviewed for license compatibility and maintenance status.

**Rationale**: Accumulated code debt in consumer-health contexts directly increases the risk of incorrect health data handling. Readability and consistency are safety properties here, not aesthetic preferences.

### II. Testing Standards

Testing is a first-class engineering activity and MUST be treated as such throughout the development lifecycle:

- **Test-First by default**: For all new behavior, tests MUST be authored and reviewed before implementation is written. Exceptions (exploratory spikes, pure config changes) MUST be called out in the PR.
- **Coverage floor**: Unit test coverage MUST remain at or above 80% for all source modules. Drops below this threshold block merge.
- **Integration tests required** for: any new API contract, any change to inter-service communication, any shared schema modification, and any data persistence layer change.
- **Tests MUST be deterministic**: Flaky tests (non-deterministic, order-dependent, or time-sensitive without mocking) MUST be fixed or removed within one sprint of detection. Flaky tests MAY NOT be skipped as a workaround.
- **Test naming MUST be descriptive**: Test names MUST read as a specification sentence — e.g., `returns_empty_list_when_no_health_records_exist`, not `test_1`.
- **No testing through mocks of the domain layer**: Business logic MUST be tested against real domain objects. Infrastructure (DB, HTTP, queues) MAY use fakes or mocks, but the seam MUST be at the infrastructure boundary.

**Rationale**: Consumer health data correctness cannot be validated by manual QA alone. Automated, deterministic tests are the primary safety net for regression prevention.

### III. User Experience Consistency

Every user-facing surface MUST conform to a single, coherent experience regardless of which team or sprint produced it:

- **Design system compliance is mandatory**: All UI components MUST be sourced from the project design system. Custom one-off components require design review and, if approved, MUST be contributed back to the system.
- **Interaction patterns MUST be uniform**: Equivalent actions (save, cancel, navigate back, confirm destructive action) MUST behave identically across all screens and flows.
- **Error and empty states are not optional**: Every data-displaying view MUST define and implement its error state, loading state, and empty state before the feature is considered complete.
- **Accessibility is non-negotiable**: All interactive elements MUST meet WCAG 2.1 AA contrast ratios, keyboard navigability, and screen-reader label requirements. Accessibility failures block release.
- **Copy and terminology MUST be consistent**: Health-domain terminology (symptoms, conditions, measurements, units) MUST use the agreed glossary. Inconsistent labeling in UI copy is treated as a bug.

**Rationale**: Consumer health applications require users to trust the product with sensitive data. Inconsistent UX erodes that trust and increases the likelihood of user error in health-critical workflows.

### IV. Performance Requirements

The application MUST meet defined performance budgets at all times. Performance regressions are treated as bugs, not enhancements:

- **API response time**: All API endpoints MUST respond within 200ms at p95 under expected load. Endpoints exceeding 500ms at p99 MUST be escalated and addressed within the current sprint.
- **UI interaction latency**: User interactions (taps, clicks, form submissions) MUST produce visible feedback within 100ms. Full screen transitions MUST complete within 300ms.
- **Initial load time**: The application's critical rendering path MUST complete within 2 seconds on a median network (4G / 10 Mbps). First Contentful Paint MUST occur within 1.5 seconds.
- **Memory ceiling**: The application MUST not exceed 150 MB of resident memory under normal usage. Background processes MUST release memory promptly and MUST not retain health record data beyond the active session.
- **Performance tests in CI**: Performance-critical paths MUST have benchmark tests that run in CI. A benchmark regression of more than 15% MUST fail the build.

**Rationale**: Consumer health tools are often used in moments of stress or urgency. Slow or unresponsive interfaces directly degrade health outcomes and trust. Performance is a patient-safety concern.

### V. Security & Data Protection (gematik TI Compliance)

This principle encodes mandatory requirements from **gemSpec_DS_Hersteller v1.7.0** (gematik, 2025-05-27),
the normative security and data-protection specification for Telematikinfrastruktur (TI) product manufacturers.
All rules below carry the force of the source specification. Requirement IDs are retained for traceability.

#### Vulnerability Management

- **[GS-A_2330-02] Proactive vulnerability monitoring**: The team MUST implement preventive measures to
  continuously detect and analyze technical hardware and software vulnerabilities, including components
  supplied by third parties. Monitoring MUST cover all shipped dependencies, not only first-party code.
- **[A_22984] Immediate CVSS assessment**: Every identified software vulnerability MUST be assessed using
  the current CVSS standard within **< 24 hours on working days** and **< 72 hours on weekends and
  public holidays**.
- **[A_22986] Mandatory reporting of critical vulnerabilities**: Vulnerabilities with CVSS ≥ 7 and
  significant threats MUST be reported to gematik and deploying providers immediately after assessment
  is complete.
- **[GS-A_2525-01] Coordinated vulnerability closure**: Newly reported software or hardware vulnerabilities
  MUST be disclosed to gematik immediately; further remediation steps MUST be coordinated with gematik
  to minimize impact and close the vulnerability as quickly as possible.
- **[A_23029] Patch delivery SLAs by severity** — assessed vulnerabilities MUST be remediated within:

  | CVSS score | Maximum remediation time          |
  |------------|-----------------------------------|
  | 10 – 9     | As fast as possible (individual agreement) |
  | 8.9 – 7    | 1 month                           |
  | 6.9 – 5    | Quarterly                         |
  | < 5        | Regular patch cycle               |

- **[A_23445] Coordinated Vulnerability Disclosure**: The team MUST actively participate in gematik's
  Coordinated Vulnerability Disclosure (CVD) program, including receipt, analysis, and public communication
  of disclosed vulnerabilities after closure.
- **[A_17179] Third-party component currency**: When shipping an installation package that includes
  additional software components (e.g., runtime libraries), the team MUST deliver patched versions of
  those components as updates immediately upon availability of a security fix, even if the first-party
  product itself has not changed.

#### Secure Software Development Lifecycle (SSDLC)

- **[A_19152] Secure product lifecycle**: Security activities MUST be integrated throughout the entire
  product lifecycle — development, operation, and decommissioning — following industry-recognized
  frameworks (e.g., Microsoft SDL, OWASP SAMM).
- **[GS-A_4946-01] Secure coding guidelines**: Recognized and proven secure coding guidelines MUST be
  applied during development. The specific guidelines in use MUST be documented.
- **[GS-A_4945-01] Quality assurance techniques**: Technical-organizational QA procedures MUST be
  applied during product development, including fuzz/robustness testing, penetration testing, and source
  code review.
- **[GS-A_4947-01] Confidential development environment**: Products MUST be developed in an environment
  where technical and organizational measures protect the confidentiality and integrity of the product
  and its development artifacts.
- **[A_17178] OWASP Top 10 baseline**: The product MUST be resistant to all risks identified in the
  current and the two preceding OWASP Top 10 reports. Non-applicability of a specific risk MUST be
  explicitly documented and justified.
- **[A_19153] Security architecture review**: A security-relevant software architecture review MUST be
  conducted and all identified architecture-level vulnerabilities MUST be remediated.
- **[A_19154] Threat analysis**: A threat analysis MUST be performed and countermeasures MUST be
  implemented for all identified threats.
- **[A_19155] Security source-code review**: Security-relevant source code reviews or automated
  security-relevant source code scans MUST be conducted throughout development.
- **[A_19156] Automated security tests**: Automated security tests MUST be executed during development
  and MUST be part of the CI pipeline.
- **[A_19160] Change and configuration management**: A formal change and configuration management process
  MUST be in place. Configuration management MUST provide at any point in time the definitive composition
  of the product — including all third-party libraries and frameworks — and the changes made to
  first-party components.

#### Security Documentation & Testing

- **[A_19148] Security and data-protection concept**: Security and data-protection measures MUST be
  documented in a formal concept that covers: product description (security aspects), gematik security
  requirements, protection-needs assessment, threat analysis, security analysis (verification of
  effectiveness), residual-risk assessment, identification of personal data and processing activities,
  and data-protection risk notes. This document MUST be provided to gematik on request.
- **[A_19147] Security test plan**: A security test plan covering all security tests during product
  development phases and regular penetration tests by independent security experts MUST exist and MUST
  be provided to gematik on request.
- **[A_19150] Security test execution and reporting**: The security test plan MUST be executed; a test
  report MUST be provided to gematik with every new product version release.
- **[A_19151] Implementation-specific security requirements**: Implementation-specific security
  requirements MUST be documented and implemented during development.
- **[A_19157 / A_19158] Developer security training**: A documented training plan for regular developer
  training in secure development and secure coding MUST exist and be implemented. All developers MUST
  complete this training.
- **[A_19159] SSDLC documentation**: The secure product lifecycle and its sub-processes MUST be
  documented, covering at minimum: security requirements capture, architecture/design reviews, threat
  analyses, source-code reviews, QA-phase security tests, quality gates that block release of products
  with MEDIUM or HIGH security defects, change/configuration management, and vulnerability management.

#### Supply Chain & Inventory

- **[A_27430] Supply-chain attack prevention**: Measures against supply-chain attacks MUST be
  implemented (reference: BSI TR-03161 "Third-Party Software" and NISTIR 8276).
- **[A_27431 / A_27432] Software Bill of Materials (SBOM)**: An SBOM MUST be created and maintained in
  accordance with BSI Technical Guideline TR-03183 Part 2. The SBOM MUST be provided to gematik on
  request.
- **[A_27433] Authorized code modifications only**: Only authorized team members MAY perform code
  modifications. Access controls MUST be enforced to prevent unauthorized (including former-employee)
  code changes.
- **[A_27429] Supplier SSDLC verification**: If a supplier is commissioned to develop software
  components, the supplier MUST provide documented proof of operating a secure software development
  process (e.g., ISO 27001 certification covering the development scope).

#### Afo Implementation Traceability

Every normative gematik requirement (Afo — Anforderung) that is implemented in this product
MUST be traceable from specification through code to test. The following rules are
non-negotiable:

- **One commit per Afo**: Each Afo MUST be implemented in its own dedicated git commit.
  The commit message MUST reference both the Afo ID and the corresponding Jira ticket in
  the following format:
  ```
  [JIRA-TICKET] [AFO-ID] <imperative summary of what the Afo requires>

  Example: BCP-1234 A_19156 add automated security tests to CI pipeline
  ```
  Bundling multiple Afos into a single commit is prohibited. If an Afo's implementation
  requires preparatory refactoring, the refactoring MUST be committed separately before
  the Afo commit.
- **One test per Afo**: Every Afo MUST be covered by at least one automated test.
  The default is a **unit test**. An integration or end-to-end test MAY substitute if
  the Afo describes system-level or cross-component behavior that cannot be meaningfully
  verified at unit level — this substitution MUST be explicitly noted in the commit
  message (e.g., `covered by integration test: AfoTest_A_19156`).
- **Test naming MUST reference the Afo ID**: Test methods or test cases covering an Afo
  MUST include the Afo ID in their name so the traceability is machine-readable.
  Example: `test_A_19156_automated_security_tests_run_in_ci()`.
- **No merge without Afo test**: A PR that implements an Afo MUST include the corresponding
  test in the same PR. Afo tests MAY NOT be deferred to a follow-up ticket.
- **Afo coverage report**: The Jira ticket for each Afo MUST be updated with a link to
  the implementing commit and the covering test before the ticket is closed.

**Rationale**: gematik certification audits (A_19163–A_19165) require demonstrable traceability
between normative requirements and their implementation. Per-Afo commits and named tests make
this traceability machine-verifiable and auditor-friendly, removing ambiguity during
conformance assessments.

#### Audit Support

- **[A_19163 / A_19164 / A_19165] Audit and penetration-test cooperation**: The team MUST grant gematik
  (or authorized representatives bound by confidentiality) the right to conduct security audits and
  whitebox/blackbox penetration tests at any time, verify implementation of requirements on-site, and
  access all information necessary for the audit. The same obligation extends to all subcontractors.
  Cooperation includes providing release/beta versions, test systems, test accounts, and minor product
  adaptations that facilitate testing.

#### gematik TI Testing & Certification (gemKPT_Test)

This subsection encodes mandatory testing requirements from **gemKPT_Test v3.6.0** (gematik, 2026-05-19),
the normative test concept for Telematikinfrastruktur product admission. The document follows ISTQB
standards and ISO/IEC/IEEE 29119. Requirement IDs are retained for traceability.

**Mandatory three-phase test lifecycle** — test phases MUST be executed sequentially; each phase MUST
complete successfully before the next begins:

| Phase | Stage | Executed by | Environment |
|-------|-------|-------------|-------------|
| 1 — Self-responsible Test (EvT) | Eigenverantwortliche Tests | Manufacturer | Local / RU DEV |
| 2 — Admission Test (ZulT) | Produktübergreifender Test (PüT) | Manufacturer + gematik | TU |
| 2 — Admission Test (ZulT) | Gesamtintegrationstest TI (GIT-TI) | gematik | TU / RU |

- **[TIP1-A_6517-02 / TIP1-A_6519-01] EvT obligations**: The manufacturer MUST plan, execute, and
  document the Eigenverantwortlicher Test (EvT) in accordance with Tab_Test_005_01. The EvT MUST
  verify: functional requirements (end-to-end), functional-technical requirements, non-functional
  requirements, security requirements, and full product integration.
- **[TIP1-A_6521 / TIP1-A_6523] Admission test obligations**: The manufacturer MUST fulfill all
  admission test (ZulT) obligations per Tab_Test_006, including delivery of the test object,
  support during test execution, provision of a named contact person, and making changes to the
  test object only with prior agreement from gematik's Test & Transition Manager.
- **[A_27438] Generalprobe**: The manufacturer MUST fulfill all obligations for the
  Gesamtintegrationstest - Generalprobe per Tab_Test_035_02.
- **[A_27460] IOP tests with primary systems**: Manufacturers whose products expose an interface to
  primary systems (e.g., PVS, KIS, AVS) MUST make their admission object available for
  interoperability tests (IOP tests) in the reference environment (RU) after the Generalprobe.

**Entry criteria** — a test phase MUST NOT begin unless all of the following are met:

For PüT entry:
- EvT is complete and results are available
- No blocking (Sehr schwer/Schwer) open errors remain
- A documented bug fix plan for all open errors exists
- Test environment (TU) is available
- All required test documentation is created and delivered
- Test object is fully built and delivered; test data, test cards, and certificates are present

For GIT-TI entry:
- All PüT exit criteria are met
- No blocking errors remain
- Bug fix plan is current
- Both TU and RU are available

**Exit criteria** — a test phase is complete only when ALL of the following hold:

For PüT:
- All required tests completed successfully; test goals achieved
- Required test documentation created
- Bug fix plan for remaining open errors exists

For GIT-TI:
- All required tests completed successfully; test goals achieved
- Required test documentation created
- No certification-blocking (Sehr schwer/Schwer) errors remain open

**Mandatory test documentation suite** — all six artifacts MUST be created per the gematik templates
(Tab_Test_013 through Tab_Test_018). Delivery timing is defined per artifact:

| Artifact | Standard | Delivery |
|----------|----------|----------|
| Testkonzept (test concept) | ISO/IEC/IEEE 29119 Master Test Plan | Before EvT / ZulT start |
| Testspezifikation (test specification) | ISO/IEC/IEEE 29119 Level Test Design/Case/Procedure | Before EvT / ZulT start |
| Release Notes | — | Before ZulT start; mandatory for every release |
| Produktdokumentation (product documentation) | — | Before ZulT start |
| Testprotokoll (test log) | ISO/IEC/IEEE 29119 Level Test Log / Anomaly Report | At EvT / ZulT completion |
| Testbericht (test report) | ISO/IEC/IEEE 29119 Level Test Report / Master Test Report | At each test stage completion |

- **[TIP1-A_7335] Provide test documentation**: The manufacturer MUST provide all required test
  documentation in coordination with gematik's Test & Transition Manager.
- **[TIP1-A_6524-01] Adhere to documentation templates**: The manufacturer MUST follow the gematik
  templates for all six test documents (Tab_Test_013 – Tab_Test_018).
- **[A_25392-01] Afo_Testmatrix template mandatory**: The manufacturer MUST use gematik's
  "Afo_Testmatrix" template to document executed and non-executed test cases and MUST provide it
  to gematik. The Afo_Testmatrix MUST be referenced in both Release Notes and the Testkonzept.
- **[A_20065] Use gematik document templates**: Test documentation SHOULD be produced using the
  templates provided by gematik for the admission process. Deviations MUST be agreed with the
  responsible test manager.

**Release Notes content requirements** — every product release MUST include Release Notes containing:
- Reference to the completed Afo_Testmatrix
- Changes beyond what the specification requires
- Fixed bugs (with IDs)
- Open and known bugs with description of their impairment
- Change impact analysis and risk analysis

**Test specification quality** — the Testspezifikation MUST include a test design specification,
test case specification, test procedure specification, and standard configuration specification,
each conforming to ISO/IEC/IEEE 29119. If automated test scripts are used, the scripts themselves
MUST be tested before use in any certification test phase.

**Error severity classification** — all defects discovered in testing MUST be classified using the
following four levels (gematik standard, Ch. 4.2.4):

| Severity | German | Definition |
|----------|--------|------------|
| Critical | Sehr schwer | Test object or key functionality completely unusable; no workaround exists; product cannot be deployed |
| Severe | Schwer | Key functionality usable only with major restrictions; risk of data loss, memory, or performance issues; workaround possible but with restrictions |
| Medium | Mittel | Minor restrictions that do not affect end users |
| Minor | Leicht | No impact on functionality; cosmetic deviations (e.g., spelling errors in messages) |

Critical and Severe defects are certification-blocking. No release with open Critical or Severe
defects may proceed to the next test phase or to production.

**Error management obligations**:
- **[A_20061] Pre-fix description**: For bugs found during the admission test (ZulT), the
  manufacturer MUST provide gematik with a brief description of the nature and scope of the planned
  bug fix *before* implementing it.
- **[A_27475] Bug fix plan**: The manufacturer MUST maintain a bug fix plan documenting all open
  errors. For each open error the plan MUST include: unique error ID, short and detailed description
  (including affected products), CVSS/severity rating, planned fix date, and fix status. The plan
  MUST be updated at least weekly and MUST be accessible to gematik's test contact. The plan MUST
  be created before the conclusion of the EvT and delivered to gematik before ZulT start.

**Rationale**: As a TI-connected consumer-health product, Basis Consumer Health is subject to the normative
security requirements of gemSpec_DS_Hersteller. These rules are not aspirational — they are certification
prerequisites and represent the minimum bar for operating lawfully within the German health telematics
infrastructure.

### VI. UML Documentation Standards

Every feature specification MUST be accompanied by PlantUML diagrams that make the architecture and
interactions visible without requiring readers to read through prose requirements. Diagrams are
living documentation: they MUST be updated in the same PR as any change to the architecture,
protocol, or data model they describe.

#### Tooling

- All diagrams MUST be written in [PlantUML](https://plantuml.com/) syntax and stored as `.puml`
  files under `specs/[###-feature]/diagrams/`.
- A `diagrams/README.md` MUST exist in every feature's diagram directory, listing all diagrams
  with a one-line description and a GitHub proxy image link for each.
- To render `.puml` files directly in the GitHub web UI, the project README MUST recommend the
  **[PlantUML for GitHub](https://github.com/plantuml/plantuml-for-github)** browser extension.
  No server-side rendering infrastructure is required.
- GitHub proxy image links MUST use the format:
  `https://www.plantuml.com/plantuml/proxy?cache=no&src=https://raw.githubusercontent.com/<org>/<repo>/<branch>/<path>.puml`

#### Required diagram types (in priority order)

The following diagram types MUST be produced for every feature that introduces significant
architecture, interaction, or data-model changes. Simpler features (pure config, minor bug fixes)
MAY omit lower-priority types with a written justification in the PR.

| Priority | Diagram type | Purpose | Filename convention |
|----------|--------------|---------|---------------------|
| 1 | **Use Case Diagram** | Actors and their interactions with the system | `use-case.puml` |
| 2 | **Deployment Diagram** | Physical / cloud topology, network zones, external systems | `deployment.puml` |
| 3 | **Component Diagram** | Module / package structure and their dependencies | `component.puml` |
| 4 | **Sequence Diagram(s)** | Interaction flows between objects / services | `sequence-<flow-name>.puml` |
| 5 | **State Chart(s)** | Lifecycle and state machines of key entities | `state-<entity-name>.puml` |
| 6 | **Class Diagram** | Detailed domain model with fields, methods, relationships | `class-<scope>.puml` |

#### Quality rules for diagrams

- Diagrams MUST reflect the current state of the system, not an aspirational future state.
  Outdated diagrams MUST be corrected or deleted; they MUST NOT be left as historical references.
- Each diagram MUST have a `title` directive and a meaningful title.
- Sequence diagrams MUST use participant labels that match the actual module/class names in the
  codebase, not generic names like "ServiceA".
- State charts MUST label every transition with the triggering event or guard condition.
- Class diagrams MUST show visibility modifiers (`+` public, `-` private, `#` protected) and
  MUST include the most important methods, not just fields.
- No diagram MUST exceed what can be rendered legibly at 1920×1080 resolution. Large diagrams
  MUST be split into focused sub-diagrams.

**Rationale**: In a system as complex as the Basis Consumer (SICCT terminal pairing, multi-key-source
routing, multi-tenant SOAP, TI compliance), prose specifications alone are insufficient for onboarding,
code review, and audit. Diagrams reduce the risk of misimplementation, enable faster review of
architectural decisions, and provide auditors with an unambiguous view of security-relevant flows
(e.g., EHEALTH AUTHENTICATE, remote-PIN, CryptoProvider routing).

### VII. External Library Evaluation & Security Review

Before implementing any significant functionality from scratch, the team MUST evaluate whether a
well-maintained external library already provides the required capability. Custom implementations of
cryptographic operations, PKI validation, ASN.1 encoding, TLS, or TI-protocol handling introduce
unnecessary risk when proven, audited alternatives exist.

#### Evaluation mandate

- **Evaluate before building**: For any non-trivial functionality — particularly cryptography, PKI,
  certificate handling, ASN.1, smart-card protocols, or TI connector operations — the team MUST
  document an evaluation of at least one established external library covering the same capability
  before writing a custom implementation. This evaluation MUST appear in `research.md` (Phase 0).
- **Security review required**: Every evaluated external library MUST be assessed on ALL of the
  following dimensions before adoption:
  - Active maintenance: last commit within 12 months; named maintainer or sponsoring organisation
  - Known CVEs: query NVD and OSS-Index; no unpatched CVSS ≥ 7 vulnerabilities
  - License compatibility: license is permissive (Apache 2, MIT, BSD) or LGPL with dynamic linking;
    GPL requires explicit legal sign-off
  - Certification status: where the library performs TI-regulated functions, verify gematik approval
    or alignment with a normative specification (e.g., gemSpec_Krypt)
  - Supply-chain integrity: signed releases, checksums or SLSA provenance available; artifact hosted
    on Maven Central or an equivalent audited registry
- **Use when secure**: If a library passes the security review, it MUST be preferred over a custom
  implementation. Custom implementations are permitted only when no library passes the review, or when
  a mandated architectural constraint (see spec Architectural Mandates) explicitly requires custom code.
  The decision (adopt or reject + rationale) MUST be recorded in `research.md`.

#### Known-good library candidates

The following libraries have established track records in the TI ecosystem and MUST be the first
candidates evaluated for their respective domains:

| Domain | Library | Evaluation trigger |
|--------|----------|--------------------|
| Cryptography (JCE) | **Bouncy Castle** (`org.bouncycastle`) | Any symmetric/asymmetric cipher, ECDSA, RSA, OCSP, CMS, PKCS#7/12 |
| TI PKI validation | **gematik/ref-GemLibPki** | Certificate validation, OCSP, TI trust anchor management |
| ASN.1 encoding/decoding | **beanit jASN1** (mandated by spec) | All SICCT ASN.1 structures (architectural mandate) |
| SOAP / WSDL | **Apache CXF** (mandated by spec) | All consumer and konnektor SOAP interfaces |
| Smart-card / PKCS#11 | **SunPKCS11 / IAIK** | HSM and card communication |

This list is not exhaustive. Any library not listed above MUST still be evaluated using the security
review criteria above before adoption.

#### SBOM and monitoring obligations

- Every adopted external library MUST appear in the project SBOM (Principle V §Supply Chain) with
  its version, license, and a reference to the documented security review outcome.
- Adopted libraries are subject to the vulnerability monitoring obligations of Principle V §Vulnerability
  Management. A library that receives a CVSS ≥ 7 CVE MUST be patched or replaced within the SLA
  defined in Principle V — there is no exception for third-party libraries.
- If a library's maintenance status changes (project archived, maintainer abandoned), the team MUST
  initiate a replacement evaluation within 30 days of detection.

**Rationale**: Custom cryptographic and PKI implementations are a primary source of security
vulnerabilities in healthcare IT systems. Established libraries such as Bouncy Castle and gematik's
own ref-GemLibPki are maintained by domain experts, regularly audited, and aligned with TI
certification expectations. Reusing them reduces implementation risk, accelerates delivery, and
ensures alignment with gematik reference implementations — directly supporting the SSDLC obligations
in Principle V and the supply-chain requirements of [A_27430].

## Quality Gates

Every pull request MUST pass all of the following gates before merge is permitted:

- Linter and formatter: zero warnings, zero suppressions without inline rationale
- Unit test suite: green, coverage ≥ 80%
- Integration tests: green (where applicable per Principle II)
- Performance benchmarks: no regression > 15%
- Accessibility audit: zero WCAG 2.1 AA violations on changed screens
- Security scan (SAST/SCA): no new HIGH or CRITICAL findings [GS-A_4945-01, A_19156]
- OWASP Top 10 compliance check: no regressions against current or two prior reports [A_17178]
- Design system review: any new UI component has explicit design-system sign-off
- New third-party dependency: SBOM entry added, license and security status verified [A_27431]
- Security defect gate: no MEDIUM, HIGH, or CRITICAL open security defects at release time [A_19159]
- Afo traceability: every Afo commit references its Afo ID + Jira ticket; every Afo has a named test
- UML diagrams: any architectural or protocol change MUST include updated or new `.puml` files;
  `diagrams/README.md` MUST be updated; diagrams MUST render without errors [Principle VI]
- External library evaluation: for any new significant functionality, a documented library evaluation
  MUST exist in `research.md` before a custom implementation is merged; if a library was rejected,
  the rejection rationale MUST be present; adopted libraries MUST appear in the SBOM [Principle VII]

Manual exceptions to any gate require written approval from a tech lead and MUST be tracked as a
follow-up ticket with a resolution deadline within two sprints.

## Development Workflow

- **Branching**: All work happens on short-lived feature branches. Branches MUST be named following the project convention (`[TICKET-ID]-short-description`). Long-lived branches are prohibited.
- **PR size**: Pull requests SHOULD target fewer than 400 changed lines. Larger PRs require justification and MUST be split where logically separable.
- **Review turnaround**: PRs MUST receive a first review within one business day of opening. Stale PRs (no activity > 3 business days) are escalated.
- **Definition of Done**: A feature is done when: all acceptance scenarios pass, all quality gates are green, documentation is updated (including UML diagrams and library evaluation in research.md), and the feature is observable in production (metrics/logging confirmed).
- **Rollback readiness**: Every deployment MUST be reversible within 10 minutes. Features with no rollback path require a feature flag before production deployment.

## Governance

This constitution supersedes all other written and verbal engineering norms within the Basis Consumer Health project. Where a conflict exists between this document and another guideline, this document takes precedence unless a formal amendment has been ratified.

**Amendment procedure**:
1. Propose amendment as a pull request to `.specify/memory/constitution.md` with a clear rationale section.
2. Amendment requires approval from at least two tech leads and one product representative.
3. Breaking changes to principles (MAJOR version bumps) require a team-wide review and a migration plan for existing code that no longer complies.
4. After ratification, dependent templates (plan, spec, tasks) MUST be reviewed and updated in the same PR or an immediately following one.

**Versioning policy**:
- MAJOR: Principle removed, redefined in a backward-incompatible way, or governance structure changed fundamentally.
- MINOR: New principle or section added, or existing principle materially expanded.
- PATCH: Wording clarifications, typo fixes, non-semantic refinements.

**Compliance review**: Constitution compliance is reviewed quarterly. The review produces a compliance report noting any systemic violations, drift in test coverage trends, performance budget adherence, UX consistency findings, gematik TI security requirement adherence (Principle V), UML diagram currency (Principle VI), and library evaluation completeness (Principle VII — checking that research.md artifacts document the required evaluations and that adopted libraries appear in the SBOM).

**Version**: 1.5.0 | **Ratified**: 2026-06-05 | **Last Amended**: 2026-06-06
