---
title: Job Search Workspace Design Optimization
created_at: 2026-08-10
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-plan-bootstrap
execution: code
---

# Job Search Workspace Design Optimization

## Goal Capsule

- Objective: turn the existing feature-complete resume product into a contextual job-search workspace that makes the user's next action, its evidence, and its outcome clear.
- Authority: current Web behavior, `PROJECT_CONTEXT.md`, existing i18n conventions, and this plan define scope. The product's truthfulness, ownership, consent, and manual-confirmation boundaries take precedence over new interaction ideas.
- Scope: current-worktree baseline reconciliation, Web navigation, server-backed dashboard context, ATS-to-editor handoff, route loading, and regression coverage.
- Stop condition: each in-scope flow is actionable on desktop and mobile, retains its current safety semantics, and passes the verification contract.

---

## Product Contract

### Problem Frame

The product already implements the full workflow from career materials through application tracking and interview preparation. The active worktree contains a partial implementation of this redesign, so execution must first reconcile that diff against this contract before extending it.
The remaining usability gap is continuity: a signed-in user needs an account-accurate next action, a diagnostic needs to lead safely to the relevant edit while preserving its source, and related generation entry points must not compete in navigation. Route loading must preserve existing deep-link and recovery behavior while avoiding an eager all-view bundle.

### Requirements

- R1. Authenticated home users see their real next actionable item, derived from owned interviewing applications, incomplete applications, account-level resumable generation tasks, and resumes, rather than a static example presented as current work.
- R2. The home page distinguishes product illustration from user data. Static preview metrics must be labeled as examples for guests and replaced or hidden for authenticated users when no corresponding real data exists.
- R3. Each ATS prioritized action and evidence finding that identifies a resume section provides a direct, safe handoff to the correct owned resume editor section, with a clear return entry to the originating diagnosis.
- R4. The editor consumes an optional section handoff without losing its current draft recovery, dirty-navigation guard, versioning, or AI confirmation behavior.
- R5. Navigation groups use concise job-seeker task language and give the two resume-generation paths distinguishable labels and descriptions.
- R6. Route-level code splitting reduces initial JavaScript without changing route guards, locale behavior, or deep-link behavior.
- R7. New user-visible copy remains available in Chinese and English, and desktop/mobile/keyboard behavior remains accessible.

### Scope Boundaries

- Do not change scoring rules, ATS result semantics, AI prompts, consent, ownership checks, or PDF rendering.
- Do not promise interview outcomes, ATS pass rates, or hiring probabilities.
- Do not add automated job submission, job scraping, a third-party analytics SDK, or a new backend service for dashboard aggregation in this pass.
- Do not make static dashboard guesses when the existing client cannot derive a reliable state; use a clear empty state and direct action instead.
- Do not retain a full ATS diagnostic summary inside the editor. The editor stays focused on editing; the originating report remains available through the return entry.
- Do not introduce interview or application deadline fields in this pass. Their current contracts do not provide a trustworthy deadline, so same-priority items use existing update time rather than inferred urgency.

---

## Planning Contract

### Key Technical Decisions

- KTD-1. Build the authenticated dashboard from existing Web stores and APIs. Reuse resume summaries and application records, but obtain resumable generation tasks through a narrow authenticated account-level continuation query; browser task memory remains a polling and navigation optimization, not the dashboard's source of truth. The query returns only the current user's `JOB_MATERIAL_SELECTION` and `JOB_GENERATION` tasks that are pending, running, or successfully completed but awaiting a confirmation decision, ordered by `updatedAt` descending and then ID descending. Use one deterministic next-action order: interviewing application, incomplete application awaiting submission, resumable generation task, most recently updated resume, then the empty-state start action. The primary action is the highest matching state; lower states remain secondary links when present. Within every group, use `updatedAt` descending and then ID descending; do not infer a deadline that the product does not store.
- KTD-2. Treat ATS handoff as navigation context, not a persisted editor mutation. Pass validated `section`, `atsResultId`, and source-version context query parameters. After the target loads, move focus to and scroll the allow-listed section, announce the arrival, and expose a clear return-to-diagnosis entry. Do not keep a full diagnosis summary in the editor. Keep this context URL-derived so refreshes retain the evidence without storing it as resume content.
- KTD-3. When an ATS result targets a non-current version, open that owned version as the read-only source and require an explicit "create editable version from this analysis" action before modifications. That explicit action creates a traceable successor and makes it the resume's current version, matching the existing restore contract; the button-adjacent feedback and success state must say that the active version changed. Persist the validated ATS result ID, source version ID, job-description ID, mapped section/item, and a readable optimization objective in the successor's existing `generationContext`, and return that context in version detail/history responses. The restore request must validate the ATS result belongs to the current user, resume, and source version before it records that provenance. Never silently redirect the user to the resume's current version.
- KTD-4. Keep ATS prose and scoring unchanged. The new action control appears only where the result has a known section mapping; generic risks remain informational.
- KTD-5. Rename and regroup navigation through locale keys while retaining the existing route paths. This preserves bookmarks, route guards, and E2E selectors that depend on URLs.
- KTD-6. Use Vue Router async components for feature views, keeping the app shell, authentication pages, and home view eager. Avoid manual chunk rules until the route split is measured in the production build.
- KTD-7. Preserve the existing restrained evidence-desk visual system: current tokens, maximum 8px card radii, Lucide icons, visible focus, and reduced-motion behavior remain the baseline.

### Sequencing

U0 first reconciles this plan with the active dirty worktree.
U1 establishes the account-backed dashboard state and copy.
U2 establishes the navigation contract independently.
U3 independently adds the ATS-to-editor handoff and its editor context pattern.
U4 applies lazy routes only to behavior not already implemented and verified in U0.
U5 completes cross-flow, responsive, and accessibility regression coverage.

---

## Implementation Units

### U0. Reconcile the Active Baseline

- Goal: protect in-progress work by establishing exactly which parts of R1-R7 already exist, which are incomplete, and which tests prove the baseline.
- Files: the current diff in the files named by U1-U5, including `server/src/main/java/com/intelligentresume/ats/dto/AtsCheckResponse.java`, `server/src/main/java/com/intelligentresume/ats/service/AtsService.java`, and `server/src/test/java/com/intelligentresume/ats/controller/AtsControllerIT.java`; `docs/plans/2026-08-10-001-design-optimization.md`; and no product source files solely for this unit.
- Patterns: inspect the dirty diff and existing focused E2E/server coverage before editing; retain working route loading, ATS handoff, and version protections instead of re-implementing them.
- Test scenarios:
  - every changed source and test file is mapped to a requirement or explicitly marked out of scope;
  - already-passing dashboard, ATS, and route-loading behavior is recorded as baseline evidence before any follow-up edits;
  - no subsequent unit overwrites an existing user change without reconciling its intended behavior against the corresponding requirement.
- Verification: the implementation backlog is diff-scoped, and U1-U5 file lists identify only missing or incorrect behavior.

### U1. Contextual Home Workspace

- Goal: replace the authenticated static proof panel with an action-oriented workspace that answers what is in progress, what needs attention, and where to continue.
- Files: `server/src/main/java/com/intelligentresume/ai/task/controller/AiTaskController.java`, `server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java`, `server/src/main/java/com/intelligentresume/ai/task/repository/AiTaskRepository.java`, `server/src/test/java/com/intelligentresume/ai/task/controller/AiTaskControllerIT.java`, `server/src/test/java/com/intelligentresume/ai/task/service/AiTaskServiceTest.java`, `web/src/views/HomeView.vue`, `web/src/api/ai.ts`, `web/src/stores/aiTask.ts`, `web/src/i18n/index.ts`, and focused browser tests under `web/e2e/`.
- Patterns: reuse `useAuthStore`, `listResumes`, `listApplications`, and the existing `AiTaskStatusResponse` projection. The authenticated continuation query owns filtering and ordering; `aiTask` local storage only resumes a known task's polling/navigation. Keep the guest illustration visibly labeled as an example.
- Test scenarios:
  - a guest sees the product illustration and registration/import calls to action;
  - an authenticated user with an interviewing application, an incomplete application, a resumable generation task, and a recent resume sees them in that fixed priority order;
  - an authenticated user with an interviewing application sees the preparation continuation before an incomplete application, generation task, or recent resume;
  - an authenticated user with an incomplete application sees the submission continuation before a generation task or recent resume;
  - equal-priority next actions are ordered by `updatedAt` and then ID, even when no deadline exists;
  - a resumable task created on another device or after local storage is cleared still appears in the workspace;
  - terminal, rejected, foreign-user, and non-generation tasks never appear in the continuation result;
  - multiple resumable tasks are returned in the documented stable order and remain secondary actions when a higher-priority application exists;
  - an authenticated user with a remembered active task sees its actual stage and resumes it;
  - an authenticated user with no resume or active task sees one unambiguous start action;
  - an unavailable dashboard request shows a recoverable, non-misleading state.
- Verification: R1, R2, R7.

### U2. Task-Oriented Navigation

- Goal: make global navigation describe user goals and distinguish the two generation workflows without changing their URLs.
- Files: `web/src/layouts/AppLayout.vue`, `web/src/i18n/index.ts`, `web/src/styles/main.css`, and navigation assertions in `web/e2e/workflow.spec.ts`.
- Patterns: retain the grouped desktop dropdown and mobile disclosure patterns, current Lucide icons, active group behavior, and accessible labels.
- Test scenarios:
  - desktop and mobile expose the same route set in the same conceptual groups;
  - each generation route has a distinct localized task label;
  - the active group reflects deep links to child routes;
  - keyboard focus reaches the group controls and each visible destination.
- Verification: R5, R7.

### U3. ATS-to-Editor Action Handoff

- Goal: convert section-specific ATS findings into a direct edit path while preserving the diagnosis as evidence.
- Files: `server/src/main/java/com/intelligentresume/ats/dto/AtsCheckResponse.java`, `server/src/main/java/com/intelligentresume/ats/service/AtsService.java`, `server/src/test/java/com/intelligentresume/ats/controller/AtsControllerIT.java`, `server/src/main/java/com/intelligentresume/resume/controller/ResumeVersionController.java`, `server/src/main/java/com/intelligentresume/resume/dto/ResumeVersionSummary.java`, `server/src/main/java/com/intelligentresume/resume/service/ResumeVersionService.java`, `server/src/test/java/com/intelligentresume/resume/service/ResumeVersionServiceTest.java`, `server/src/test/java/com/intelligentresume/resume/controller/ResumeControllerIT.java`, `web/src/api/ats.ts`, `web/src/api/resume.ts`, `web/src/views/AtsCheckView.vue`, `web/src/views/ResumeEditorView.vue`, `web/src/resume/sectionRegistry.ts`, `web/src/i18n/index.ts`, and `web/e2e/ats-ai.spec.ts`.
- Patterns: expose `resumeId` as nullable while ATS analysis is pending, while retaining `resumeVersionId` and `jobDescriptionId`; construct no editor link until an owned resume ID exists. Map only known ATS section identifiers to `sectionRegistry` keys; construct links only from those result fields; and consume the query after `loadEditor` with `jumpToSection`, a focus target, and a polite arrival announcement. Extend the existing restore request with only server-validated ATS provenance and store it in `generationContext`, rather than adding a table or trusting route query values.
- Test scenarios:
  - a mapped prioritized action and a mapped evidence finding each open the expected owned resume version and editor section, independent of the options currently selected on the ATS page;
  - an unknown or generic action has no misleading edit link;
  - invalid section query values are ignored safely;
  - the editor offers a clear return-to-diagnosis entry that returns to the same ATS result without a local-storage dependency, while no full diagnosis summary competes with the editing surface;
  - a historical analyzed version is read-only until the user explicitly creates an editable successor; creation identifies the source version, makes the successor current, persists its validated ATS provenance, and preserves the return-to-diagnosis context, while a current analyzed version preserves normal editing behavior;
  - successor creation disables its control and announces progress while pending; a failure retains the read-only source and ATS return context, offers a retry beside the failure message, and announces the active-version change only after the successor reload succeeds;
  - a pending ATS result exposes no editor handoff while retaining its source version/job-description data; a forged, foreign, or mismatched ATS result cannot be persisted as successor provenance;
  - refreshing the editable successor and opening its version history retains the ATS result, source version, job-description, mapped item, and optimization-objective provenance;
  - the editor preserves restored drafts and does not auto-save on arrival;
  - the handoff moves focus to the selected section, announces the change, and remains usable at the mobile breakpoint.
- Verification: R3, R4, R7.

### U4. Route-Level Loading Strategy

- Goal: reduce initial JavaScript by loading high-cost authenticated views only when their routes are visited.
- Files: `web/src/router/index.ts`, optionally `web/vite.config.ts` only if post-split output still demonstrates an avoidable shared chunk, and route-loading coverage in `web/e2e/workflow.spec.ts`.
- Patterns: use `defineAsyncComponent` or dynamic route imports with explicit loading/error behavior compatible with the existing app shell; preserve component props and route meta.
- Test scenarios:
  - direct navigation and refreshed deep links still resolve for every lazy route;
  - auth redirects retain the original destination;
  - route loading errors have a visible retry path if a custom async wrapper is introduced;
  - production build output no longer emits the current monolithic main application chunk.
- Verification: R6, R7.

### U5. Design-System and Journey Regression Proof

- Goal: prove the redesigned handoffs do not weaken interaction quality across the existing resume journey.
- Files: `web/e2e/workflow.spec.ts`, `web/e2e/ats-ai.spec.ts`, `web/e2e/local-services.spec.ts`, and only the CSS files changed by U1-U3.
- Patterns: extend existing visible-UI Playwright flows; use route mocks only in existing non-local suites and retain the opt-in real-service coverage boundary.
- Test scenarios:
  - desktop and 390px mobile screenshots show no overlapping labels, clipped controls, or inaccessible primary actions on home, ATS, and editor handoff states;
  - reduced-motion mode does not depend on animation to communicate task progress;
  - Chinese and English copy pass `check:i18n` and fit their controls;
  - the core local-service journey can start from the dashboard, reach ATS, edit a flagged section, and preserve the resume version workflow.
- Verification: R1-R7.

---

## Verification Contract

| Gate | Applies to | Evidence of success |
| --- | --- | --- |
| `server: mvn -Dtest=AiTaskControllerIT,AiTaskServiceTest,AtsControllerIT,ResumeVersionServiceTest,ResumeControllerIT test` | U1, U3, U5 | Account-scoped continuation filtering, ATS pending contract, and validated successor provenance pass. |
| `web: npm run check:i18n` | U1-U3 | New navigation, dashboard, and handoff copy exists in both locales and the visible-copy guard passes. |
| `web: npm run build` | U1-U4 | Typecheck and production build pass; emitted chunks demonstrate route-level splitting. |
| `web: npx playwright test` | U1-U5 | Mock-backed browser regression tests pass, including desktop and mobile paths. |
| `web: npm run test:e2e:local` | U1, U3, U5 | The visible local-service path proves dashboard continuation and ATS-to-editor handoff when local dependencies are started. |
| Manual keyboard and responsive review | U1-U3, U5 | Focus order, focus visibility, and 390px/1440px layouts are inspected for the changed workflows. |

---

## Definition of Done

- The home screen shows account-accurate continuation context for authenticated users, prioritizing interviews, incomplete submissions, generation tasks, and recent resumes in that order; it uses `updatedAt` then ID within a group and clearly labeled examples for guests.
- ATS findings that can be mapped to a known resume section offer a working, safe editor handoff with a return-to-diagnosis entry rather than a persistent diagnostic summary.
- Navigation distinguishes generation workflows without breaking old deep links.
- The production build has route-level chunks instead of a single all-view application entry chunk.
- New UI copy is localized, changed workflows work with keyboard and mobile layouts, and all applicable verification gates pass.
- An ATS editable successor is created only by explicit user action, records its historical source and validated ATS provenance, visibly becomes current, and does not weaken AI consent, user confirmation, traceability, resume history, or ownership constraints.
