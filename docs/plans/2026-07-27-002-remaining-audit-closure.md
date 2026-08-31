---
title: Remaining Audit Closure and Regression Evidence
created_at: 2026-07-27
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: 2026-07-27-audit-followup
execution: code
---

# Remaining Audit Closure and Regression Evidence

## Goal Capsule

- Objective: close the implementation and evidence gaps that remain after `docs/2026-07-27-全项目实现一致性与质量审计.md` and the first remediation pass.
- Authority: the audit report, current code, and this plan define scope; do not revert existing uncommitted work.
- Executor: a delegated implementation AI performs U1-U6 and writes the required handoff report.
- Reviewer: the primary reviewer validates the report against the worktree, reruns the stated regression gates, and rejects unsupported claims.
- Stop condition: all requirements and evidence items are complete, or the report names a concrete external blocker with the evidence already collected.

---

## Product Contract

### Problem Frame

The first remediation pass fixed the high-impact placeholder APIs, editor/PDF/template consistency, and core interview flow.
The remaining risks are primarily cross-surface evidence gaps: user-facing locale drift still exists in seven Vue files, resume shape ownership is not explicit across Web/API/PDF, local-service E2E does not cover every peripheral module, import coverage only proves TXT, and a few public controllers still expose untyped `Map<String, Object>` boundaries.

### Requirements

- R1. All remaining user-visible text in `web/src/views` and `web/src/components` must resolve through `useLocale`, with Chinese and English values for every added key.
- R2. The i18n guard must protect the entire user-facing Vue surface and distinguish legitimate data/examples/comments from visible template and runtime text.
- R3. Resume rendering must have a named, typed Web contract and a single section registry that drives editor navigation and `ResumePaper`; PDF behavior must be contract-tested against the same section fixture and saved `layout.sectionOrder`.
- R4. Real local-service smoke coverage must exercise ATS, application persistence/status transition, communication draft handoff, interview completion/report, and answer-asset filtering without API route mocks.
- R5. Resume import must prove extraction and normalization for valid TXT, PDF, and DOCX fixtures; invalid/corrupt inputs and the size/type policy must be covered.
- R6. Public JSON endpoints must use explicit request DTOs where their payload is currently an unbounded `Map<String, Object>`, without weakening input validation or AI consent/ownership checks.
- R7. External production claims must remain evidence-based: backup restore, alert delivery, and capacity verification are reported as verified only with actual environment evidence.
- R8. The executor must write a reviewer-oriented completion report with exact changed files, commands, test results, unresolved risks, and an R1-R8 evidence matrix.

### Scope Boundaries

- Do not redesign scoring, PDF queueing, authentication, or the already-completed application/ATS/communication/interview domains unless a regression test proves a defect.
- Do not claim a production operational control is complete from repository configuration alone.
- Do not introduce a new shared package or dependency merely to share a handful of constants; use existing project structures unless a real schema boundary requires more.
- Do not remove safety checks, user ownership checks, file-size checks, escaping, or explicit confirmation semantics during refactoring.

---

## Planning Contract

### Key Technical Decisions

- KTD-1. Use a Web-local typed `ResumeDocument` and section registry first. The Java/PDF services remain independently validated through fixtures and contract tests instead of introducing a cross-language generated-schema tool in this pass.
- KTD-2. Keep the full runtime i18n catalog as the source of user copy. The guard should scan Vue templates and direct runtime user-message assignments, not arbitrary Chinese source data, comments, test fixtures, or backend messages.
- KTD-3. Local-service smoke tests remain opt-in through `LOCAL_E2E=true`; add a documented CI-capable invocation only after it can start deterministic local dependencies without real AI credentials.
- KTD-4. Resume import tests use small committed test fixtures generated in test setup or stored under the test resources tree. Do not commit personal resumes or source documents.
- KTD-5. Replace raw controller maps with narrowly shaped DTOs at the HTTP edge. Preserve flexible internal AI task input only behind an explicit DTO field when the payload is intentionally extensible.

### Sequencing

U1 establishes the resume contract before U2 touches editor components.
U3 and U4 independently improve service evidence and import safety.
U5 removes edge-boundary weak types after its callers are characterized.
U6 runs after the code changes and is required before the handoff report.

---

## Implementation Units

### U1. Resume Contract and Section Registry

- Goal: make the Web resume document shape and section ordering explicit and reusable.
- Files: add `web/src/types/resume.ts` and `web/src/resume/sectionRegistry.ts`; update `web/src/views/ResumeEditorView.vue`, `web/src/components/resume/ResumePaper.vue`, and related preview tests.
- Patterns: preserve current serialized JSON compatibility and `layout.sectionOrder`; follow the existing `ResumePaper` renderer rather than recreating a second display tree.
- Test scenarios:
  - all fourteen standard sections plus multiple custom sections render in the declared order;
  - unknown/missing optional sections do not crash preview;
  - editor mutations continue to serialize the same persisted keys;
  - existing template selection and draft recovery tests pass unchanged.
- Verification: R3, U2.

### U2. Full i18n Cleanup and Guard

- Goal: remove remaining visible hard-coded text from the seven identified Vue files and enforce the rule for all user-facing Vue components.
- Files: `web/src/components/DraftContentFields.vue`, `web/src/views/CareerMaterialView.vue`, `web/src/views/GenerationConfirmView.vue`, `web/src/views/GenerationWorkbenchView.vue`, `web/src/views/HomeView.vue`, `web/src/views/MaterialSelectionConfirmView.vue`, `web/src/i18n/index.ts`, and `web/scripts/check-i18n.mjs`.
- Patterns: use `t()`/`message()` in script setup and templates; retain literal domain data only when it is not a translated user message.
- Test scenarios:
  - guard fails on a deliberately injected template literal and direct confirmation/error string;
  - guard passes the production source tree;
  - the principal screens switch between Chinese and English without falling back to key names.
- Verification: R1, R2.

### U3. Real-Service Smoke Completion

- Goal: add deterministic real-service coverage for the peripheral modules omitted by the current local browser flow.
- Files: `web/e2e/local-services.spec.ts`, service startup/validation scripts only when needed, plus any focused Spring controller test that closes a browser-only blind spot.
- Patterns: use visible UI interactions and no route mocking; keep credentials and local host allowlisting behavior intact.
- Test scenarios:
  - ATS produces the complete visible result contract for an owned resume/JD pair;
  - an application persists after reload and accepts a valid status transition;
  - a three-round interview reaches completion, shows the report, and its saved asset appears under its JD filter;
  - cross-user resources remain absent through API-level integration tests.
- Verification: R4.

### U4. Import Fixture Coverage and Safety Characterization

- Goal: prove TXT/PDF/DOCX extraction rather than only the TXT path.
- Files: `server/src/test/java/com/intelligentresume/imports/controller/ResumeImportControllerIT.java`, test fixture helpers/resources, and `ResumeImportService` only if tests expose unsafe behavior.
- Patterns: use in-memory generated minimal PDF/DOCX where practical; preserve the no-original-file-persistence rule.
- Test scenarios:
  - each valid format extracts expected text and normalized name/email/phone fields;
  - corrupt PDF/DOCX return validation errors without 500 responses;
  - unsupported extension, MIME mismatch, and over-limit files are rejected;
  - extraction does not create a file outside the test request lifecycle.
- Verification: R5.

### U5. HTTP Edge DTO Closure

- Goal: replace raw request maps in public optimize endpoints with validated DTOs while retaining intended extensibility.
- Files: `server/src/main/java/com/intelligentresume/ai/optimize/controller/InlineOptimizeController.java`, new DTOs under `ai/optimize/dto`, relevant Web API callers, and controller tests.
- Patterns: mirror existing record DTO validation and `ApiResponse` conventions; do not expose provider-specific prompt fields to the browser.
- Test scenarios:
  - valid field/section optimize requests still enqueue the same task type;
  - malformed or oversized payloads return `40001`;
  - unauthenticated and consent-withdrawn requests retain current behavior.
- Verification: R6.

### U6. Evidence, Documentation, and Handoff Report

- Goal: produce auditable completion evidence and update only documentation that has become inaccurate.
- Files: add `docs/reviews/2026-07-27-remaining-audit-closure-report.md`; update README/project context/deployment readiness only for verified behavior.
- Patterns: report facts and command output summaries, never aspirations; mark operational environment evidence separately.
- Test scenarios:
  - report links every R1-R8 to a code/test/command evidence source;
  - all modified project validation commands are run;
  - report identifies remaining external operational checks as verified, deferred, or blocked.
- Verification: R7, R8.

---

## Verification Contract

| Gate | Applies to | Evidence of success |
| --- | --- | --- |
| `server: mvn test` | U3-U5 | Entire Spring suite passes with Flyway migrations. |
| `web: npm run build` | U1-U2 | Typecheck, Vite build, and full i18n guard pass. |
| `web: npx playwright test` | U1-U3 | Mock regression suite passes; skipped local tests are reported. |
| `web: npm run test:e2e:local` | U3 | Real local-service suite passes when required local dependencies are started. |
| `pdf-service: npm run check && npm test` | U1 | Renderer and template contract tests pass. |
| `git diff --check` | All | No whitespace errors. |
| Manual report audit | U6 | Reviewer can trace each claim to a file, test, or command result. |

---

## Definition of Done

- U1-U6 are implemented without regressing existing completed workflows.
- Every R1-R8 has direct evidence in the implementation report.
- The report is written to `docs/reviews/2026-07-27-remaining-audit-closure-report.md` using the required format below.
- No production backup, alert, or capacity statement is marked complete without external evidence; unresolved items are explicit residual risks.
- The reviewer can reproduce all non-external validation gates from the report.

---

## Appendix

### Required Executor Report Format

The delegated AI must create `docs/reviews/2026-07-27-remaining-audit-closure-report.md` before handoff.

```markdown
# Remaining Audit Closure Report

## Scope
- Plan: `docs/plans/2026-07-27-002-remaining-audit-closure.md`
- Base commit and final commit/worktree state:
- Explicitly excluded scope:

## Changes
| Requirement | Files | Behavioral result |
| --- | --- | --- |
| R1 | ... | ... |

## Verification
| Command | Result | Notes |
| --- | --- | --- |
| ... | pass/fail/skipped | exact count or reason |

## Requirement Evidence
| Requirement | Code/Test Evidence | Status |
| --- | --- | --- |
| R1 | `path:line` | complete/partial/blocked |

## Review Notes
- Decisions made and rationale:
- Compatibility risks:
- Security/privacy checks retained:

## Residual Risks and External Evidence
- Backup restore: verified/deferred/blocked, with evidence or owner.
- Alert delivery: verified/deferred/blocked, with evidence or owner.
- Capacity validation: verified/deferred/blocked, with evidence or owner.

## Reviewer Handoff
- Exact regression commands the reviewer should rerun:
- Files requiring focused review:
- Known failures or skipped tests and why:
```
