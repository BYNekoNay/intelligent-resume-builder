---
title: Job Search Workspace Review Remediation
created_at: 2026-08-11
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-plan-bootstrap
execution: code
---

# Job Search Workspace Review Remediation

## Goal Capsule

- Objective: resolve the four verified boundary defects in the job-search workspace review without changing the confirmed product behavior for ATS history or dashboard priority.
- Authority: `docs/reviews/2026-08-11-code-review.md`, `docs/plans/2026-08-10-001-design-optimization.md`, current code, and the product decisions already confirmed in this task.
- Scope: ATS handoff version lineage, pending ATS response typing, editor dirty-route protection, lazy-route rollout recovery, and their focused tests.
- Stop condition: an ATS handoff can edit only an explicit successor, client types represent every server state, editor query navigation preserves unsaved work, stale lazy chunks recover once, and all focused checks pass.

---

## Product Contract

### Problem Frame

The redesign correctly adds contextual ATS editing and route-level splitting, but URL-derived state can currently detach an editable version from its analyzed source, and two new asynchronous boundaries are not fully represented or recoverable.

### Requirements

- R1. A historical ATS handoff remains read-only unless the loaded current version is the explicit successor restored from that handoff's source version.
- R2. Every ATS response state has an accurate frontend type and consumers do not construct an editor route from an absent resume ID.
- R3. Changing handoff query parameters in the active editor preserves the existing dirty-navigation decision and draft behavior.
- R4. A failed lazy feature chunk after deployment performs at most one bounded recovery reload and does not create a reload loop.
- R5. Existing mapped handoffs, ownership checks, focus transfer, localization, and route guards retain current behavior.

### Scope Boundaries

- Do not change ATS scoring, AI output, authorization rules, resume restore semantics, or the confirmed historical read-only product policy.
- Do not introduce a new persistence table, backend aggregation API, or third-party runtime dependency.

---

## Planning Contract

### Key Technical Decisions

- KTD-1. Treat `editVersionId` as navigation context only. The editor loads it as editable only when it is the resume's current version and its `restoredFromVersionId` equals the ATS `sourceVersionId`; otherwise it loads the source version read-only.
- KTD-2. Model the backend's current pending-state contract directly as `resumeId: number | null`, and make editor-link construction return no link until the value exists.
- KTD-3. Use the existing `onBeforeRouteUpdate` guard for query-only editor navigation. A dirty editor must confirm before the existing query watcher clears draft state and reloads content.
- KTD-4. Register a Vue Router error handler for recognized dynamic-import failures. Persist a route-specific once-only marker in `sessionStorage`, reload once, and surface the normal router error path on a repeated failure.

### Sequencing

U1 first constrains ATS version lineage, then U2 makes the API boundary explicit. U3 preserves editor draft behavior around the same route state. U4 adds lazy-chunk recovery after the normal route behavior is stable. U5 proves the four failure paths together.

---

## Implementation Units

### U1. Verify ATS Editable Successor Lineage

- **Goal:** prevent a hand-edited `editVersionId` from opening an unrelated current version as editable while retaining historical ATS context.
- **Files:** `web/src/views/ResumeEditorView.vue`, `web/e2e/ats-ai.spec.ts`.
- **Patterns:** reuse `ResumeVersion.restoredFromVersionId`, the existing ATS result ownership/source validation, and the current `createEditableSuccessor` route replacement.
- **Test scenarios:** a valid created successor becomes editable; `editVersionId` equal to an unrelated current version remains on the historical source and read-only; a malformed or unavailable edit version falls back safely without losing the ATS return path.
- **Verification:** R1, R5.

### U2. Align Pending ATS Contract

- **Goal:** represent the nullable pending `resumeId` state consistently across server response tests and the frontend client.
- **Files:** `web/src/api/ats.ts`, `web/src/views/AtsCheckView.vue`, `server/src/test/java/com/intelligentresume/ats/controller/AtsControllerIT.java`, `web/e2e/ats-ai.spec.ts`.
- **Patterns:** retain the current decision not to resolve a resume ID while `analysisStatus` is `ANALYZING`; make all route construction explicitly conditional on a present ID.
- **Test scenarios:** an analyzing response has the documented nullable shape; it exposes no editor handoff; a completed mapped result still exposes the exact owned editor URL.
- **Verification:** R2, R5.

### U3. Guard Dirty Query Navigation

- **Goal:** avoid discarding unsaved editor content when handoff query values change while the resume ID stays the same.
- **Files:** `web/src/views/ResumeEditorView.vue`, `web/e2e/ats-ai.spec.ts`.
- **Patterns:** reuse the existing leave-confirmation behavior and query watcher; only run draft reset and editor reload after navigation is accepted.
- **Test scenarios:** a dirty same-resume handoff query prompts; cancel keeps content and URL; confirm reloads the validated target; clean successor creation continues without a prompt.
- **Verification:** R3, R5.

### U4. Recover Stale Lazy Chunks Once

- **Goal:** make a feature navigation recover after an old tab requests a removed deployed chunk without masking persistent asset failures.
- **Files:** `web/src/router/index.ts`, `web/e2e/route-loading.spec.ts`.
- **Patterns:** keep direct route dynamic imports and add a small router-level error boundary; scope the session marker to the destination and clear it after a successful route resolution.
- **Test scenarios:** a forced dynamic-import failure reloads once; a repeated failure does not loop; ordinary deep links and authentication redirects retain their full destination.
- **Verification:** R4, R5.

### U5. Focused Regression Proof

- **Goal:** exercise the unsafe inputs and failure transitions alongside the existing happy paths.
- **Files:** `web/e2e/ats-ai.spec.ts`, `web/e2e/route-loading.spec.ts`, `server/src/test/java/com/intelligentresume/ats/controller/AtsControllerIT.java`.
- **Patterns:** extend existing mock-backed browser tests and ATS integration fixtures; keep local-service coverage unchanged unless a tested contract requires it.
- **Test scenarios:** execute every U1-U4 negative path and retain the existing readonly, draft restore, route-refresh, and ownership assertions.
- **Verification:** R1-R5.

---

## Verification Contract

| Gate | Applies to | Evidence of success |
| --- | --- | --- |
| `web: npm run check:i18n` | U1-U4 | No unlocalized user-visible recovery or error text. |
| `web: npm run build` | U1-U4 | Typecheck accepts nullable ATS data and route chunks remain split. |
| `web: npx playwright test e2e/ats-ai.spec.ts e2e/route-loading.spec.ts --workers=1` | U1-U5 | Valid and invalid handoffs, dirty navigation, and chunk-recovery paths pass. |
| `server: mvn -Dtest=AtsControllerIT test` | U2, U5 | Pending and completed ATS response contracts pass. |
| `git diff --check` | U1-U5 | No whitespace errors. |

---

## Definition of Done

- A historical ATS URL cannot make an unrelated current version editable.
- Pending ATS responses and frontend types agree, and no absent ID becomes an editor link.
- Same-editor query navigation cannot silently discard dirty work.
- Stale lazy chunks recover once without reload loops or broken redirects.
- Existing happy-path ATS handoff, explicit successor creation, localization, build, and focused browser/backend checks remain green.
