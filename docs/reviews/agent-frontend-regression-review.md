# Frontend Regression Review

## Scope

- Base: `4b87dfc887f6402ef4b40bad47408a9e848e1375`
- Reviewed target: current working tree, including tracked changes under `web/` and `pdf-service/`
- Explicitly included untracked frontend/PDF files:
  - `web/scripts/check-i18n.mjs`
  - `web/scripts/check-i18n.test.mjs`
  - `web/src/components/resume/ResumePaper.vue`
  - `web/src/resume/sectionRegistry.ts`
  - `web/src/types/resume.ts`
- Requirements source: `docs/plans/2026-07-27-002-remaining-audit-closure.md` (R1-R6, U1-U5)
- Mode: review only. No application source was modified, staged, or committed. The full test suite and opt-in local-service suite were not run by this review.

## Findings

### P2 - Material-library load failures have no user-visible error

- severity: `P2`
- file:line: `web/src/views/ResumeEditorView.vue:268`
- concrete scenario/reproduction chain:
  1. Open the resume editor and click "Add from materials".
  2. Make `GET /api/career-materials` fail with a network error or 5xx response.
  3. `listMaterials()` rejects inside the event handler.
  4. The `finally` block clears the loading state, but no `catch` updates the page error state.
  5. The picker does not open and the user receives no explanation; Vue also reports an unhandled event-handler rejection.
- first_evidence: `web/src/views/ResumeEditorView.vue:268 - try { materialLibrary.value = (await listMaterials()).data.data } finally { materialLibraryLoading.value = false }`
- why: This is a normal recoverable API failure on a newly added editor workflow. Silently returning the button to its idle state makes the action appear to have done nothing and gives the user no basis for retrying.
- suggested_fix: Add a localized material-library load error key, catch the request failure, set `error` only for the current resume context, and add a focused failure/retry browser test.
- requires_verification: `true`

### P2 - Async editor failures can leak into a different resume route

- severity: `P2`
- file:line: `web/src/views/ResumeEditorView.vue:299`
- concrete scenario/reproduction chain:
  1. On resume A, start either a material insertion request or a version save.
  2. While the request is pending, navigate to resume B in the reused editor component and accept the dirty-navigation confirmation when applicable.
  3. Let the request for resume A fail after resume B has loaded.
  4. The insertion catch at line 299 or save catch at line 599 writes to the component-wide `error` ref without checking the snapshotted resume ID.
  5. Resume B displays a material-insert or save failure even though no such action was performed on B. During a pending insertion, B also inherits A's loading state until the old request settles.
- first_evidence: `web/src/views/ResumeEditorView.vue:299-300 - } catch { error.value = t('resumeEditor.materialInsertFailed') }`
- why: The success paths now guard against cross-route completion, but the failure paths do not. A late response therefore mutates the newly loaded editor context and can prompt the user to retry an operation against the wrong mental model.
- suggested_fix: Snapshot the resume ID for every async editor action and gate error/loading mutations on `props.id === targetResumeId`. Reset route-scoped request state in the ID watcher. Extend the pending-save and material-insertion route-switch tests with rejected responses.
- requires_verification: `true`

## Requirements Coverage

- R1/R2: The current production Vue surface passes the focused i18n guard. The guard self-tests now cover static visible text, runtime message sinks, bound visible attributes, duplicate locale keys, and locale-key parity.
- R3: The typed Web contract, 14-key registry, shared `ResumePaper`, shared all-section fixture, object-form location normalization, multiple custom sections, and saved-order assertions are present. Web and PDF tests cover the shared fixture independently.
- R4: The opt-in local-service flow contains visible, unmocked ATS, application, communication, interview, report, and answer-asset steps. Execution evidence was not reproduced in this review.
- R5: The browser import handoff consumes and removes corrected text from `sessionStorage`; server-side fixture coverage is outside this frontend-focused report.
- R6: Changed Web API boundaries for export, resume version summaries, and system health are typed. No frontend contract regression was confirmed in the reviewed callers.

## Residual Risks

- `web/src/views/ResumeEditorView.vue:297`: projecting an `ACHIEVEMENT` into a custom section retains a synthesized description/highlight but drops structured fields such as scenario, action, period, metric name, and exact metric value. Product intent for this lossy projection is not explicit enough to classify it as a defect.
- `web/src/types/resume.ts` does not model the legacy/generated `period` field still used by `GenerationConfirmView.vue` and the AI generation prompt. Web and PDF renderers also derive dates from `startDate`/`endDate`, so generated period-only entries may remain invisible. This predates the renderer extraction but weakens the claim that the new type fully mirrors persisted JSON.
- `resolveSectionOrder()` accepts duplicate known keys. Current CSS ordering and PDF de-duplication preserve relative visual order, but the Web/PDF normalization algorithms are not identical for malformed saved order arrays.
- The executor handoff report contains stale test counts after later test additions. This does not change runtime behavior, but it should not be treated as final reproducible evidence until refreshed.

## Testing Gaps

- `npm run test:e2e:local` was not run. The real Web/Spring/database/PDF integration path therefore remains unverified in this review.
- The answer-asset local-service assertion proves that a matching asset is visible after selecting a JD, but does not create an unrelated asset and prove it is filtered out.
- Material-library E2E coverage does not exercise list failure, insert failure/retry, duplicate clicks, or rejected completion after a route switch.
- The pending-save route-switch test covers successful completion only; it does not cover a rejected request writing stale error state into the new resume.
- The sample-resume test verifies that save becomes enabled, but does not submit and inspect the serialized payload or exercise overwrite confirmation for an already dirty editor.
- No single browser scenario drives all seven templates through editor save, detail/version summary, export request, and resulting PDF output.
- Resume import browser coverage proves the TXT correction handoff only. PDF/DOCX and unsupported, corrupt, MIME-mismatched, and oversized rejection behavior are covered below the browser layer rather than through the UI.
- `ResumePaper` has no focused component-level matrix for malformed/duplicate section order, both location shapes, all highlight shapes, and multiple custom-section edge cases; current coverage is concentrated in one Playwright fixture.

## Verification Performed

- `web: npm run check:i18n` - passed: 8 self-tests; 30 Vue files; 2 locales.
- Additional focused reviewer runs reported green results for `web/e2e/workflow.spec.ts` and PDF template tests; this report does not claim a full-suite rerun.
- No source fixes, staging, commits, or local-service execution were performed.

---

## Verdict

`Ready with fixes.` Address the silent material-library failure and route-scoped async error leakage before treating the editor workflow as regression-complete. The remaining items are evidence gaps or compatibility risks rather than confirmed frontend regressions.
