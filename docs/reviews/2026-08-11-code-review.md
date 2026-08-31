# Code Review Results

**Scope:** current working tree relative to `HEAD` (18 tracked implementation and test files). The review also inspected the two new untracked browser suites, `web/e2e/home-workspace.spec.ts` and `web/e2e/route-loading.spec.ts`. The user-owned interview handbook was excluded.

**Intent:** review the job-search workspace redesign: home next-action priority, ATS-to-editor handoff, resume version safety, navigation, lazy routes, and related browser coverage.

**Mode:** report-only. No implementation files were changed by this review.

**Reviewers:** correctness, API-contract, frontend async/reliability, testing, maintainability, and standards.

- API-contract: the ATS response gained fields consumed by the handoff.
- Frontend async/reliability: this change introduces route-level dynamic imports and query-driven editor reloads.
- Correctness/testing: the change crosses user-owned version history, dashboard state, and browser flows.

### Triage Groups

| Group | Findings | Context | Preferred Resolution | Why |
|-------|----------|---------|----------------------|-----|
| ATS handoff integrity | #1, #2, #4 | Editor context and editable state are derived from URL query values and an extended ATS response. | Apply #1 first, then cover nullable API data in #2; add the dirty-query regression test in #4. | This keeps the displayed diagnosis, source version, and editable target consistent. |
| Lazy-route deployment recovery | #3 | Route-level code splitting introduces hashed chunks for all authenticated feature views. | Add one bounded stale-chunk recovery path and test it. | A successful deep-link test does not cover an already-open tab after an asset rollout. |

### P2 -- Moderate

| # | File | Issue | Reviewer | Confidence |
|---|------|-------|----------|------------|
| 1 | `web/src/views/ResumeEditorView.vue:636` | A query-supplied current version can bypass the historical ATS read-only path. | correctness, testing | 97 |
| 2 | `web/src/api/ats.ts:5` | Client type requires `resumeId`, while an `ANALYZING` server response returns `null`. | correctness, API-contract, testing | 99 |
| 3 | `web/src/router/index.ts:10` | Lazy route imports have no stale-chunk recovery after a deployment. | frontend reliability | 90 |
| 4 | `web/src/views/ResumeEditorView.vue:475` | A same-resume query update reloads the editor without applying the dirty-navigation guard. | testing | 96 |

- **#1** An owned historical ATS link can be modified to include `editVersionId=<currentVersionId>`. The editor treats any current version as editable without verifying that it is the explicit successor of the analyzed version. Verify the successor's `restoredFromVersionId` lineage before unlocking, or derive editability from trusted persisted state. Add a negative browser test for the injected query parameter.

- **#2** `AtsService` intentionally returns `null` for `resumeId` while analysis is pending, but the frontend declares it as `number` and `editorLocation()` has no absent-ID guard. Either expose a stable ID throughout the lifecycle or declare `resumeId: number | null`, guard consumers, and assert the pending response in the integration test.

- **#3** A browser tab with an old entry bundle can request a removed hashed chunk after deployment; the dynamic import rejects and there is no `router.onError` recovery or visible retry path. Add a once-only, session-guarded reload for recognized chunk-load failures, then expose the normal error state if it fails again. Add a browser test that forces one dynamic import to fail.

- **#4** The route guard returns `true` whenever the resume ID is unchanged, while the new handoff-query watcher immediately clears draft state and reloads the editor. Preserve the confirmation/recovery behavior when handoff query values change and the editor is dirty. This is most relevant to same-component navigation and browser history; add a focused regression test.

### Requirements Completeness

| Requirement / unit | Status | Evidence |
|--------------------|--------|----------|
| R1, R2 / U1 contextual home workspace | Met | Authenticated priority states, empty/error states, and guest example labeling are implemented and covered by `web/e2e/home-workspace.spec.ts`. |
| R3, R4 / U3 ATS-to-editor handoff | Partially addressed | Mapped links, ownership checks, focus transfer, diagnostics, readonly history, and successor creation are implemented; #1 and #4 leave version and dirty-state boundaries incomplete. |
| R5 / U2 task-oriented navigation | Met | Grouped labels, deep-route active state, keyboard paths, and mobile parity are covered in `web/e2e/workflow.spec.ts`. |
| R6 / U4 route-level loading | Partially addressed | Production build produces per-view chunks and deep-link tests pass; #3 leaves stale deployment recovery unhandled. |
| R7 / U1-U5 localization and accessibility | Met for reviewed coverage | i18n guard, mobile assertions, focus behavior, and reduced-motion CSS are present; manual keyboard review is not automated evidence. |

### Coverage

- Passed: `web/npm run check:i18n`.
- Passed: `web/npm run build`; it emitted separate feature-view chunks, including `ResumeEditorView`, `AtsCheckView`, and `GenerationConfirmView`.
- Passed: `web/npx playwright test e2e/ats-ai.spec.ts e2e/home-workspace.spec.ts e2e/route-loading.spec.ts --workers=1` (39 passed).
- Passed: `server/mvn -Dtest=AtsControllerIT test` (4 passed).
- Passed: `git diff --check HEAD`.
- Residual risk: the home page uses the full application list, including long draft text, solely to select a status and recent item. This is an existing API-scope tradeoff rather than a correctness defect; consider a summary-shaped read contract if application records become large or numerous.
- Test gaps: add the four negative scenarios named in #1 through #4. The review intentionally did not claim full local-service or manual responsive/keyboard verification from the mock-backed test results.
- Excluded from formal tracked-diff scope: the two new untracked E2E suites were inspected and executed; the user-owned interview handbook was not inspected or changed.

---

> **Verdict:** Ready with fixes
>
> **Reasoning:** The main redesign compiles and its exercised paths pass, but four moderate boundary conditions remain. The most important is preventing query manipulation from detaching an editable version from the historical ATS evidence it displays.
>
> **Fix order:** #1 historical-version lineage -> #2 nullable ATS contract -> #4 dirty query navigation -> #3 stale lazy-chunk recovery.

### Actionable Findings

| # | Severity | File | Issue | Route |
|---|----------|------|-------|-------|
| 1 | P2 | `web/src/views/ResumeEditorView.vue:636` | Validate the editable successor against the ATS source version. | `gated_auto -> downstream-resolver` |
| 2 | P2 | `web/src/api/ats.ts:5` | Make the pending ATS response contract nullable or consistently populated. | `gated_auto -> downstream-resolver` |
| 4 | P2 | `web/src/views/ResumeEditorView.vue:475` | Preserve dirty confirmation/recovery on same-editor query changes. | `manual -> downstream-resolver` |
| 3 | P2 | `web/src/router/index.ts:10` | Add bounded stale-chunk recovery and a failure-path test. | `gated_auto -> downstream-resolver` |
