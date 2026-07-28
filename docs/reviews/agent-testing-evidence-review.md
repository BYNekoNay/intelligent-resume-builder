# Testing and Evidence Regression Review

## Scope

- Base: `4b87dfc887f6402ef4b40bad47408a9e848e1375`
- Branch: `codex/complete-resume-workflows`
- Plan: `docs/plans/2026-07-27-002-remaining-audit-closure.md`
- Included tracked changes and every path returned by `git ls-files --others --exclude-standard` at review time.
- Review only: the testing agent did not modify product source or tests.

## Findings

### P1: R4 was claimed complete without a successful real-service smoke run

- File: `docs/reviews/2026-07-27-remaining-audit-closure-report.md`
- Evidence: `web/e2e/local-services.spec.ts` skips unless `LOCAL_E2E=true`; the prior report explicitly deferred `npm run test:e2e:local`.
- Impact: code presence does not prove the Web, Spring, database, AI provider, and PDF service interoperate.
- Required action: record the real run and keep R4 partial unless all required journeys pass.

### P1: The verification snapshot was stale

- File: `docs/reviews/2026-07-27-remaining-audit-closure-report.md`
- Evidence: the report used older i18n, Playwright, and InlineOptimize counts than the reviewed worktree.
- Required action: rerun all final gates and replace the counts.

### P1: The exact file inventory was incomplete

- File: `docs/reviews/2026-07-27-remaining-audit-closure-report.md`
- Evidence: the report listed selected files rather than the complete tracked and untracked inventory.
- Required action: add exhaustive tracked and untracked appendices.

### P2: The i18n guard missed literals in bound visible attributes

- File: `web/scripts/check-i18n.mjs`
- Reproduction: `findVisibleLiterals('<template><input :placeholder="\'Full name\'" /></template>')` returned no finding.
- Required action: inspect literal expressions in bound `alt`, `aria-label`, `placeholder`, and `title` attributes and add self-tests.

### P2: V18 did not backfill or enforce historical round numbers

- File: `server/src/main/resources/db/migration/V18__interview_record_round.sql`
- Evidence: the reviewed migration added a nullable column and unique index without migrating V17 data, while the entity declared the field non-null.
- Required action: deterministically backfill by `created_at, id`, enforce `NOT NULL`, and add an upgrade test.

### P2: The answer-asset filter test proved inclusion but not exclusion

- File: `web/e2e/local-services.spec.ts`
- Evidence: the flow asserted the saved answer was visible after filtering but created no unrelated asset.
- Required action: add an unrelated asset and assert it disappears under the JD filter.

## Requirements Completeness At Review Time

| Item | Status | Reason |
| --- | --- | --- |
| R1 / U2 copy cleanup | Complete | Catalog and language-switch coverage present. |
| R2 i18n guard | Partial | Bound-attribute escape path above. |
| R3 / U1 resume contract | Complete | Shared fixture, registry, Web and PDF coverage present. |
| R4 / U3 local services | Partial | Real AI-dependent smoke did not complete; filter assertion was weak. |
| R5 / U4 import | Complete | TXT/PDF/DOCX and invalid input coverage present. |
| R6 / U5 DTO closure | Complete | Validated DTO and ownership/consent tests present. |
| R7 | Complete | External controls remained deferred. |
| R8 / U6 evidence | Partial | Counts and exact file inventory were stale/incomplete. |

## Verification Performed By Testing Agent

- i18n guard: 8/8; 30 Vue files and 2 locales.
- Focused Web workflow: 31/31.
- Focused Server high-risk suite: 36/36.
- PDF: 9/9.
- `git diff --check`: pass with line-ending warnings only.

The testing agent did not run the full Server/Web suites or the real-service smoke; the primary reviewer owns final reruns and resolution evidence.

## Residual Risks

- The local-service smoke remains environment-dependent and absent from CI.
- Backup restore, alert delivery, and capacity validation require real environment evidence.
- Export cleanup and AI heartbeat have unit/database coverage but no full failure-injection production exercise.
- Import extension/MIME checks do not constitute file-magic validation for TXT.

## Verdict

`Not ready` at review time. Close the i18n, V18, filter, final-count, and inventory findings; keep R4 partial until a credentialed real-service run succeeds.
