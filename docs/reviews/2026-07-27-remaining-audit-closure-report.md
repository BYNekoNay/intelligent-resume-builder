# Remaining Audit Closure Report

## Scope

- Plan: `docs/plans/2026-07-27-002-remaining-audit-closure.md`
- Base: `4b87dfc887f6402ef4b40bad47408a9e848e1375`
- Branch: `codex/complete-resume-workflows`
- Final state: uncommitted working tree; no commit, push, or PR.
- Review date: 2026-07-28 (Asia/Shanghai).
- Review inputs: the complete tracked diff, all untracked files, and three independent reports under `docs/reviews/`.

## Outcome

R1, R2, R3, R5, R6, R7, and R8 are complete. R4 is partial: the real local-service suite was executed against isolated current-code services, but both AI-dependent journeys stopped at the external provider because no `BAILIAN_API_KEY` was available. No live-provider success is claimed.

## Requirement Evidence

| Requirement | Evidence | Status |
| --- | --- | --- |
| R1 | `web/src/i18n/index.ts`; Web build; language-switch E2E | Complete |
| R2 | AST/SFC guard in `web/scripts/check-i18n.mjs`; 9 self-tests including bound visible attributes; 30 Vue files and locale parity | Complete |
| R3 | `web/src/types/resume.ts`, shared section registry/fixture, Web preview E2E, PDF template tests | Complete |
| R4 | Five Spring controller ITs and opt-in real-service browser suite; isolated run reached real import and generation APIs | Partial: external AI credential missing; PDF fault injection not enabled |
| R5 | `ResumeImportControllerIT`: TXT/PDF/DOCX, corrupt/empty/oversized/unsupported/MIME mismatch, normalization, no original storage | Complete |
| R6 | `InlineOptimizeRequest`, controller ownership checks, 8-case controller IT | Complete |
| R7 | Deployment docs and this report keep backup restore, alert delivery, and capacity as deferred external evidence | Complete |
| R8 | Current counts, honest blockers, three reviewer reports, and exhaustive file inventory below | Complete |

## Implementation Units

| Unit | Status | Result |
| --- | --- | --- |
| U1 | Complete | Typed 14-section resume contract, shared registry/fixture, Web/PDF compatibility and tolerance tests |
| U2 | Complete | Full Vue i18n cleanup and guard, including bound attribute literals |
| U3 | Partial | Spring IT and real-service flow implemented; credentialed AI completion remains externally blocked |
| U4 | Complete | Import extraction and safety coverage for all supported formats |
| U5 | Complete | Validated optimize DTO with authentication, consent, and ownership behavior preserved |
| U6 | Complete | Evidence refreshed after independent review and final regression |

## Independent Review Closure

| Review | Confirmed findings | Resolution |
| --- | --- | --- |
| Backend boundaries | Batch tail could lose its AI lease; V18 lacked historical backfill/non-null; scheduled workers polluted tests | Worker now claims one task immediately before execution; V18 backfills and enforces invariants with V17 upgrade test; test scheduling disabled |
| Frontend correctness | Material-library failures were silent; old async failures leaked across resume routes | Localized load error plus route-scoped error/loading guards and two Playwright regressions |
| Testing evidence | Bound attribute i18n gap; weak filter assertion; stale counts/inventory; unsupported R4 completion claim | Guard/test fixed; negative asset filter assertion added; counts/inventory refreshed; R4 downgraded to partial |

Reports:

- `docs/reviews/2026-07-28-agent-backend-boundaries-review.md`
- `docs/reviews/agent-frontend-regression-review.md`
- `docs/reviews/agent-testing-evidence-review.md`

## Final Verification

| Command | Result | Evidence |
| --- | --- | --- |
| `server: mvn test` | Pass | 310 tests, 0 failures, 0 errors, 0 skipped; includes V17 to V18 historical upgrade and worker sequencing |
| `web: npm run build` | Pass | i18n 9/9; 30 Vue files; 2 locales; `vue-tsc` and Vite production build pass |
| `web: npx playwright test --workers=1` | Pass with expected opt-in skips | 33 passed, 5 local-service tests skipped without `LOCAL_E2E=true` |
| `pdf-service: npm run check && npm test` | Pass | syntax checks and 9/9 tests |
| `git diff --check` | Pass | no whitespace errors; LF/CRLF conversion warnings only |

## Real Local-Service Evidence

An isolated current-code API was started at `127.0.0.1:8081` and Web at `127.0.0.1:5174`; existing user services at 8080/5173/3001 were not replaced.

- 2 passed: local route load and synthetic account lifecycle.
- 2 failed only after reaching external AI calls: import/generation and the core journey received provider 4xx because `.env`/`BAILIAN_API_KEY` was absent.
- 1 skipped: PDF stop/restart fault scenario requires explicit `LOCAL_E2E_PDF_RECOVERY=true` authorization.
- Current-code API 8081 parsed TXT import successfully before the AI handoff.
- Existing API 8080 returned import HTTP 500 and was identified as a stale process; that result is not attributed to the reviewed current code.

Therefore this run proves current import routing and non-AI local integration, but not live Bailian generation. R4 remains partial.

## Residual And External Risks

- Backup restore: deferred until an actual recovery drill records restored-data checks.
- Alert delivery: deferred until the configured destination records receipt.
- Capacity validation: deferred until a measured environment load test exists.
- Live Bailian journeys: blocked by missing credentials; rerun the isolated local suite with an authorized key.
- PDF failure recovery: test exists but controlled renderer restart was not enabled in this run.
- V18 SQL passed H2 MySQL-mode fresh and V17-upgrade tests; production deployment still requires the documented MySQL pre/post-deploy queries and backup procedure.

## Exact Tracked Inventory (64)

- `PROJECT_CONTEXT.md`
- `README.md`
- `docs/DEPLOYMENT_READINESS.md`
- `pdf-service/README.md`
- `pdf-service/src/server.js`
- `pdf-service/src/templates/classic.js`
- `pdf-service/test/templates.test.js`
- `server/src/main/java/com/intelligentresume/ai/optimize/controller/InlineOptimizeController.java`
- `server/src/main/java/com/intelligentresume/ai/ratelimit/AiQuotaService.java`
- `server/src/main/java/com/intelligentresume/ai/task/repository/AiTaskRepository.java`
- `server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java`
- `server/src/main/java/com/intelligentresume/ai/worker/AiTaskWorkerProperties.java`
- `server/src/main/java/com/intelligentresume/ai/worker/DatabaseTaskWorker.java`
- `server/src/main/java/com/intelligentresume/ai/worker/TaskExecutionService.java`
- `server/src/main/java/com/intelligentresume/ai/worker/TaskLeaseService.java`
- `server/src/main/java/com/intelligentresume/application/controller/ApplicationController.java`
- `server/src/main/java/com/intelligentresume/ats/controller/AtsController.java`
- `server/src/main/java/com/intelligentresume/auth/service/AuthService.java`
- `server/src/main/java/com/intelligentresume/communication/controller/CommunicationController.java`
- `server/src/main/java/com/intelligentresume/config/WorkerSchedulingConfig.java`
- `server/src/main/java/com/intelligentresume/export/dto/CreateExportRequest.java`
- `server/src/main/java/com/intelligentresume/export/repository/ExportTaskRepository.java`
- `server/src/main/java/com/intelligentresume/export/service/ExportService.java`
- `server/src/main/java/com/intelligentresume/export/service/ExportStorageService.java`
- `server/src/main/java/com/intelligentresume/imports/controller/ResumeImportController.java`
- `server/src/main/java/com/intelligentresume/interview/asset/controller/InterviewAssetController.java`
- `server/src/main/java/com/intelligentresume/interview/controller/InterviewController.java`
- `server/src/main/java/com/intelligentresume/resume/dto/ResumeVersionSummary.java`
- `server/src/main/java/com/intelligentresume/resume/repository/ResumeVersionRepository.java`
- `server/src/main/java/com/intelligentresume/resume/service/ResumeVersionService.java`
- `server/src/main/java/com/intelligentresume/system/controller/SystemController.java`
- `server/src/main/java/com/intelligentresume/system/dto/SystemHealthResponse.java`
- `server/src/main/resources/application-local-h2.yml`
- `server/src/main/resources/application.yml`
- `server/src/test/java/com/intelligentresume/ai/ratelimit/AiQuotaServiceTest.java`
- `server/src/test/java/com/intelligentresume/ai/task/service/AiTaskServiceTest.java`
- `server/src/test/java/com/intelligentresume/ai/worker/DatabaseTaskWorkerIT.java`
- `server/src/test/java/com/intelligentresume/ai/worker/TaskExecutionServiceTest.java`
- `server/src/test/java/com/intelligentresume/ai/worker/TaskLeaseServiceTest.java`
- `server/src/test/java/com/intelligentresume/database/FlywayMigrationIT.java`
- `server/src/test/java/com/intelligentresume/export/controller/ExportControllerIT.java`
- `server/src/test/java/com/intelligentresume/export/service/ExportServiceTest.java`
- `server/src/test/java/com/intelligentresume/export/service/ExportStorageServiceTest.java`
- `server/src/test/java/com/intelligentresume/resume/service/ResumeVersionServiceTest.java`
- `server/src/test/resources/application-test.yml`
- `web/e2e/local-services.spec.ts`
- `web/e2e/workflow.spec.ts`
- `web/package-lock.json`
- `web/package.json`
- `web/playwright.config.ts`
- `web/src/api/export.ts`
- `web/src/api/resume.ts`
- `web/src/api/system.ts`
- `web/src/components/DraftContentFields.vue`
- `web/src/composables/useResumeEditorDraft.ts`
- `web/src/i18n/index.ts`
- `web/src/views/ApplicationsView.vue`
- `web/src/views/CareerMaterialView.vue`
- `web/src/views/GenerationConfirmView.vue`
- `web/src/views/GenerationWorkbenchView.vue`
- `web/src/views/HomeView.vue`
- `web/src/views/MaterialSelectionConfirmView.vue`
- `web/src/views/ResumeDetailView.vue`
- `web/src/views/ResumeEditorView.vue`

## Exact Untracked Inventory (67)

- `docs/2026-07-27-全项目实现一致性与质量审计.md`
- `docs/plans/2026-07-27-002-remaining-audit-closure.md`
- `docs/reviews/2026-07-27-remaining-audit-closure-report.md`
- `docs/reviews/2026-07-28-agent-backend-boundaries-review.md`
- `docs/reviews/agent-frontend-regression-review.md`
- `docs/reviews/agent-testing-evidence-review.md`
- `server/src/main/java/com/intelligentresume/ai/optimize/dto/InlineOptimizeRequest.java`
- `server/src/main/java/com/intelligentresume/application/domain/ApplicationRecord.java`
- `server/src/main/java/com/intelligentresume/application/domain/ApplicationStatus.java`
- `server/src/main/java/com/intelligentresume/application/dto/ApplicationResponse.java`
- `server/src/main/java/com/intelligentresume/application/dto/CreateApplicationRequest.java`
- `server/src/main/java/com/intelligentresume/application/dto/UpdateApplicationRequest.java`
- `server/src/main/java/com/intelligentresume/application/dto/UpdateApplicationStatusRequest.java`
- `server/src/main/java/com/intelligentresume/application/repository/ApplicationRecordRepository.java`
- `server/src/main/java/com/intelligentresume/application/service/ApplicationService.java`
- `server/src/main/java/com/intelligentresume/ats/domain/AtsCheckResult.java`
- `server/src/main/java/com/intelligentresume/ats/dto/AtsCheckRequest.java`
- `server/src/main/java/com/intelligentresume/ats/dto/AtsCheckResponse.java`
- `server/src/main/java/com/intelligentresume/ats/repository/AtsCheckResultRepository.java`
- `server/src/main/java/com/intelligentresume/ats/service/AtsService.java`
- `server/src/main/java/com/intelligentresume/communication/domain/CommunicationDraft.java`
- `server/src/main/java/com/intelligentresume/communication/domain/CommunicationType.java`
- `server/src/main/java/com/intelligentresume/communication/dto/CommunicationResponse.java`
- `server/src/main/java/com/intelligentresume/communication/dto/GenerateCommunicationRequest.java`
- `server/src/main/java/com/intelligentresume/communication/repository/CommunicationDraftRepository.java`
- `server/src/main/java/com/intelligentresume/communication/service/CommunicationService.java`
- `server/src/main/java/com/intelligentresume/export/service/ExportExpiryService.java`
- `server/src/main/java/com/intelligentresume/imports/dto/ResumeImportResponse.java`
- `server/src/main/java/com/intelligentresume/imports/service/ResumeImportService.java`
- `server/src/main/java/com/intelligentresume/interview/asset/domain/InterviewAnswerAsset.java`
- `server/src/main/java/com/intelligentresume/interview/asset/dto/InterviewAssetRequest.java`
- `server/src/main/java/com/intelligentresume/interview/asset/dto/InterviewAssetResponse.java`
- `server/src/main/java/com/intelligentresume/interview/asset/repository/InterviewAnswerAssetRepository.java`
- `server/src/main/java/com/intelligentresume/interview/asset/service/InterviewAssetService.java`
- `server/src/main/java/com/intelligentresume/interview/domain/InterviewMode.java`
- `server/src/main/java/com/intelligentresume/interview/domain/InterviewRecord.java`
- `server/src/main/java/com/intelligentresume/interview/domain/InterviewSession.java`
- `server/src/main/java/com/intelligentresume/interview/domain/InterviewSourceType.java`
- `server/src/main/java/com/intelligentresume/interview/domain/InterviewStatus.java`
- `server/src/main/java/com/intelligentresume/interview/dto/AnswerInterviewRequest.java`
- `server/src/main/java/com/intelligentresume/interview/dto/AnswerInterviewResponse.java`
- `server/src/main/java/com/intelligentresume/interview/dto/InterviewFeedback.java`
- `server/src/main/java/com/intelligentresume/interview/dto/InterviewReportResponse.java`
- `server/src/main/java/com/intelligentresume/interview/dto/StartInterviewRequest.java`
- `server/src/main/java/com/intelligentresume/interview/dto/StartInterviewResponse.java`
- `server/src/main/java/com/intelligentresume/interview/repository/InterviewRecordRepository.java`
- `server/src/main/java/com/intelligentresume/interview/repository/InterviewSessionRepository.java`
- `server/src/main/java/com/intelligentresume/interview/service/InterviewService.java`
- `server/src/main/java/com/intelligentresume/resume/service/ResumeTemplateCodes.java`
- `server/src/main/resources/db/migration/V17__communication_draft.sql`
- `server/src/main/resources/db/migration/V18__interview_record_round.sql`
- `server/src/test/java/com/intelligentresume/ai/optimize/controller/InlineOptimizeControllerIT.java`
- `server/src/test/java/com/intelligentresume/ai/task/repository/AiTaskRepositoryIT.java`
- `server/src/test/java/com/intelligentresume/ai/worker/DatabaseTaskWorkerTest.java`
- `server/src/test/java/com/intelligentresume/application/controller/ApplicationControllerIT.java`
- `server/src/test/java/com/intelligentresume/ats/controller/AtsControllerIT.java`
- `server/src/test/java/com/intelligentresume/communication/controller/CommunicationControllerIT.java`
- `server/src/test/java/com/intelligentresume/export/service/ExportExpiryServiceTest.java`
- `server/src/test/java/com/intelligentresume/imports/controller/ResumeImportControllerIT.java`
- `server/src/test/java/com/intelligentresume/interview/asset/controller/InterviewAssetControllerIT.java`
- `server/src/test/java/com/intelligentresume/interview/controller/InterviewControllerIT.java`
- `test-fixtures/resume-all-sections.json`
- `web/scripts/check-i18n.mjs`
- `web/scripts/check-i18n.test.mjs`
- `web/src/components/resume/ResumePaper.vue`
- `web/src/resume/sectionRegistry.ts`
- `web/src/types/resume.ts`

## Reviewer Handoff

To close R4 completely, start isolated current-code Web/API/PDF services with an authorized Bailian key, enable `LOCAL_E2E=true`, optionally authorize PDF fault injection, and rerun `web: npm run test:e2e:local`. Preserve the exact counts and provider/request evidence. All repository-only gates are currently green.
