# Local full-flow validation

This guide validates the application on one machine only. It does not configure or contact any deployment environment.

## Safety rules

- Store local MySQL settings in ignored `.env`, and the Bailian key only in ignored `.env.live-ai`.
- Do not put credentials in screenshots, terminal transcripts, reports, or Git.
- All generated reports, logs, downloads, and browser artifacts belong in ignored `.local-validation/`.
- Use synthetic names, email addresses, career materials, and job descriptions only.

## 平台要求

验证脚本为 PowerShell（.ps1），需要 Windows 环境或 PowerShell 7+（pwsh）。macOS/Linux 用户可安装 PowerShell 7（`brew install powershell` 或 `sudo apt-get install powershell`）后运行。脚本中使用的 curl、jq 等工具需预先安装。

## Local MySQL + Bailian baseline

Prerequisites: a running MySQL server matching the `SPRING_DATASOURCE_*` values in root `.env`, Java 17, Maven, Node.js, and installed dependencies in `web` and `pdf-service`.

For an existing local MySQL installation, add these values to ignored root `.env` with your actual database and credentials (do not use the example password unless it is the password you configured):

```properties
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/intelligent_resume?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=your_local_mysql_user
SPRING_DATASOURCE_PASSWORD=your_local_mysql_password
```

The database user needs permission to create and migrate the `intelligent_resume` schema. Flyway runs automatically when the API starts.

```powershell
.\scripts\Start-LocalValidation.ps1
.\scripts\Test-LocalFullFlow.ps1 -VerifyPdfRecovery
.\scripts\Stop-LocalValidation.ps1
```

For a fresh disposable database, use the project's supported MySQL 8.4 runtime and start the same flow with:

```powershell
.\scripts\Start-LocalValidation.ps1 -DisposableDatabase
Set-Location web
npm run test:e2e:local
Set-Location ..
.\scripts\Stop-LocalValidation.ps1
```

The disposable mode records only the strictly validated schema and user names in the ignored process manifest. The generated password stays in the child process environment and is never written to disk; the stop script removes the exact temporary schema and user.

MySQL 5.7 is not a supported fresh-install target because the published V18 migration uses a MySQL 8 window function. To test against real data from an existing MySQL 5.7 schema already at Flyway V19, clone it into an isolated database instead:

```powershell
.\scripts\Start-LocalValidation.ps1 -CloneDatabase intelligent_resume
```

Clone mode requires a V19 source, copies every base table and verifies row counts and the required V18/V19 schema invariants. Only the clone is baselined at V19 and migrated through V21. The source schema and its Flyway history are never modified. Stop the current validation environment before starting another isolated database; the script rejects silent reuse of an existing API on port `8080`.

The start script uses the already-running local MySQL instance, then starts PDF service on `3001`, API on `8080`, and web on `5173`. The web app calls the API through the Vite `/api` proxy, keeping each browser origin same-origin; `localhost` and `127.0.0.1` still maintain separate browser sessions.
The full-flow script creates and deletes one synthetic user. It verifies authentication, resume/material/JD creation, consent, job generation and confirmation, scoring, editable communication/application data, interview-answer assets, cross-user isolation, and authorized PDF download. It calls Bailian with synthetic data. With `-VerifyPdfRecovery`, it additionally stops the PDF service, verifies a failed export, restarts the service, and verifies that retry succeeds.

Each run writes a redacted JSON report and a readable Markdown summary under `.local-validation/`. The allowed evidence is status, duration, synthetic run ID, trace ID, PDF SHA-256, and cleanup status; request bodies, credentials, cookies, and downloaded files are not written.

## 失败判定标准

脚本运行后，以下任一条件表示验证失败：

- 任何 API 调用返回非预期 HTTP 状态码（如期望 200 得到 500）
- 任何响应 JSON 的 code 字段不为 0（成功）或不为预期错误码
- PDF 文件未生成或 SHA-256 校验和不匹配
- 跨用户隔离测试中，用户 B 能访问用户 A 的资源
- AI 任务在最大重试次数后仍未达到终态（SUCCESS 或 FAILED）
- 任何未预期的异常堆栈出现在服务日志中

报告中 status=FAILED 或 cleanup=INCOMPLETE 的条目必须人工排查。

## 验证范围说明

当前本地验证覆盖 M1/M2（MVP 闭环）及面试答案资产的基础操作。ATS、成果量化引导、沟通文案、AI 面试多轮对话和投递状态流转已经实现，但默认全流程尚未覆盖其全部 AI 与状态转换路径；对应模块测试和专项 E2E 是当前验证依据。验证通过不等于完整发布验收通过——发布前仍需完成本文列出的专项验证与环境检查。

薄弱项练习（interview follow-up）已通过专项验证（2026-08-31 手工验证 PASS）：对已完成会话基于真实百炼 AI 生成 3~5 条候选练习题，并校验问题/聚焦点/预期信号/覆盖标签的契约与归属。

## Failure drill

With the local MySQL + Bailian baseline running, the full-flow command above can perform the PDF outage/retry drill. The following commands are useful for manual troubleshooting:

```powershell
.\scripts\Invoke-LocalFault.ps1 -Action StopPdf
.\scripts\Invoke-LocalFault.ps1 -Action StartPdf
```

The task state and retry outcome belong in the generated redacted summary, never in a tracked evidence folder.

## Live Bailian provider gate

Create ignored `.env.live-ai` containing `BAILIAN_API_KEY` and, optionally, `BAILIAN_MODEL` (verified recommended value: `qwen3.7-plus-2026-05-26`).
Do not add `AI_PROVIDER` to `.env`; the gate injects it only into its Maven child process.

```powershell
$env:BAILIAN_LIVE_TEST = 'true'
.\scripts\Invoke-LiveAiGate.ps1
Remove-Item Env:BAILIAN_LIVE_TEST
```

The gate uses synthetic prompts, validates structured responses, and prints field/count summaries rather than source content.

## MySQL 5.7 migration gate

With the local MySQL 5.7 service running, execute the disposable migration gate from the repository root:

```powershell
.\scripts\Invoke-MySql57MigrationGate.ps1
```

The gate creates a randomly named schema and least-scope temporary user, loads a data-free V19 schema fixture, baselines it at V19, applies V20 and V21, verifies the AI interview table, both uniqueness constraints, and the output-language column, then removes the exact schema and user in a `finally` block. It proves the V19-to-V21 upgrade path on MySQL 5.7; it does not claim that a fresh MySQL 5.7 database can execute every historical migration. It does not print generated credentials.

## Browser local-services smoke

Start the local MySQL + Bailian baseline first, then run:

```powershell
Set-Location web
npm run test:e2e:local
```

This command is opt-in and only accepts the documented loopback origin. Browser trace, video, and screenshots are disabled by default.

## Browser manual regression findings (2026-09-03)

This section records findings from the Codex in-app browser against the loopback UI (`http://127.0.0.1:5173/`) using synthetic local test data. No credentials, cookies, source files, or AI payloads were recorded. The browser-only pass did not modify source code or grant AI consent.

### Reproduced defects (historical baseline)

The entries in this chronological section preserve the original symptoms and investigation evidence. Later entries may supersede an earlier observation; the current status is recorded in the latest closure sections rather than inferred from an older “still” or “current” phrase.

| ID | Route and reproduction | Historical observed result | Historical root cause / contract gap |
| --- | --- | --- | --- |
| BR-001 | Open `/match/42` or `/match/43` for an existing valid match result. | The page remains in the loading state and the console reports `TypeError: Cannot read properties of undefined (reading 'matched')` at `web/src/views/MatchResultView.vue:212`. | `ScoringController.getResult()` returns the `MatchResult` entity, whose persisted field is `explanationJson`; the view reads `store.current.explanation`. Invalid IDs show the intended friendly alert, so the failure is specific to the valid-result response contract. |
| BR-002 | Open `/communications`, choose `模板库`, select `感谢` in `场景筛选`. | The list continues to show all templates (9 items). Clicking `刷新` then reduces it to the single matching template. | `web/src/views/CommunicationView.vue:503` binds the select with `v-model` but does not reload templates on change; `loadTemplates()` is invoked on mount and form submit. |
| BR-003 | Open `/communications` → `模板库`, edit `本地测试求职信模板`, and save without changing the name. | The dialog stays open and shows `模板包含非法占位符，仅允许白名单字段。`. | `openEditTemplate()` loads only the template name/scene. `saveTemplateDialog()` sends `previewDraft` or `draft` as `bodyText`, but the edit flow has not loaded the existing template body. See `web/src/views/CommunicationView.vue:336-363`. |

### Additional verified behavior

- Direct ATS rule checking succeeds and returns a persisted local report (`result=4`, score 80); missing version/job selection shows a validation alert.
- Template-generated cover letter, email body, and opening message work. `用于投递` carries the opening message into the new application form without sending anything externally.
- Application status changes from `APPLIED` to `INTERVIEWING` update the counters, lanes, home next-action card, and remain after refresh. The UI intentionally exposes forward-only transitions, so the synthetic test record remains in `INTERVIEWING`.
- The route-load error retry returns to the workbench. Resume import opens a single-file chooser, but no file was uploaded.
- Invalid resume detail/edit/compare routes and an invalid match ID render friendly alerts without console errors; valid match IDs were covered by the historical BR-001 baseline and are now covered by the closure recheck below.
- Existing export task `#49` displays `PDF 已准备好`. A new export task (`#50`) first displayed `PDF 渲染失败`, then transitioned to `PDF 已准备好` about four seconds after clicking `重试导出`; the retry path is therefore verified. The page shows no error after clicking `下载 PDF`, but the browser automation did not observe a download event within 5 seconds, so client-side file delivery remains an open validation item rather than a confirmed product failure.

### Follow-up priority at baseline (historical)

1. Fix the valid match response/view contract (BR-001), because it blocked the primary matching result at baseline.
2. Reload templates when the scene filter changes (BR-002).
3. Fetch and preserve the existing template body during edits (BR-003).
4. Add a browser-level assertion for the PDF response/file itself to close the download validation gap; this remains a validation limitation rather than a confirmed product failure.

No fix was applied during this initial browser-only pass; the findings above were the baseline for the subsequent implementation and verification cycle. See `Historical browser finding closure recheck (2026-09-04)` for the current status.

### Responsive smoke check (historical baseline, 2026-09-03)

Using the Codex in-app browser at `390x844` with the same synthetic local session:

- `/communications`: the mobile header collapses into an accessible navigation menu; the template cards, scene selector, refresh action, and edit dialog remain usable. At that time, the BR-002 filter-refresh defect also reproduced at this size; the later closure recheck confirms the selector now updates immediately.
- `/applications`: the summary, funnel, search/filter controls, application card, and new-application form remain readable and operable. The follow-up filter updates the visible card list correctly.
- The new-application form can be opened and dismissed without creating a record. No new page console errors were recorded during this pass.

The temporary viewport override was reset to the browser default after testing.

### Continued browser regression (historical baseline, 2026-09-03)

- `/generate`: selecting the existing target role enables the next step; the three evidence-boundary controls correctly update the `必须使用` / `AI 自动判断` / `不使用` counts. Generation was not started because the synthetic account remains `尚未授权` for AI data processing.
- `/ats`: the missing version/job guard shows `请先选择简历版本和目标岗位。`; a valid direct rule check completes with score 80, structure 100, and keyword coverage 75. No new console errors were recorded.
- `/ai-consent`: the page clearly reports `尚未授权` and presents the explicit consent action. `/interviews` also remains on the preparation step when the required external-resume text is empty; the input has a required constraint and no console error occurs.
- `/interviews/history`: the empty state and job filter render correctly. `/career-materials`: type filtering, asynchronous keyword search (including a zero-result state), and returning to the full list all work after waiting for the loading state to settle.
- `/resumes/82` → current version → `导出 PDF`: export task `#51` reached `PDF 已准备好` with a 204.9 KB file. Clicking `下载 PDF` left the page healthy, but no browser download event was observed within 5 seconds; file delivery remains an open validation item, not a confirmed product failure.
- Switching the home page between English and Chinese updates the navigation and content, and the test restored the Chinese UI.

### Further browser coverage (2026-09-03)

- At that time, `/match/42` still reproduced BR-001: after the valid result loaded, the view remained on `正在加载规则覆盖度…` and the console reported `Cannot read properties of undefined (reading 'matched')`. The later closure recheck confirms the valid result now renders normally; invalid match IDs still show the intended friendly alert.
- `/jobs`: the blank save action focuses the required job-name field; editing the existing job loads its name, company, and full description, and cancelling leaves the saved job unchanged.
- `/resumes/82/edit`: the existing unsaved-draft recovery dialog appears. Restoring the draft enables the save action; preview rendering and template switching both work. The test did not discard the local draft or save a new version. A separate tab was used for the remaining resume checks.
- `/resumes/82` → version comparison: comparing v2 with itself reports zero changes; selecting v1 reports 7 changed chapters, and the changed-only filter plus left/right swap both work. The comparison tab recorded no console errors.
- `/applications`: the existing record opens in edit mode with its saved text, and the feedback panel loads and closes without changing the record. The persisted `INTERVIEWING` status remains intact.
- `/material-generation` and `/achievement-guidance`: empty inputs show clear validation alerts without starting an AI request. `/interview-assets`: keyword search returns the correct zero-result and matching-result states, then returns to the full list.

### Responsive and persistence follow-up (2026-09-03)

- `/career-materials`: the `正常` preference returns all three synthetic records, title A–Z ordering sorts them as expected, `优先使用` shows the correct empty state, and restoring the default filters returns all three records.
- `/interview-assets`: filtering by `项目经历` and by the saved target job both returns the expected single answer asset; restoring the filters returns the full list.
- `/applications`: the `逾期` follow-up filter and a non-matching keyword both hide the synthetic record; restoring the filters shows it again. After a full page reload, the record remains in `INTERVIEWING`.
- At a temporary `768x1024` viewport, `/communications`, `/applications`, and `/resumes/82` reported no horizontal overflow (`scrollWidth` did not exceed the viewport width). The tablet pass recorded no new console errors. The viewport override was reset to the browser default afterward.

### Form-boundary and account-security follow-up (2026-09-03)

- `/interview-assets`: saving an empty new asset focuses the required question field; after entering only a question, validation focuses the required original-answer field. The temporary question was cleared and no asset was created.
- `/account`: the change-email and change-password dialogs open with the expected confirmation fields and close cleanly. Confirming an empty password-change form does not submit and keeps focus on the current-password field; no account setting was changed.
- `/communications`: attempting AI generation without selecting a resume version and target job keeps the user on the form and focuses the required version selector. Because AI consent remains `尚未授权`, no AI request or external data processing was started.
- No new page console errors were recorded during this pass.

### Materials and version-management follow-up (2026-09-03)

- At that time, `/career-materials` validated a missing title but allowed saving when only a title was present and `来源原文` was empty. This created the synthetic local record `临时资料校验`; it was opened in edit mode successfully and then left unchanged. The later evidence-quality remediation closes this data-quality boundary while preserving the historical row for traceability.
- `/resumes/82`: the `已归档` filter shows the correct empty state, returning to `版本历史` restores both version cards, and the rename form blocks an empty title. No version was archived, restored, or renamed.
- The test did not delete the temporary material because deletion requires an explicit destructive-action confirmation. No new page console errors were recorded.

### Application and interview boundary follow-up (2026-09-03)

- `/applications`: submitting the empty new-application form focuses the required resume-version selector and creates no record. The form was then cancelled.
- `/interviews`: the target-question input exposes native `min=4` and `max=12` constraints. The default value `6` displays the expected dynamic range of `3 ~ 9` questions; typing out-of-range values changes the helper text but no interview was started and the value was restored to `6`.
- No new page console errors were recorded during this pass, and no AI request was started.

### Authentication-form follow-up (2026-09-03)

- `/register`: the empty submit focuses the username field. The username input exposes the expected `[a-zA-Z0-9_.-]+` pattern, the email input uses `type=email`, and the password input requires at least 8 characters. Password show/hide toggles between `text` and `password` correctly.
- No account was created and no authentication data was submitted; the authentication page recorded no console errors.

Invalid synthetic username/email/password values were also submitted through the form boundary; the page stayed on registration and created no account. The values were cleared afterward, with no console error.

### Downstream evidence-quality follow-up (historical baseline, 2026-09-03)

- At that time, the title-only synthetic material `临时资料校验` appeared as a selectable item in `/generate` → `选择资料范围` even though it had no `来源原文` content. No AI generation was started; this confirmed the earlier optional-source observation could reach the downstream evidence-selection boundary. The later evidence-quality remediation closes this boundary.
- `/ats`: a valid direct rule report remains visible after a full page reload, including score `80`; no new console errors were recorded.

### Loopback-host session check (2026-09-03)

- The authenticated synthetic session at `http://127.0.0.1:5173/ats?result=6` remains valid after opening another tab.
- Opening the equivalent protected route at `http://localhost:5173/applications` redirects to `/login?redirect=/applications` instead of reusing that session. This is host-scoped browser-session behavior, not a data/API failure, but local validation must use one loopback hostname consistently.

### Export error-state follow-up (historical baseline, 2026-09-03)

- `/exports/51` continues to show `PDF 已准备好` with a visible `下载 PDF` action after reload.
- `/exports/999999` initially showed the alert `导出任务状态无法获取，请检查网络后刷新。` while still leaving `正在获取导出任务…` in the page status after 2.5 seconds and offering no clear return/retry action. This was the historical error-state UX finding; the later closure recheck confirms the page now provides recoverable retry and return actions.

### Authenticated route smoke refresh (2026-09-03)

The following known routes were opened in the authenticated `127.0.0.1` session and each rendered its expected primary heading with no page alert or new console error: `/`, `/career-materials`, `/resumes`, `/jobs`, `/generate`, `/ats?result=6`, `/communications`, `/applications`, `/interviews/history`, `/interview-assets`, `/resume-import`, `/account`, `/ai-consent`, and `/exports/51`.

### Interview configuration follow-up (2026-09-03)

- `/interviews`: switching `简历来源` to `平台简历` exposes the saved resume/version selectors; switching back to `外部简历` restores the resume-text input. Switching between `技术面试` and `JD 针对性`, and between a selected job and `不选择岗位（通用面试）`, updates the visible controls correctly. The default JD-targeted configuration was restored and no interview was started.
- The successful export page remains available after this pass with `PDF 已准备好` and one `下载 PDF` action. No new console errors were recorded.

### Locale persistence follow-up (2026-09-03)

- On `/applications`, switching to English changes the page heading and control labels; a full reload preserves the English locale and the application-stage control remains available. Switching back to Chinese restores `投递记录` and `投递阶段`.
- No application data or status was changed, and no new console errors were recorded.

### Consent and export persistence follow-up (2026-09-03)

- `/ai-consent`: after a full page reload, the synthetic account still shows `尚未授权` and the explicit `同意并启用 AI` action remains available. No consent was granted.
- `/exports/51`: after a full page reload, `PDF 已准备好` and the `下载 PDF` action remain available. No download was triggered.
- No new console errors were recorded.

### PDF delivery endpoint observation (2026-09-03)

- After clicking `下载 PDF` on task `#51`, the browser page-asset inventory observed the authenticated request `GET /api/exports/files/51`. The page stayed on `PDF 已准备好` with no alert or console error.
- The browser automation surface still did not emit a standard download event within the prior 5-second wait, so endpoint invocation is verified while browser file-persistence observation remains open.

### PDF delivery recheck (2026-09-04)

- A fresh click on `下载 PDF` again produced an observed page resource for `GET /api/exports/files/51`; task `#51` remained `PDF 已准备好` and no page console error appeared.
- The browser surface still exposes neither the response body nor a persisted local download path, so PDF content/file-system verification remains open rather than being reported as a product failure.

### Narrow export and resume-detail follow-up (2026-09-04)

- At `320x568`, `/exports/51` reports no horizontal overflow (`scrollWidth=320`), keeps `PDF 已准备好` visible, and keeps `下载 PDF` available.
- At the same viewport, `/resumes/82` reports no horizontal overflow; the version history, rename action, version actions, and both `导出 PDF` buttons remain present and usable. The viewport was reset afterward.
- The unsaved editor draft remained isolated in a separate tab with its recovery prompt intact; no version was saved or discarded, and no new console error was recorded.

### Job parsing and generation-entry follow-up (2026-09-04)

- `/jobs`: clicking `解析` for the existing parsed job returns the structured parse-result panel with the expected version, role excerpt, and keyword list; no alert or console error appeared.
- Clicking `生成草稿` for that job navigates to `/generate?jdId=69` and automatically checks the saved target role. The test stopped at the generation setup step, before any AI request or draft creation.

### Job-parser coverage finding (historical baseline, 2026-09-04)

- The saved JD text visibly contains `Kafka`, `微服务`, and `高并发`, but the rendered parse result lists only `Java`, `Spring Boot`, `MySQL`, and `Redis`; `requirements` is also empty for this JD.
- The repository runtime configuration declares those additional terms in `app.job.parser.keyword-dictionary`, so the browser result indicates that the configured dictionary is not fully taking effect in the running parser (or that the parser/runtime configuration is stale). This can under-score ATS coverage and weaken material selection for otherwise explicit JD requirements.
- No page console error appeared; this is a functional/data-quality finding requiring parser configuration or runtime verification.

### AI-input whitespace validation follow-up (2026-09-04)

- `/material-generation`: a three-space input is rejected for both `生成结构化草稿` and `AI 联想扩展` with `请先粘贴零碎资料。`; no AI request starts.
- `/achievement-guidance`: a whitespace-only description is rejected with `请选择简历版本并填写需要完善的描述。`; no AI request starts and no page console error appears.

### Resume-import state and locale follow-up (2026-09-04)

- `/resume-import` initially shows the supported PDF/DOCX/TXT limits, a file chooser, and a disabled `解析文件内容` action until a file is selected. No file was uploaded.
- Switching the page to English changes the step labels and instructions; switching back to `中文` restores `简历导入` and keeps the parse action disabled. No console error occurred.

### Post-parse data-integrity check (2026-09-04)

- After the job-parser recheck, `/career-materials` still shows `共 4 条结果`, the restored profile remains `测试求职者 / 后端工程师 / 100%`, and the known title-only `临时资料校验` record remains the only temporary material.
- `/jobs` still shows `1 个已保存岗位` with no `临时岗位标题校验` record; `/account` still shows the synthetic username/display name `codexqa_mtldthak`.
- The editor draft recovery prompt remained present in a separate tab, and no new page console errors were recorded.

### Communication-defect recheck (historical baseline, 2026-09-04)

- The scene filter defect remains reproducible: selecting `感谢` leaves the full template list visible until `刷新` is clicked; after refresh only `面试感谢信` remains.
- Editing the custom `本地测试求职信模板` still opens a form without the existing body; saving unchanged data keeps the dialog open and shows `模板包含非法占位符，仅允许白名单字段。` Cancelling closes the dialog without changing the template.

### Career-materials narrow-layout follow-up (2026-09-04)

- At `320x568`, `/career-materials` reports no horizontal overflow (`scrollWidth=320`); the `新建资料` action and profile card remain visible.
- Opening the `测试求职者` profile editor at the same width keeps the 11 profile fields, `保存个人档案`, and the accessible `返回资料库` button available without overflow. The editor was closed without changes and the viewport was reset.

### Email-change validation follow-up (2026-09-04)

- `/account` → `修改邮箱`: an invalid synthetic email is blocked by the native email constraint before submission; the confirmation modal remains open and no account change or network submission occurs.
- Cancelling restores the account page with the original `codexqa_mtldthak@example.test` email and no console error.

### Password-change validation follow-up (2026-09-04)

- `/account` → `修改密码`: an empty confirmation focuses the required current-password field and keeps the modal open.
- With synthetic local-only values, mismatched new passwords are rejected with `两次输入的新密码不一致。`; the modal was cancelled and no password or account setting changed.
- No page console error was recorded.

### AI-consent read-only recheck (2026-09-04)

- `/ai-consent` continues to show `尚未授权` and the explicit `同意并启用 AI` action after a full page reload. The action was not clicked and no AI request or consent change occurred.
- The unsaved resume-editor recovery prompt remained available in a separate tab; no console error was recorded.

### Account narrow-layout follow-up (2026-09-04)

- At `320x568`, `/account` reports no horizontal overflow and keeps both `修改邮箱` and `修改密码` visible.
- Both security dialogs open with their confirm/cancel controls visible and no overflow; both were cancelled without submitting a change, and the viewport was reset.

### Resume-list narrow-layout follow-up (2026-09-04)

- At `320x568`, `/resumes` reports no horizontal overflow; the new-resume form remains visible.
- Entering only a temporary resume name and submitting correctly focuses the required person-name field, creates no resume, and leaves the saved-resume count at `1`. The viewport was reset afterward.

### Jobs narrow-layout follow-up (historical baseline, 2026-09-04)

- At `320x568`, `/jobs` reports no horizontal overflow both before and after opening the parse-result panel.
- At that time, the existing job’s parse panel remained readable at this width; its keyword list contained only `Java`, `Spring Boot`, `MySQL`, and `Redis`, so the missing-`Kafka` parser coverage finding was still present. The later stale-runtime recheck confirms the restarted API exposes the full configured keyword set.
- No page console error appeared and the viewport was reset afterward.

### Export-expiry status recheck (2026-09-04)

- `/exports/51` still reports `PDF 已准备好` with `下载 PDF` available and no retry action, despite the displayed expiry time of `2026年9月4日 21:59`.
- The page showed no alert or console error; the editor draft recovery prompt remained intact in a separate tab.

### Account-to-consent navigation recheck (2026-09-04)

- `/account` → `管理 AI 数据授权` navigates to `/ai-consent`; the destination continues to show `尚未授权` and `同意并启用 AI`.
- The authorization action was not clicked and no AI request or consent change occurred.

### AI-helper narrow-layout follow-up (2026-09-04)

- At `320x568`, `/material-generation` and `/achievement-guidance` both report no horizontal overflow, keep their textarea and primary action visible, and record no page console errors.
- The viewport was reset afterward; no AI request was started and the editor draft remained intact.

### Interview-support narrow-layout follow-up (2026-09-04)

- At `320x568`, `/interviews/history`, `/interview-assets`, and `/ai-consent` each report no horizontal overflow and keep their primary filter/search/authorization controls visible.
- These pages produced no alerts or console errors; the viewport was reset and no authorization or data mutation was performed.

### Resume-editor narrow-layout follow-up (2026-09-04)

- At `320x568`, `/resumes/82/edit` reports no horizontal overflow; the unsaved-draft recovery prompt, `下一部分`, and `保存新版本` controls remain visible.
- The recovery prompt was left untouched, no editor content or version changed, and no page console error appeared. The viewport was reset afterward.

### Generation JD-input narrow-layout follow-up (2026-09-04)

- At `320x568`, `/generate` → `粘贴 JD` keeps the textarea and next-step control visible with no horizontal overflow.
- A short `测试` value keeps `下一步：选择资料` disabled; a synthetic JD longer than the minimum enables it and enters the `证据边界` step without starting generation. No console error appeared and the viewport was reset.

### Version-comparison narrow-layout follow-up (2026-09-04)

- At `320x568`, `/resumes/82/compare` reports no horizontal overflow; both version selectors, `交换左右`, and `只看有变化的章节` remain usable.
- Checking the changed-only filter and swapping left/right changes the comparison state to v1/v2 without modifying either version. No console error appeared and the viewport was reset.

### Communication-form input follow-up (2026-09-04)

- `/communications` can switch the generation type between `邮件正文` and `开场消息` while preserving the form.
- Submitting `AI 生成文案` with no selected version or target job focuses the version selector and does not start an AI request; no console error appeared.

### Core-route smoke scan (historical pre-fix snapshot, 2026-09-04)

- Fresh navigation through `/`, `/career-materials`, `/resumes`, `/jobs`, `/generate`, `/ats?result=6`, `/applications`, `/interviews`, `/interviews/history`, `/interview-assets`, `/resume-import`, `/account`, `/ai-consent`, and `/exports/51` produced the expected primary content with no new page console errors or alerts.
- `/match/42` was the only route in this historical scan with a console error: `TypeError: Cannot read properties of undefined (reading 'matched')` at `web/src/views/MatchResultView.vue:212`, leaving the page on `正在加载规则覆盖度…`. The later closure recheck confirms that this error is no longer present.
- The editor draft recovery prompt remained intact in a separate tab; no AI request, upload, or destructive action was performed.

### Workbench locale persistence recheck (2026-09-04)

- On `/`, switching to English changes the workbench copy; a full reload preserves the English locale. Switching back to `中文` restores `你的求职工作台`.
- The isolated editor draft recovery prompt remained intact, and no page console error was recorded.

### Job-description length boundary (2026-09-03)

- `/jobs` exposes `maxlength=5000` for the job-description field. A synthetic over-limit value was rejected on save with `无法保存岗位描述。请检查必填项后重试。`; no new job was created, the temporary fields were cleared, and no console error was recorded.

### Test-data side-effect check (2026-09-03)

- After reloading `/jobs`, the saved-job count remains `1` and no `临时岗位边界` record exists; the transient save-error alert clears on reload.
- After reloading `/career-materials`, the list consistently shows `4` records, including the previously created title-only synthetic `临时资料校验`. No additional record was created during this check, and no console error was recorded.

### Resume-editor navigation follow-up (2026-09-03)

- `/resumes/82/edit`: preview returns to the editor; the section sequence `工作经历` → `下一部分` → `实习 / 志愿经历` → `技能` loads the expected groups and existing fields. No field was changed and no new version was saved.
- The editor navigation pass recorded no console errors and left the pre-existing unsaved draft intact.

### PDF retry persistence follow-up (2026-09-03)

- The previously failed-then-retried export task `#50` still renders `PDF 已准备好` after revisiting its route; it no longer shows the failure state.
- The primary task `#51` also remains ready, and this recovery check recorded no console errors. No retry or new download was triggered.

### Input-branch and navigation follow-up (2026-09-03)

- `/generate` → `粘贴 JD`: a four-character description keeps `下一步：选择资料` disabled; a synthetic description longer than 20 characters enables it and opens the evidence-boundary step. No generation was started.
- `/resumes`: the blank new-resume form exposes required name and person fields; entering only a name focuses the missing person field. The temporary name was cleared and no resume was created.
- `/resume-import`: clicking the supported file button opens a file chooser event, while no file was selected or uploaded.
- An invalid route renders `页面不存在`; `返回工作台` returns to the authenticated home page. The desktop `准备资料` menu expands with the expected evidence/import links and closes cleanly.
- No new page console errors were recorded during this pass.

### Narrow-mobile layout follow-up (2026-09-03)

- At `320x568`, `/communications`, `/applications`, `/resumes/82`, and `/resume-import` all reported no horizontal overflow (`scrollWidth` equaled the viewport width).
- On the same viewport, the communications scene filter/refresh controls, applications search/follow-up filter/new-application control, and resume-import file chooser control remained visible and operable. The import parse action stayed disabled until a file is selected.
- The import page’s narrow layout was visually checked; its step indicator and file-selection card remain readable. The viewport override was reset to the browser default, and no new console errors were recorded.

### Workbench and material-edit follow-up (2026-09-03)

- The home-page `准备面试` next-action link opens `/interviews?jobDescriptionId=69`; the interview setup preserves the selected synthetic role and loads the expected JD-targeted configuration.
- Opening the existing `智能推荐服务` material loads its title, source text, and structured content in the edit form. Cancelling returns to `/career-materials` without changing the record or the current four-record count.
- No new page console errors were recorded during this pass.

### Generation-flow boundary follow-up (2026-09-03)

- Direct navigation to `/generate/materials` without a staged task shows `选材任务参数无效，请返回生成工作台重新开始。` and provides `返回生成工作台`.
- Direct navigation to `/generate/confirm` without a task ID shows `缺少任务 ID` and provides `重试生成`; neither route produced a page console error.
- At that time, starting from `/generate`, selecting the saved role, and entering the evidence-boundary step displayed all four synthetic materials, including the title-only `临时资料校验`. Toggling one `必须使用` control changed the summary from `0/4/0` to `1/3/0`. The later evidence-quality remediation excludes the invalid row from the usable set; the test stopped before the AI request because consent remained unavailable.

### Account-profile validation follow-up (historical baseline, 2026-09-03)

- At that time, `/account` marked the display-name input as required, but clearing it and submitting still showed `个人资料已保存。` and accepted the empty value. This was the historical validation defect; the current button guard disables submission for a blank name, as confirmed in the later closure recheck.
- The synthetic account was restored to display name `codexqa_mtldthak` and verified again after a full page reload. No account email, password, consent, or other setting was changed; no page console error was recorded.

### Profile, job, and interview-asset follow-up (2026-09-03)

- `/career-materials`: opening the `测试求职者` profile card exposes the personal-profile editor; `返回资料库` closes it without changing the profile or the four-record material count.
- `/jobs`: submitting a new job with only a title correctly focuses the required job-description textarea and creates no record. The temporary title was not saved.
- `/interview-assets`: the existing answer asset opens in edit mode with its question, original answer, AI suggestion, chapter, and material selections populated. Cancelling returns to the list with the single saved asset unchanged.
- No new page console errors were recorded during this pass.

### Profile-recommendation persistence finding (historical baseline, 2026-09-03)

- In `/career-materials` → the `测试求职者` profile editor, selecting `后端平台工程师基础简历` and clicking `读取建议` changes the profile form to resume-derived values and displays `已读取建议，请核对后保存。`.
- Without clicking `保存个人档案`, a full page reload still showed the changed profile (`张明远`, `尚未设置目标岗位`, `45%`). The read-suggestion action therefore persists changes despite the explicit “manually save” instruction; it should keep the suggestion in an unsaved draft until confirmation.
- The original synthetic profile was restored and verified after another reload (`测试求职者`, `后端工程师`, `100%`). No AI request or external data transfer occurred, and no page console error was recorded.

### Cross-page persistence check (2026-09-03)

- `/ats?result=6`: the persisted rules-only report remains visible after a full reload with score `80`, structure `100`, and keyword coverage `75`.
- `/applications`: the single synthetic application remains in `面试中` after a full reload; counters remain `面试中 1` and `已获录用 0`. No status or application content was changed.
- No new page console errors were recorded during this pass.

### Resume-detail navigation follow-up (2026-09-03)

- `/resumes/82`: opening the rename form and submitting an empty title leaves the original title unchanged and closes the form without creating a version or showing a page error.
- The `返回我的简历` action returns to `/resumes`, where the single saved resume remains listed. The unsaved editor draft was kept in a separate tab and was not opened, discarded, or saved during this check.
- No new page console errors were recorded during this pass.

### Login-form follow-up (2026-09-03)

- On the unauthenticated `http://localhost:5173/login?redirect=%2Fapplications` origin, an empty submit focuses the required username field and stays on the login page without an alert or console error.
- The password visibility control changes the input from `password` to `text` and back to `password`; the synthetic value was cleared and no credentials were submitted.

### Interview configuration and narrow-layout follow-up (2026-09-03)

- `/interviews?jobDescriptionId=69`: switching from external resume to platform resume exposes the saved resume and version selectors; selecting v2 (`122`) works, and switching back restores the required external-resume text area without starting an interview.
- At `320x568`, the interview page has no horizontal overflow (`scrollWidth=320`), and the mobile navigation opens with `aria-expanded=true`, exposes the expected grouped links, then closes cleanly. The viewport was reset afterward.
- No new page console errors were recorded during this pass.

### Interview-history and application-panel follow-up (2026-09-04)

- `/interviews/history`: selecting the saved role and applying the filter keeps the expected empty state (`还没有完成的面试会话。`); restoring `全部岗位` also returns to the same empty state without errors.
- `/interview-assets`: searching `性能优化` returns the saved answer asset; an unmatched keyword shows the zero-result state (`0 条已保存答案` / `还没有答案资产`), and clearing the search restores the single asset.
- `/applications`: the existing `面试中` record opens in edit mode with its resume, version, job, and saved cover-letter text populated. Cancelling leaves the record unchanged. The feedback panel opens with its saved feedback and closes cleanly.
- No new page console errors were recorded during this pass.

### Job-parser stale-runtime follow-up (2026-09-04)

- The first browser recheck still showed only `Java`, `Spring Boot`, `MySQL`, and `Redis` for the saved JD, even though the source configuration and `JdKeywordParserIT` included `Kafka`, `微服务`, and `高并发`.
- Investigation found a stale local server process: it started at 13:02, while the repaired classes were compiled at 13:13. `Start-LocalValidation.ps1` correctly reused the already-healthy server and therefore did not reload the new classes; this was a validation-environment issue, not a remaining parser implementation failure.
- After restarting only the local server on port 8080 and clicking `解析` again in `/jobs`, the browser displayed all expected keywords: `Java`, `Spring Boot`, `MySQL`, `Redis`, `Kafka`, `微服务`, and `高并发`. No alert or page console error appeared.
- The parser integration test and browser verification now agree; the saved JD's `requirements` remains empty because its text contains no matching experience or education requirement under the configured deterministic rules.
- Final automated checks: server `mvn test` passed with 555 tests and 3 environment-gated skips; PDF service `npm test` passed with 16 tests; web `npm run build` passed; the documentation diff passed `git diff --check`.
- The AI 4xx, missing-key, and intentionally failed Flyway-migration log lines observed during the server suite belong to explicit failure-path tests; they produced no test failures and are not a new browser defect.

### Evidence-quality remediation (2026-09-04)

The previously recorded downstream finding for `临时资料校验` is now closed. The root cause was that the ordinary career-material form synthesized `{ title, sourceText }` when the advanced JSON and source text were both empty, while the backend only enforced JSON size and specialized-type fields. As a result, a title-only row could be stored and sent into the generation boundary.

Implemented safeguards:

- Added a shared evidence check: a material must have non-blank source text or at least one meaningful structured value; the title label alone does not count.
- Create and update APIs reject title-only materials with a validation response. Existing title-only rows are preserved for traceability and repair rather than deleted.
- Material summaries and search results expose `evidenceReady`; the library labels unusable historical rows with a repair hint.
- The generation workbench displays the historical row but disables both selection actions and excludes it from counts/payloads. Server-side AI selection, legacy selection, and selected-material validation repeat the same boundary.
- The browser form shows the same validation before submission. The test account's existing `临时资料校验` row was not deleted or overwritten.

Browser recheck against `http://127.0.0.1:5173/` after restarting the API on port `8080`:

- `/career-materials`: `临时资料校验` remains visible with `缺少来源证据，请补充原文或结构化内容。`; the three valid materials remain unchanged.
- `/career-materials`: opening a new material, entering only `浏览器标题-only校验`, and saving shows `请填写来源原文或至少一项有意义的结构化内容。`; the form stays open and no record is created.
- `/generate`: the invalid row shows `缺少证据 · 不可用于生成`; `必须使用` and `不使用` are disabled for it, and the usable-material summary is `0 / 3 / 0`.
- No AI generation was started and no new browser console error or external data transfer was observed. `agent-browser` is not installed in this environment, so this pass used the Codex in-app browser fallback; this is a tooling limitation only.

Automated verification after the change:

- `server`: `mvn test` — 560 passed, 0 failures, 0 errors, 3 environment-gated skips.
- `web`: `npm run build` — passed, including the i18n guard and TypeScript build.
- Targeted server coverage includes title-only create rejection, controller `40001`, structured-evidence acceptance, and exclusion from AI candidates.

### Historical browser finding closure recheck (2026-09-04)

This pass rechecked the older findings after the corresponding fixes landed. The results below are closure evidence, not new defects:

- `/match/42` loads the persisted rules coverage report normally; the former `store.current.explanation` undefined error and stuck loading state did not recur.
- `/communications`: changing the scene selector to `感谢` immediately narrows the template list to `面试感谢信`; no manual refresh is required.
- `/communications` custom-template edit loads the existing body text into the dialog, so saving without changing the name no longer sends an empty body or triggers the placeholder validation unexpectedly.
- `/exports/999999` reaches a recoverable error state with `导出任务状态无法获取，请检查网络后刷新。`, `重试读取`, and `返回工作台`; it no longer leaves the page stuck on the loading message.
- `/account`: clearing the display name disables `保存资料`, preventing an empty profile from being submitted. The test value was not saved.
- Profile recommendation persistence: clicking `读取建议` changes only the active form draft and shows `已读取建议，请核对后保存。`. A second authenticated tab loaded from the server still showed `测试求职者 / 后端工程师 / 100%`, confirming that the imported values (`张明远`, no target role, `45%`) were not persisted without clicking `保存个人档案`. Leaving the first tab was correctly blocked by the unsaved-change guard; the draft was not submitted.

The original BR-001, BR-002, and BR-003 entries above remain as historical reproductions so the project retains the original symptom and root-cause record. Their current status is **closed and reverified**. No AI generation, consent change, external transfer, account-setting change, or additional test record was made during this closure pass.

### Current validation limits (2026-09-04)

- `agent-browser` is not installed in this environment. The browser checks used the Codex in-app browser against the existing authenticated local session, which is sufficient for this manual pass but does not replace a repeatable CLI E2E run.

### Optimization audit follow-up (2026-09-04)

- A new local-only synthetic account was registered through `/register` in the Codex in-app browser and redirected to `/career-materials`; the empty library state rendered normally and no AI request was started. Credentials and cookies were not recorded.
- Current branch checks passed: targeted server tests for career-material evidence, material selection, interview history, and application statistics; `web/npm run build`; and `pdf-service/npm test` (16 tests).
- The optimization/error audit is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It marks stale O-01/O-02/O-03/O-04/O-05/O-06/O-08/O-09/O-10/O-11/O-12/O-13/O-14 entries as closed and keeps the remaining structured-search, read-model, statistics, and evidence-E2E gaps explicit.
- The local test account is synthetic and intentionally scoped to loopback testing. External OAuth, email delivery, real AI-provider calls, and browser download-file persistence still require environment-specific verification before release.

### Further optimization audit follow-up (2026-09-04)

- Static review confirms `/api/ai/tasks/continuations` currently restores only `JOB_MATERIAL_SELECTION` and `JOB_GENERATION`; `MATERIAL_IMPORT`, `ATS_ANALYSIS`, `COMMUNICATION_GENERATE`, and `INTERVIEW_COACH` do not yet have a shared inbox or cross-device recovery contract. This is recorded as a P2 extensibility gap, not a current endpoint failure.
- Static review found an account-lifecycle security risk: `DELETE /api/auth/me` disables the user and revokes refresh sessions, while `JwtAuthenticationFilter` accepts an otherwise valid access token without checking `User.status`. With the default 3600-second access-token TTL, immediate post-delete invalidation is not guaranteed. No destructive browser check was performed; add a dedicated integration regression before enabling a user-facing delete flow.
- The `/account` page was checked in the Codex in-app browser with the authenticated local test session. Profile/security/AI-consent controls rendered normally; there is currently no data-export or delete-account control.
- Targeted backend verification passed: `AiTaskServiceTest` 11, `ResumeImportServiceTest` 11, and `JdKeywordParserTest` 7; total 29 passed, 0 failed, 0 errors, 0 skipped.
- Additional non-blocking expansion gaps are recorded in the ideation report: imported-resume source provenance and structured JD keyword ontology/word-boundary handling.

### Optimization audit follow-up — consent, pagination, and export storage (2026-09-04)

- Static review found that AI consent categories are not declared by one shared task policy: `AiTaskService.requiredCategories` falls back to an empty list for `MATERIAL_IMPORT`, `INTERVIEW_COACH`, `RESUME_OPTIMIZE`, `INLINE_OPTIMIZE`, and `ACHIEVEMENT_GUIDANCE`. The latter two explicitly carry a resume version and user-provided resume content, while material import carries raw career-material text; only the worker's interview branch adds `RESUME`/`INTERVIEW_ANSWER` (and optional JD) checks. The current full-consent UI path remains usable, but partial-consent paths can persist and process data without the matching category grant, while interview tasks can persist an input snapshot before the worker rejects it. This is recorded as the P1/P2 privacy and task-contract risk in finding #10; no AI request was started during browser validation.
- Static review found that resume, JD, application, interview-asset, and ordinary career-material list endpoints return unbounded `List` responses. Career-material search already uses `Page`, so this is a P2 scalability gap rather than a current page failure.
- Static review found that PDF export storage is a concrete local filesystem service rooted at `./pdf-output`. Expiry cleanup and retry semantics are present, but separate containers or instances do not share files; a storage port plus S3/OSS implementation is a P2 deployment extension candidate.
- These findings, their code evidence, risk levels, and proposed acceptance tests are recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). No source-code behavior was changed in this documentation-only follow-up.

### Architecture deepening follow-up (2026-09-04)

- A current-session architecture scan found two high-leverage extension candidates. First, AI task capability metadata is scattered across worker dispatch, consent checks, quotas, generic-controller restrictions, and continuation queries; `TaskExecutionService` alone is about 322 lines and already contains a second, inconsistent consent mapping. Recommended direction: one internal task-capability policy drives create, execute, retry, and resume behavior.
- Second, resume document semantics are duplicated across `web/src/resume/sectionRegistry.ts`, Java validators and section maps, and `pdf-service/src/templates/classic.js`. The generation schema intentionally supports a subset today, but adding a new resume section will require coordinated edits and cross-runtime checks. Recommended direction: a versioned resume-document contract with shared fixtures, while keeping Web/Java/PDF rendering implementations separate.
- A self-contained visual report was generated and opened from `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-150009.html`. It records files, problems, before/after structures, recommendation strength, and the top recommendation.
- This was a documentation and architecture-review pass only. No source behavior was changed, no AI call was started, and no team/subprocess was created.

### Cross-runtime contract follow-up (2026-09-04)

- Static review found a P2 contract-drift risk: `web/src/api/ai.ts` declares `EXPORT_PDF` in the `AiTask.taskType` union while the server `AiTaskType` enum does not; the AI consent policy version is also duplicated in the Web constant and server configuration. No OpenAPI document or generated client contract was found.
- The existing `web/src/types/resume.ts` is not yet an authoritative editor contract: the current import scan finds it used by `ResumePaper.vue`, while `ResumeEditorView.vue` still mutates `Record<string, any>` drafts. This strengthens the case for an incremental, machine-checked contract rather than a one-shot JSON rewrite.
- The current Web build and existing browser flows remain healthy; this is an extensibility risk for additional clients, task types, or independently deployed versions, not a confirmed current-page failure.
- The ideation report now records a machine-checked API/async-task contract as a follow-up candidate. No source behavior was changed in this pass.
- `RateLimitFilter` was also checked as a deployment extension point: its IP/path buckets live in one JVM and are intentionally cleared on restart, so multiple API replicas would not share login/register/refresh limits. This is a documented MVP trade-off, not a current local failure; Redis or another shared limiter should be evaluated only when multi-instance exposure is real.

### Async failure-contract audit (2026-09-04)

- Static review found that several AI worker branches pass `e.getMessage()` or a provider error string into `ai_task.error_message`, and `AiTaskStatusResponse` returns that field to the browser. The interview flow has the same shape in `InterviewOperationSupport.markAttemptFailed` and `interview_ai_attempt.error_message`; both database/entity limits are 1024 characters. Unlike the PDF lease path, these AI failure persistence paths have no shared truncation or public-message mapping.
- This can become a secondary failure if an adapter/validation exception exceeds the column length, and it can expose implementation details if an upstream exception is user-visible. No real AI call or failure injection was performed, so this remains an open P2 reliability/privacy risk rather than a reproduced browser defect.
- Acceptance coverage should force a long and an internal-looking failure through each task family, assert durable `FAILED` state, bounded public output, a stable failure code, and trace-based internal diagnosis. Retryability and fallback should be structured fields, not parsed from free-form text.
- The current in-app browser home smoke rendered normally with no visible error. `agent-browser` remains unavailable, so this audit did not claim a repeatable CLI E2E result. No source behavior was changed in this documentation-only follow-up.

### Export replay and idempotency audit (2026-09-04)

- Static review found that `POST /api/exports/pdf` creates a new `export_task` on every accepted request. Neither the controller nor the service accepts an idempotency key, and `export_task` has no request fingerprint or deterministic uniqueness contract.
- `ResumeDetailView` disables the export button only while the current browser request is in flight. A network retry, page refresh, or another device can still enqueue the same immutable resume version and template multiple times, causing duplicate PDF rendering and local/object-storage usage.
- Existing export tests cover creation, ownership isolation, download, and expiry, but do not assert replay or concurrent-create behavior. This is an open P2 scalability/cost risk; the single export flow remains functional and no duplicate export was intentionally created during browser validation.
- Recommended acceptance coverage: same idempotency key/same payload returns the same task, same key/different payload conflicts, concurrent first submissions converge, and a non-expired successful file can be reused without a second render. No source behavior was changed in this documentation-only follow-up.

### Runtime configuration audit (2026-09-04)

- Static review counted 65 `@Value` injections in the server. Operational defaults are split between `application.yml` and constructor fallbacks; for example, `app.ai.bailian.read-timeout-seconds` defaults to 300 seconds in YAML but 60 seconds in `BailianAiProvider`, while `app.resume-import.max-bytes` is only declared as a code fallback.
- `ProductionConfigurationValidator` protects production secrets and the secure cookie flag, but does not validate ranges or relationships for worker leases, polling intervals, batch sizes, TTLs, import/input limits, scoring weights, or timeout values. The current default profile starts normally; this is an open P2 deployment-consistency risk, not a reproduced runtime error.
- Recommended acceptance coverage: boot default/local-h2/prod configurations plus missing and invalid overrides, assert one effective value per setting, fail fast on invalid ranges, and verify that health/info output never contains secrets. No source behavior was changed in this documentation-only follow-up.

### Worker claim and index audit (2026-09-04)

- Static review found a mismatch between comments and SQL: both AI and PDF claim queries describe `FOR UPDATE SKIP LOCKED`, but the actual native queries end in `FOR UPDATE`. Concurrent worker instances can therefore wait on locked candidate rows instead of skipping them.
- The AI queue index is `(status, lease_expires_at)`, while the PDF queue migration adds `(status, lease_expires_at, id)` even though both queries order by `id`. This may add sorting/range work as the AI queue grows.
- The current local single-worker path remains functional; no duplicate claim or lock wait was reproduced because this requires a controlled multi-worker database run. Treat it as an open P2 queue-scaling risk. H2 tests are insufficient evidence for MySQL lock behavior, and any `SKIP LOCKED` change must be checked against the project's MySQL 8.x production baseline and historical 5.7 migration gate.

### Resume import resource-boundary audit (2026-09-04)

- Static review confirmed the dependency baseline: PDFBox `2.0.32` and Apache POI `5.3.0`. PDFBox's `PDDocument.load(byte[])` path retains the supplied byte array and uses the default main-memory scratch setting; `ResumeImportService` has no PDF page-count, extracted-text, or parse-time budget.
- Apache POI `5.3.0` does provide baseline OOXML ZIP checks. The inspected defaults are `MIN_INFLATE_RATIO=0.01`, `MAX_ENTRY_SIZE=4 GiB`, and `MAX_FILE_COUNT=1000`; these checks reduce classic ZIP-bomb exposure but do not impose the application's total expanded-data, paragraph-count, output-text, or synchronous request-time budgets. The current DOCX path collects all paragraph text into one string.
- The service enforces a 5 MB `MultipartFile` limit, but `app.resume-import.max-bytes` is only present as a constructor fallback and is not declared in the main `application.yml`. `normalizedText` has no separate maximum length. Existing tests cover normal TXT/PDF/DOCX, size/type validation, and damaged files, but not high-expansion DOCX, excessive paragraphs, PDF page/text limits, or parse timeouts.
- This is an open P2 input-resource/reliability risk, not a reproduced normal-file failure. No malicious archive or oversized document was generated or uploaded during this audit, and no source-code behavior was changed. The finding and proposed acceptance gates are recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).

### Extensibility architecture audit (2026-09-04)

- `AiProviderRegistry.route` currently returns the first adapter whose `supports(type)` is true. The common interface exposes `isAvailable()`, but the registry does not use it; only two ATS call paths check availability after routing. `BailianAiProvider.supports` returns true for every task, so adding a second provider would make bean/list order an implicit routing rule. This is an open P2 extensibility/reliability risk, not a reproduced single-provider failure.
- Application workflow knowledge is duplicated across `ApplicationService.TRANSITIONS`, `ApplicationsView.vue` status lanes/terminal statuses/labels/transitions, and `web/src/api/application.ts`. `ApplicationService.stats` aggregates the current six statuses with fixed formulas, while `ApplicationRecord` has no status-history table. The current six-state flow and tests pass; adding stages or reliable stage-duration analytics would require synchronized edits and a migration contract.
- Acceptance gates are recorded in the ideation report: provider tests must cover availability, ordering independence, fallback and sensitive-input policy; workflow tests must machine-check Java/Web parity, legal/illegal transitions, concurrent updates, history replay and stage-duration calculations.
- A visual architecture report was generated at `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-152034.html` and opened in the Codex panel. This was a static/documentation-only pass; no source behavior, database data, AI provider call, or browser state was changed.

### PDF renderer readiness and capacity audit (2026-09-04)

- `pdf-service/src/server.js` exposes `/health` as an unconditional `200 { status: "UP" }`; Chromium is launched lazily by `browserPool` on the first `/render`. The production Compose healthcheck therefore verifies the Node listener, not Chromium startup or template render readiness.
- `pdf-service/src/browserPool.js` shares one browser but creates a new page for every concurrent request without a semaphore, queue limit, overload response, or drain state. The current API export worker processes its claimed batch serially, which is an incidental local bound and not a capacity contract for multiple API/PDF instances.
- The renderer already has a 1 MB JSON body/payload limit, a service token, and graceful SIGTERM cleanup. The open gap is readiness/capacity semantics: browser launch failure, overload, and shutdown should produce distinct retryable signals and bounded resource use. This is an open P2 deployment/reliability risk, not a reproduced single-instance export failure.
- Acceptance gates are recorded in the ideation report: inject Chromium launch/disconnect failures and concurrent peaks, assert readiness behavior, bounded page/queue counts, controlled 429/503 responses, no new work after drain begins, and consistent API task retry classification. No stress or failure-injection request was sent during this audit.
- Baseline verification after the static review: `pdf-service/npm run check` passed and `pdf-service/npm test` passed 16/16. These tests cover browser reuse/recovery, production token guards, and template rendering; they do not prove readiness or overload behavior.

### Release-readiness script failure-code audit (2026-09-04)

- `scripts/Test-ReleaseReadiness.ps1` wraps commands in `Invoke-CheckedCommand`, which checks only the final `$LASTEXITCODE` after `Invoke-Expression` returns. Its PDF invocation is `npm run check; npm test`, so an earlier syntax-check failure can be overwritten by a later successful test.
- The behavior was reproduced without changing the repository: a first process exit code of `7` followed by a second exit code of `0` produced `first=7, final=0`. This is a release-gate orchestration defect, not a product-page failure.
- The GitHub and Gitee workflows currently run the PDF syntax check and test as separate commands, so this finding does not claim that the existing CI jobs passed an invalid PDF service. The local release-readiness script still needs a fail-fast regression test and explicit per-command exit-code handling.
- Recommended acceptance: force the first PDF check to fail while the second passes and assert the script exits non-zero; validate production, registry-overlay, and IP-test Compose manifests as separate static gates. No script or source-code behavior was changed in this audit.
- Post-audit baseline: `Test-ReleaseReadiness.ps1 -SkipBackendTests -SkipFrontendE2E` passed; production Compose static validation, PDF syntax/tests (16/16), and Web production build all passed. The command started no containers and made no AI/provider request.

### MySQL 5.7 migration-gate drift audit (2026-09-04)

- The repository currently contains Flyway migrations V1 through V24. `server/src/test/java/com/intelligentresume/database/MySql57MigrationLiveIT.java` still expects a V19 baseline to execute exactly two migrations and end at V22; `scripts/Invoke-MySql57MigrationGate.ps1` and its failure text use the same old V19-to-V22 contract.
- Because `MYSQL57_LIVE_TEST=true` is an environment-gated test, the normal server suite does not expose this stale assertion. If the live gate is enabled today, the expected migration count and maximum-version assertion are no longer aligned with V23/V24. The gate also does not validate the new V23 tables/seed rows or V24 English seed rows on MySQL 5.7.
- `FlywayMigrationIT` runs against H2 MySQL mode and currently has no V22/V23/V24 schema/seed assertions. H2 evidence is useful for syntax and application tests but is not evidence of MySQL 5.7 DDL behavior.
- Recommended acceptance: update the gate to the current migration target, assert V23/V24 structures and seed counts, verify a second `migrate()` is a no-op, and run it against the supported MySQL 5.7 environment. No migration or test source was changed in this audit.
- Current H2 baseline check: `mvn -q "-Dtest=InterviewAssetServiceTest,FlywayMigrationIT" test` passed. The Flyway log validated and applied all 24 current migrations, then confirmed the database was up to date on a repeat migrate; this is H2 evidence only.

### AI quota observability contract audit (2026-09-04)

- `AiQuotaService` enforces a per-user, per-task-type daily limit using attempt counts (`countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter`). `AppObservability` registers `resume_ai_quota_daily_tasks_created` from the global task-row count (`countByTaskTypeAndCreatedAtAfter`) and separately exposes a limit gauge tagged only by task type but named `...per_user`.
- Consequently the dashboard's “AI quota consumption” series is neither per-user nor attempt-equivalent: retries increase enforcement usage without increasing the task-row count, while multiple users inflate the gauge independently of any one user's limit. Current quota enforcement is not changed by this mismatch; the risk is misleading capacity/cost diagnosis as usage grows.
- Existing `AppObservabilityTest` covers retry/schema-rejection/quota-rejection counters but does not verify the gauge source, retry behavior, multi-user aggregation, or naming contract.
- Recommended acceptance: choose explicit task-created versus attempt semantics, expose the matching aggregation dimensions, and add a test with two users plus a retry. No observability code was changed in this audit.

### Interview-answer asset concurrency audit (2026-09-04)

- `InterviewAssetService.create` describes `(userId, interviewRecordId)` as an idempotency rule and performs a read-before-insert. The V5 `interview_answer_asset` table has no unique constraint for that pair, so concurrent requests can both observe no existing asset and insert duplicates.
- `InterviewAnswerAsset` inherits `BaseEntity`, which has no `@Version`. `update` saves the asset and then replaces section/material rows, so concurrent edits have no optimistic-conflict contract and can silently overwrite one another.
- This was a static finding; the single-request asset path is covered by existing tests and no duplicate data was intentionally created. Recommended acceptance: concurrent same-record creates converge to one asset, `NULL interviewRecordId` remains allowed for independent assets, and stale updates return a conflict instead of silently winning.
- Current baseline: `InterviewAssetServiceTest` completed successfully in the same targeted Maven run. No concurrent create/update test was run, so the race remains an open verification item.

### Communication-template usage counter concurrency audit (2026-09-04)

- `CommunicationService.saveDraft` increments `CommunicationTemplate.usageCount` with a read-modify-save sequence. The V23 template table and entity have no version column, and system templates use `user_id=NULL`, making this a shared cross-user write point.
- Concurrent saves can both read the same value and write the same incremented value. Because template search orders by `usageCount DESC`, the loss is externally visible as inaccurate popularity ordering and later operational analysis.
- The single-request save path remains covered and was not changed. Recommended acceptance: concurrent saves produce an exact count, using an atomic increment or an append-only usage event with a defined aggregation policy. No template code was modified in this audit.

### AI task retention and data-growth audit (2026-09-04)

- `ai_task` stores `input_snapshot_json`, `result_json`, error/status fields and timestamps, but has no expiry/archive/deleted-at field. The repository contains no AI-task cleanup scheduler, retention configuration, or user-facing task deletion endpoint; `continuations` is a read filter, not a retention policy.
- The generic `MATERIAL_IMPORT` browser path places `rawMaterialText` in the task input, and other task results can contain generated resume/communication content. Terminal tasks therefore remain a durable copy of potentially sensitive data unless an external database cleanup process exists; no such process was found in the repository.
- This is a static P1/P2 privacy and storage-growth risk, not a reproduced task failure. Recommended acceptance: define per-task retention and legal/audit exceptions, redact or externalize large payloads, propagate account deletion/consent withdrawal semantics, add batch cleanup indexes, and benchmark cleanup on realistic task volumes. No task data was deleted and no source behavior was changed.

### Interview AI stale-takeover timing audit (2026-09-04)

- `InterviewOperationSupport.PROCESSING_TAKEOVER_SECONDS` is hard-coded to 75 seconds, and `isStale` uses only `attempt.updatedAt`. `InterviewStartService` and `InterviewAnswerService` can mark a still-`PROCESSING` attempt as failed when a duplicate request or state read arrives after that threshold.
- The current `application.yml` default for `app.ai.bailian.read-timeout-seconds` is 300 seconds, while `BailianAiProvider` has a 60-second constructor fallback. The 75-second takeover budget is not constrained by either effective provider timeout or the repair-call budget. A legitimate slow response can therefore be abandoned while the provider is still running; a later retry may issue another provider call.
- This is a static P1/P2 asynchronous-consistency and cost risk, not a browser failure. No real slow AI call or duplicate billing was induced. Recommended acceptance: use a controllable delayed provider at 74/76 seconds and beyond the configured read timeout, then exercise duplicate GET/POST, retry, and late original completion; assert provider call count, visible result, attempt state, and user message.

### Quota and follow-up query-index audit (2026-09-04)

- `AiQuotaService` filters `ai_task` by `user_id`, `task_type`, and `created_at`; V1 has only `idx_ai_task_user (user_id)`, and the idempotency unique key cannot provide the needed task-type/time range access path.
- `InterviewOperationSupport.checkInterviewQuota` sums `interview_ai_attempt.attempt_count` by `user_id` and `created_at`; V20 declares idempotency/session unique keys but no user/time index.
- `ApplicationRecordRepository.findByUserIdAndFollowUp` filters TODAY/OVERDUE by `user_id` and `nextFollowUpAt` and orders by `updatedAt`; the current application index remains `(user_id, updated_at)`. Normal data and current list behavior are healthy, but this is a static P2 query-degradation risk. Recommended acceptance: run MySQL `EXPLAIN` and warm/cold benchmarks at production-like row counts, verify range scans and sort cost, then add only indexes that improve the plan without unacceptable write overhead.

### Communication AI draft side-effect audit (2026-09-04)

- `CommunicationAiService.executeTask` saves `communication_draft` after provider/schema success, before `TaskExecutionService.executeCommunicationGeneration` calls `leaseService.releaseSuccess`; the latter ignores the boolean that indicates a stale owner.
- If a lease expires or another worker takes over, a draft can already be committed while the AI task result is discarded. The current lookup deduplicates only an exactly equal text and has no task-id/request-fingerprint uniqueness, so a retry with different model output can leave multiple drafts.
- This is a static P2 distributed-consistency/idempotency risk. No multi-worker lease expiry was injected and no draft data was deleted. Recommended acceptance: force lease expiry before provider completion, run concurrent same-task and retry scenarios, and assert stale workers leave no unowned draft while a successful task maps to one defined draft projection or versioned retry result.

### PDF stale artifact cleanup audit (2026-09-04)

- Static review found `ExportTaskWorker` stores the rendered bytes before calling `ExportTaskLeaseService.releaseSuccess`, but ignores `false` when the lease owner is stale. The random storage key is then not attached to any task.
- `ExportExpiryService` only scans expired `SUCCESS` rows, so a stale render can leave an unreferenced local/object-storage file. This is an open P2 resource-lifecycle risk; no lease takeover was injected and no export file was deleted.
- Acceptance: force owner loss after storage and before task commit, assert stale success removes the new file, retry leaves no orphan key, and cleanup failure is observable.

### PDF expiry response consistency audit (2026-09-04)

- Static review found `ExportService.get` ignores the boolean result of `expireIfDue` and unconditionally mutates its in-memory response to `EXPIRED`.
- `ExportExpiryService` intentionally returns `false` when storage deletion fails and leaves the database row as `SUCCESS` for a later retry. The status response can therefore disagree with persisted state and with the download path.
- Acceptance: inject delete failure and concurrent status changes, then assert GET, database state, subsequent GET, and download behavior share one defined contract. No failure injection was run.

### Resume-version eligibility consistency audit (2026-09-04)

- Static review found `ScoringService.score` checks only parent-resume ownership and accepts an archived `ResumeVersion`; ATS, PDF export, application creation, and communication paths reject the same version when `deletedAt` is set.
- This is a P2 cross-module soft-delete contract risk, not a cross-user leak. Existing archive/restore UI coverage does not exercise every downstream consumer.
- Acceptance: archive a non-current version, exercise scoring/ATS/export/application/communication/interview context, assert a single “reject” or “read-only historical analysis” policy, then repeat after restore. No source behavior was changed.

### Application version lookup fan-out audit (2026-09-04)

- Static review found `ApplicationsView.findResumeByVersionId` makes one full `listVersions` request per resume in parallel. This reduces serial latency but remains an O(resume-count) N+1 request pattern.
- A larger resume library will increase browser request fan-out and repeated database queries during one edit operation. Current small-data browser smoke remained healthy.
- Acceptance: fixture 100 resumes, measure request count/bytes/edit latency, and replace the fan-out with an application response containing `resumeId` or a batch version-to-resume query. No frontend behavior was changed.

### Account-deletion async fencing audit (2026-09-04)

- Static review found account deletion disables the user and revokes refresh sessions, but does not withdraw AI consent, cancel queued `ai_task`/`export_task` rows, or write a deletion epoch. AI execution checks consent only; PDF execution reads the resume version directly.
- A queued task can therefore continue after deletion when the previous consent event is still `GRANTED`. This is an open P1/P2 deletion/privacy lifecycle risk. No destructive deletion was performed and no provider/PDF request was sent.
- Acceptance: queue AI/PDF work, delete the account, assert cancellation or safe failure before claim/commit, zero provider/render calls, immediate access/refresh invalidation, and correct delete-vs-claim race handling.

### Latest documentation-only verification (2026-09-04)

- `mvn -q "-Dtest=InterviewAssetServiceTest,FlywayMigrationIT" test` passed with exit code 0; H2 applied and revalidated all V1–V24 migrations, including the V17→V24 upgrade scenarios.
- Related normal-path regression bundle `ExportServiceTest,ScoringServiceTest,ApplicationServiceTest,ResumeVersionServiceTest,AuthServiceTest,TaskExecutionServiceTest` also passed with exit code 0; this confirms baseline behavior only and does not close the new concurrency/failure-injection gaps.
- `git diff --check` reported no whitespace errors. The new findings remain static review results; no source behavior, database data, AI call, or browser account state was changed by this pass.

### AI-safe career-material projection audit (2026-09-04)

- Static review found that `CareerMaterialAiSnapshotSanitizer` only changes `ACHIEVEMENT` metric visibility and returns other material entities unchanged. `JobGenerationPromptBuilder` and `MaterialSelectionPromptBuilder` then serialize `sourceText` and `contentJson` into provider input.
- Career materials accept free-form source text and advanced JSON. A synthetic email, phone number, address, or URL stored there therefore has no shared redaction guarantee, even though the product contract says contact details must not be sent to the model. Communication and interview paths maintain separate sanitizers, which makes future field additions easy to miss.
- This is an open P1/P2 static privacy and extensibility risk, not a reproduced provider leak. No real AI request was sent. Recommended acceptance: inject synthetic sensitive values into every material type and exercise selection, generation, communication, and interview payload builders; assert the provider payload excludes the original values while metric-display policy remains correct.

### AI prompt-context budget audit (2026-09-04)

- Static review found a 65535-character `sourceText` limit, a 65536-byte `contentJson` limit, up to 60 selection candidates, and up to 30 confirmed generation materials, but no shared prompt byte/token budget or deterministic truncation policy.
- The same material is serialized through separate selection/generation builders. New fields or material types can push failure into provider context limits, with no stable local error category or cost signal. This is an open P2 AI reliability/cost expansion risk; normal small-data flows remain healthy.
- Recommended acceptance: benchmark 1/30/60 near-limit materials, capture serialized size and estimated tokens, reject over-budget input locally with a stable error, and verify sensitive-field redaction and evidence fields survive any prioritised projection. No source behavior was changed.

### PDF presentation-language contract audit (2026-09-04)

- Web resume preview reads section labels through i18n, while `pdf-service/src/templates/classic.js` fixes `lang="zh-CN"` and section titles such as `个人概要` and `工作经历`. `POST /api/exports/pdf` carries template code but no locale/output-language field.
- Chinese export and the current browser smoke are normal. English UI users or a future multilingual export will see preview/download semantic drift, so this is an open P2 cross-runtime presentation-contract risk rather than a current rendering failure.
- Recommended acceptance: preview and export all seven templates under `zh-CN` and `en-US`, compare section order/labels/custom sections, define unknown-locale fallback, and retain URL safety and resume-fact invariants. No PDF request was sent in this static pass.

### Resume current-version pointer concurrency audit (2026-09-04)

- `ResumeVersionService` uses `MAX(version_no)+1` plus a unique constraint to turn concurrent version-number allocation into a conflict, but `BaseEntity`/`Resume` have no `@Version`. `ResumeService.setCurrentVersion`, first-version creation, and restore save `currentVersionId` directly.
- Two tabs or API instances can therefore race while changing the current pointer and silently leave the last entity write as the visible winner. This is an open P2 concurrency/extension risk; no concurrent mutation was injected and no user data was changed.
- Recommended acceptance: run concurrent create/switch/restore/archive/delete scenarios, assert pointer updates have a defined epoch or conflict response, preserve all immutable versions, and make Web retry behavior explicit.

### Latest documentation-only verification (2026-09-04)

- The existing targeted Maven bundles and PDF/Web checks remain the baseline evidence; this pass added documentation only. `git diff --check` must be rerun after the documentation edits.
- The new four findings are static review results. No source behavior, database data, local account, browser state, external provider, or PDF renderer was changed.

### Second-round interview, communication, and async-task audit (2026-09-04)

- Static review found that `TemplatePlaceholderService` accepts whitespace inside a legal placeholder during validation, but `fill` only replaces the no-whitespace spelling. A custom template such as `{{ candidateName }}` can therefore save successfully and preview with the unresolved placeholder while `missingPlaceholders` remains empty. Existing tests cover only `{{candidateName}}`; no template data was changed.
- Static review found that `web/src/api/materialGeneration.ts` polls `MATERIAL_IMPORT` for at most about 91 seconds, while the shared `useTaskPolling` window is about 5 minutes and the backend provider read timeout defaults to 300 seconds. A queued/slow task can be reported as timed out while continuing in the worker; this page does not expose a recovery task ID, and the shared continuation query does not include `MATERIAL_IMPORT`. No AI request was sent.
- Static review found that interview rule fallback questions/feedback and report summaries are hard-coded in Chinese even when the session stores `InterviewOutputLanguage.EN`. English users can therefore receive Chinese after AI fallback or in the report. No live AI failure was induced.
- Static review found that `InterviewStateResponse` omits the stored `interviewMode` and `outputLanguage`. After refresh, `InterviewView` can create a follow-up using the page default/current locale rather than the original session snapshot. No follow-up task was created during this pass.
- These are open P2 functional/extensibility risks. Recommended acceptance is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md) under findings 39–42. This pass made documentation-only changes and did not modify source behavior, database data, browser state, or external services.

### Third-round local H2 browser smoke and resource-boundary audit (2026-09-04)

- `agent-browser` is not installed in this environment, so the repeatable CLI browser skill could not run. As a documented fallback, the Codex in-app browser tested the local Vite page at `http://127.0.0.1:5173/` while the project `local-h2` Spring profile ran in memory on API port `8080`. `/api/system/health` returned HTTP 200 and Flyway applied V1–V24.
- A synthetic local account was registered and used to verify authenticated route loading, the career-material empty-form required-field guard, saving one synthetic career material, detail rendering, keyword search, the AI-consent status page, and the material-generation empty-input guard. The home page, Chinese/English switch, primary navigation expansion, unauthenticated route redirects, correct protected route names, and unknown-route 404 also behaved as expected.
- Before the H2 API was started, the frontend-only smoke emitted expected Vite proxy `ECONNREFUSED` messages for `/api/auth/refresh` and `/api/system/health`; those were caused by the intentionally absent backend during setup. After API startup, the health check and authenticated page requests completed normally.
- AI consent was not granted; no real AI/provider or PDF request was sent, no file was uploaded, and no destructive action was performed. Credentials and cookies were not recorded. The local H2 account/data disappear when the process stops.
- Static follow-up added two open P2 risks to the ideation report: `RateLimitFilter.maxBuckets` does not enforce a hard bucket cap, and generic AI task input has no server-side semantic size/depth contract. These findings were not “fixed” in this audit; acceptance gates are recorded in the ideation report under findings 43–44.

### Authentication concurrency and uniqueness audit (2026-09-04)

- Static review found that refresh rotation reads `AuthSession` without a row lock or version check, then revokes and inserts the successor session. Two concurrent requests using one active refresh token can therefore both succeed before either transaction observes the revocation, producing multiple successor token pairs and bypassing the documented reuse-family detection. The existing auth tests cover only serial rotation/reuse; no source behavior was changed.
- Static review also found a check-then-save race for registration and email changes. MySQL uniqueness constraints protect the row, but the auth service and global handler do not map a concurrent `DataIntegrityViolationException` to the same stable conflict response used by the pre-check path; a losing request may surface as an unhandled 500. No concurrent conflict was injected and no account data was changed.
- Recommended acceptance is recorded in the ideation report under findings 45–46: run two real transactions against the target MySQL isolation level, assert one refresh winner and no duplicate successor tokens, and assert concurrent username/email claims produce exactly one success plus stable conflict responses with no partial session.
- Targeted baseline verification after this audit: `mvn -q "-Dtest=AuthServiceTest,AuthControllerIT" test` passed, and `git diff --check` passed. This validates only normal/serial authentication behavior and documentation formatting; it does not close the real-database concurrency gaps.

### Career-material and AI-task seam audit (2026-09-04)

- Static review found a current-worktree regression in the career-material summary path: `CareerMaterialService.list` now loads complete entities and filters in Java after the lightweight summary projection was removed. Each entity can carry up to 65,535 characters of source text plus JSON content, while the list DTO needs only summary fields. This is a P2 growth risk; no data or source code was changed in this audit.
- Static review found that generic AI task idempotency uses a check-then-save sequence backed by a database unique key, but does not recover a concurrent unique-key collision. Two identical concurrent replays can therefore produce one task plus an unhandled 500 instead of one shared task response; serial idempotency tests remain green.
- Static review found inconsistent `Idempotency-Key` handling across AI entry points: some trim, some enforce 64/128-character limits, and generic/job-selection paths do neither even though `ai_task.idempotency_key` is `VARCHAR(128)`. Overlong keys can fail at persistence, and whitespace variants can create different logical tasks. No oversized key was submitted.
- Recommended acceptance for findings 47–49 is recorded in the ideation report: restore a lightweight SQL projection, run real concurrent idempotency races with same/different fingerprints, and centralize key normalization/length validation before lookup and persistence.
- Targeted baseline verification: `CareerMaterialServiceTest` (19), `CareerMaterialControllerIT` (10), `JobMaterialSelectionServiceTest` (3), `AiTaskServiceTest` (11), and `AiTaskControllerIT` (9) all passed; `npm run build` and `git diff --check` also passed. These checks do not cover real database concurrency, SQL column projection, or oversized-header behavior.

### Summary/read-model and API-contract audit (2026-09-04)

- Static review found several list-shaped APIs that still load full entities or expose long fields: job-description summaries read the complete `MEDIUMTEXT jdText` before making a preview, resume-version summaries carry `generationContext` from the full version entity, application follow-up lists return multiple `TEXT` message/feedback fields, and template/interview-history summaries load template body or external resume text. This is distinct from pagination: paging alone would not prevent unnecessary wide-row reads. No source behavior or stored data was changed.
- Static review also found a runtime contract drift: the frontend requires `ResumeVersion.resumeId`, while backend `ResumeVersionDetail` and `ResumeVersionService.toDetail` do not return it. Existing screens do not currently dereference the field, so the build remains green; a future consumer of the detail endpoint could receive `undefined`. No API contract or source behavior was changed.
- Recommended acceptance for findings 50–51 is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): add dedicated summary projections/read models, benchmark wide-row list behavior at realistic volumes, and add JSON contract tests or generate the frontend type from the server contract. The open gaps are SQL column selection, response-size/latency measurement, and runtime JSON-field validation.
- Static review additionally found that the home-page continuation list maps the full `AiTaskStatusResponse`, including potentially large `resultJson`, even though `HomeView` uses only task metadata and later fetches the task by ID. This is a P2 read-model and data-minimization risk; no AI task was changed and no provider call was sent.
- Recommended acceptance for findings 50–52 is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): add dedicated summary projections/read models, benchmark wide-row list behavior at realistic volumes, add a metadata-only continuation response, and add JSON contract tests or generate the frontend type from the server contract. The open gaps are SQL column selection, response-size/latency measurement, and runtime JSON-field validation.
- The architecture candidate report for this pass was generated at `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-165055.html` and queued in the Codex panel. It is a static report only; no source behavior, database data, AI/PDF provider, or browser account state was changed.

### Resume-document extensibility audit (2026-09-04)

- Static review found that the editor/backend document contract accepts more sections than the material-generation adapter preserves. `web/src/api/materialGeneration.ts` filters generated output through an eight-key `RESUME_SECTIONS` set, while the shared editor registry and `JsonResumeValidator` accept the broader document shape. A future or changed provider result containing a valid `objective`, `links`, `volunteering`, `courses`, `publications`, or `customSections` field can therefore be silently truncated before `createResume`.
- This is recorded as finding #53 in the ideation report. It is a P2 functional/extensibility risk rather than a claim that the current narrow prompt always fails: the current prompt deliberately requests a smaller subset, but the subset is not versioned or machine-checked against the rest of the document contract. No AI request or source behavior was changed.
- Recommended acceptance is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): run a legal multi-section fixture through provider result normalization, draft creation, editor, preview, PDF, and assets; either share a versioned document contract or return explicit dropped-section/rejection metadata, never silent loss. A fresh architecture report was generated at `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-165511.html` and queued in the Codex panel.

### Platform-interview context completeness audit (2026-09-04)

- Static review found that `InterviewContextSanitizer.sanitizePlatformResume` projects only `basics` label/summary plus `work`, `projects`, `education`, `skills`, `certificates`, and `languages`. Valid resume sections `objective`, `links`, `volunteering`, `courses`, `publications`, `awards`, and `customSections` are accepted by the editor/backend contract but are absent from the platform-resume interview context. The assembler has no second path that restores them.
- This is an open P2 context-completeness/extensibility risk, not a claim that every current interview is broken: the subset may be an intentional minimum-context policy, but that policy is not versioned, user-visible, or asserted by tests. Existing sanitizer coverage does not exercise the omitted sections, and assembler tests mock the sanitizer. No AI request or interview was started.
- Recommended acceptance is recorded as finding #54 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): decide whether interview context is full-resume or an explicit minimum subset; then add a shared safe projection, field/length/PII tests, and a legal all-section fixture through the local context seam. No source behavior or stored data was changed in this audit.

### Communication-AI resume projection audit (2026-09-04)

- Static review found that `CommunicationAiPromptBuilder` serializes only eight resume sections into the AI task input. The six other sections accepted by the shared editor/backend document contract (`objective`, `links`, `volunteering`, `courses`, `publications`, `customSections`) are silently omitted before the task is created.
- This is a separate P2 context-completeness risk from #54: it affects communication-draft generation at task-input construction time, not interview evaluation. Existing communication tests cover language and provider behavior but do not assert the section projection contract. No communication AI task or provider call was created.
- Recommended acceptance is recorded as finding #55 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): define a versioned, task-aware safe resume projection shared with the other AI capabilities, then test a legal all-section fixture, empty sections, length limits, and PII/link redaction. No source behavior or stored data was changed in this audit.

### AI-task capability-policy cross-check (2026-09-04)

- A second static scan confirmed that the consent-category mismatch already recorded in finding #10 also covers `RESUME_OPTIMIZE`, `INLINE_OPTIMIZE`, and `ACHIEVEMENT_GUIDANCE`; the latter two carry resume content but use the empty default category list in both task creation and worker execution. This is a refinement of #10, not a new finding.
- Targeted regression verification passed: `AiConsentServiceTest` 7, `AiTaskServiceTest` 11, `InlineOptimizeControllerIT` 8, and `TaskExecutionServiceTest` 5; 31 tests passed with 0 failures/errors. H2/Flyway applied V1–V24 during the integration test. These tests confirm existing normal-path behavior and do not close the partial-consent matrix or provider-payload privacy gap.

### Template contract and localization audit (2026-09-04)

- Static cross-check found the seven resume template codes aligned across `ResumeTemplateCodes.SUPPORTED`, `CreateExportRequest`, Web editor/export types, and PDF `TEMPLATE_CODES`; no new template-drift finding was opened. `ResumeVersionServiceTest` and `ExportServiceTest` passed, and the PDF service passed all 16 tests, including every supported template. No PDF export request was sent.
- Static review found a separate P2 UI localization issue: `ResumeDetailView`, `InterviewAssetsView`, `CompareVersionsView`, and `CommunicationView` translate section/scene label maps once during `script setup`. Because `useLocale` updates a shared reactive ref without remounting the route, in-page language switching can leave filter options and tags in the previous language while surrounding text updates. This is recorded as finding #56 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- The language-switch path was not browser-reproduced in this pass; acceptance requires switching `zh-CN`/`en-US` on all four pages without reload and asserting the static maps update. No source behavior, stored data, browser account state, AI/provider call, or PDF file was changed.
- Static contract review also found that `web/src/api/ai.ts` declares `EXPORT_PDF` in the AI task union while the backend `AiTaskType` does not; PDF uses the separate `export_task` flow. No frontend caller currently uses the stale value, so this is recorded as finding #57 rather than treated as a current runtime failure. Acceptance is to remove the stale literal or explicitly unify the two task domains with a complete status/result/recovery contract.

### Sixth-round async seam review and browser reproduction (2026-09-04)

- The shared useResumeJobOptions.loadVersions path has no request epoch/cancellation guard. ATS, communication, interview, and achievement-guidance pages all call it directly when the resume changes, while their selected resumeVersionId is not reset as one shared state. A slow response for resume A can therefore overwrite the final version list for resume B. This is recorded as finding #58; no artificial delay was injected.
- The inline resume-editor AI path waits only about 30 seconds while the provider read timeout is 300 seconds and the shared task-polling window is about 5 minutes. The inline path does not persist its task id for recovery after timeout. This is recorded as finding #59; no AI task or provider call was started.
- The generic AI worker lease is 180 seconds with a 60-second heartbeat cadence, shorter than the provider read timeout. If heartbeat renewal is lost, the current worker continues the provider call while another worker may reclaim the expired lease; the old result is only discarded at completion. This is recorded as finding #60; no failure injection or duplicate provider call was performed.
- Auth initialization sets initialized=true before refresh. A network failure then leaves the store in NETWORK state, permits protected routes, and provides no later retry because subsequent route guards skip initialization. This is recorded as finding #61; no network fault was injected.
- In a separate local H2 browser pass, a synthetic loopback account created a blank temporary resume. On /resumes/1, switching the in-page language from Chinese to English updated the title, buttons, and explanatory copy, but the Section filter menu options remained Chinese (个人信息, 工作经历, 自定义模块, etc.). This reproduces finding #56 as a real P2 UI localization defect rather than static-only evidence.
- Browser environment: API ran with local-h2 on 8081 and Vite on 5173; Flyway applied V1–V24. agent-browser remains unavailable, so the result is manual Codex in-app browser smoke rather than repeatable CLI E2E. No real AI/provider request, PDF render, upload, account deletion, or destructive action was performed. The temporary H2 account/data are process-local and credentials were not recorded.
- mvn -q -Dtest=ResumeVersionServiceTest,ExportServiceTest test, PDF service tests (16/16), Web npm run build, and git diff --check remain the latest green baseline. These checks do not close #58–#61 or the browser-reproduced #56 language-switch defect.

### Cross-runtime time contract audit (2026-09-04)

- Static review found that the backend uses timezone-less `LocalDateTime.now()`/`LocalDate.now().atStartOfDay()` for timestamps, follow-up filtering, and AI/interview daily quotas. `application.yml` configures JDBC handling for `Asia/Shanghai`, but the JSON contract does not carry an offset or user timezone. The Web then parses several values with `new Date(value)` and formats them in the browser's local timezone; `ApplicationsView` also combines browser-local overdue logic with server-local TODAY/OVERDUE filtering.
- This is an open P2 cross-runtime consistency and multi-region risk. A browser or deployment outside Asia/Shanghai can display an adjacent calendar date, disagree with the server about TODAY/OVERDUE, or observe a different daily quota reset boundary. The current Shanghai-local smoke does not validate other timezones or DST transitions.
- Recommended acceptance is recorded as finding #62 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): define an explicit instant/calendar-day contract, preferably use UTC `Instant` or offset timestamps for instants, inject `Clock`/`ZoneId` for business-day calculations, and test Asia/Shanghai, UTC, America/Los_Angeles, and a DST boundary across API serialization, TODAY/OVERDUE, quota reset, expiry, and Web display. No source behavior or stored data was changed in this documentation-only pass.

### Frontend async read-model audit (2026-09-04)

- Static review found three separate latest-response gaps. `CompareVersionsView` triggers `loadDiffs` both from selector change handlers and a watcher, and does not verify the selected version pair before committing returned JSON; `CommunicationView` has no request epoch for template list/preview requests and does not reload the language-filtered list when locale changes; `ResumeEditorView` and `ResumeDetailView` write section-filtered interview assets without a section snapshot or cancellation guard. These are recorded as findings #63–#65.
- The risks are P2 frontend consistency and extensibility issues: slow responses can show an old version diff, the wrong template preview/list, or assets belonging to a previous resume section. Normal single-request rendering and the current Web build do not close them. No artificial latency was injected and no AI/provider/PDF request was made.
- Recommended acceptance is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): use one loading entry per state change, request snapshots/epochs or cancellation, and controlled A/B/A interleaving tests for version pairs, template filters/languages/previews, and section assets. No source behavior or stored data was changed in this documentation-only pass.

### API error localization contract audit (2026-09-04)

- Static review found that server `ErrorCode` defaults and many `BusinessException` messages are Chinese strings. `GlobalExceptionHandler` returns those strings directly as `ApiResponse.message`; no locale key/parameter contract or `Accept-Language` handling was found. Several Web paths, including account credential changes and generation/confirmation failures, display `response.data.message` directly, while task pages display persisted `errorMessage`.
- This is recorded as finding #66. It is a P2 cross-runtime error-contract and multilingual-client risk: an English user can receive Chinese after a backend validation/conflict/failure, and a future client cannot reliably classify errors by natural-language text. This is distinct from #13, which covers AI failure text length, internal detail exposure, and persistence reliability.
- Recommended acceptance is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): return stable error code/message key/structured parameters/trace ID, render user text through the active Web locale, and test the same validation, authorization, conflict, rate-limit, not-found, and task-failure cases in Chinese and English. The in-app browser only verified the public home page in this pass; no account was created or backend error submitted, and no source behavior or stored data was changed.

### Career-material concurrency audit (2026-09-04)

- Static review found that `CareerMaterial` has no JPA `@Version`, `UpdateCareerMaterialRequest` carries no version/ETag, and `PATCH /api/career-materials/{id}` loads then saves the entity without a conditional update. The Web returns `updatedAt` in the detail model but does not send it back as a concurrency precondition.
- This is recorded as finding #67, an open P2 core-fact data-loss and collaboration/synchronization risk. Two tabs, devices, or a future import/sync worker can silently use last-write-wins for the same career material. Existing normal update and ownership tests do not prove concurrent behavior; no concurrent write was injected.
- Recommended acceptance is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): add a version or ETag/If-Match contract, return a stable 409 on stale updates, and test interleaved updates across all mutable fields plus soft-delete and historical source snapshots. No source behavior or stored data was changed in this documentation-only audit.

### Logging privacy-boundary audit (2026-09-04)

- Static review found `ExportService.create` logs `userId` in the debug message `Export task created: id={}, userId={}, versionId={}`. This conflicts with the project privacy boundary in `PROJECT_CONTEXT.md`, which prohibits user IDs in logs, metrics labels, test reports, or Git.
- This is recorded as finding #68, an open P2 privacy and observability-expansion risk. DEBUG visibility is not a sufficient safeguard because production log levels, temporary troubleshooting configuration, centralized log retention, and backups can expose the identifier. Task ID, version ID, and trace ID already provide lifecycle correlation.
- Recommended acceptance is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): remove `userId` from the log, add a static log/metric privacy gate, and use a test appender across PDF create/worker/expiry/retry paths to assert no user identifiers or sensitive payloads are emitted while task-level diagnosis remains possible. No source behavior, log files, or stored data was changed in this documentation-only pass.

### Job-description derived-data freshness audit (2026-09-04)

- Static review found that updating `jdText` does not invalidate `parsedKeywordsJson`, `parsedAt`, or `parsedVersion`. The Web marks a job as parsed whenever `parsedAt` is present. The parse endpoint then reads the current text and writes derived fields without a content hash/version condition, so an interleaved older parse can overwrite the result for newer text.
- This is recorded as finding #69, an open P2 derived-data consistency and parser-extensibility risk. Users can see old keywords marked as current after editing a JD, and future asynchronous parsing or rule-version rollout can propagate stale job profiles. Existing single-parse tests do not close this lifecycle gap; no interleaved update/parse was injected.
- Recommended acceptance is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): invalidate parse state on text changes, persist a content hash/source version, and condition parse writes on that version. Test edit-after-parse, repeated parse, interleaved edit/parse, parser-version changes, and late results. No source behavior or stored data was changed in this documentation-only audit.

### Personal-profile concurrency audit (2026-09-04)

- Static review found that `PersonalProfileService.upsert` uses a read-then-create path even though V13 enforces `UNIQUE (user_id)`. Concurrent first saves can therefore lose the insert race and fall through the generic 500 handler instead of converging to one idempotent profile result. The entity has no `@Version`, and `PersonalProfileRequest`/the Web payload has no version or ETag precondition, so concurrent edits to an existing profile remain silent last-write-wins.
- This is recorded as finding #70 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is distinct from #46 (user registration/email uniqueness), #67 (career-material body updates), and the previously recorded resume-version pointer race. No source behavior, account data, or stored profile was changed.
- Recommended acceptance is recorded with #70: define concurrent-upsert behavior, translate the unique-key race into a stable result, add a version/ETag conflict contract for existing profiles, and run real database interleaving tests covering contact fields and career preferences. The current unit/integration profile tests only cover serial behavior and do not close this gap.

### AI-consent event ordering audit (2026-09-04)

- Static review found that the append-only consent model resolves the current state with `findFirstByUserIdOrderByCreatedAtDesc`; `AiConsent.createdAt` is application-generated `LocalDateTime` persisted at `DATETIME(3)` precision, and the query has no stable ID tie-break, per-user sequence, or transaction-ordering guard. Concurrent grant/withdraw events written in the same millisecond can therefore have an undefined “latest” result.
- This is recorded as finding #71 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is a P1/P2 privacy-state consistency risk: `current` and `hasValidConsent` can disagree with the user’s completed withdrawal or grant under interleaving. Existing consent tests mock one latest row and cover serial events only; no AI/provider request or consent data was changed.
- Recommended acceptance is recorded with #71: define a strict per-user event order/linearization point, add a deterministic tie-break at minimum, and test concurrent grant/withdraw, retry, task creation interleaving, and cross-instance clock skew so a completed withdrawal cannot be bypassed by a stale grant.
- Verification baseline: `mvn -q "-Dtest=PersonalProfileServiceTest,PersonalProfileControllerIT,AiConsentServiceTest" test` passed; H2/Flyway applied V1–V24. `git diff --check` passed. These checks cover serial behavior and documentation formatting only; no real concurrent database interleaving was executed.

### Career-material search resource-boundary audit (2026-09-04)

- Static review found that the career-material search `q` parameter has no server-side size limit. The service only trims/escapes it, then the repository applies `lower(... LIKE '%q%')` to both the title and `MEDIUMTEXT sourceText`, with no full-text/indexed projection or query budget. Browser input/debounce behavior does not constrain direct API callers.
- This is recorded as finding #72 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is a P2 resource/scalability risk distinct from #11 pagination and #47 wide-row list reads; no large-data query or source behavior was changed.
- Recommended acceptance is recorded with #72: reject overlong search terms before SQL, define title/summary/full-text search semantics, add the matching search structure, and benchmark short/boundary/over-limit terms against realistic material counts and source-text sizes with `EXPLAIN` and concurrent latency measurements.

### Communication-template edit consistency audit (2026-09-04)

- Static review found that custom template updates load the entity and overwrite all editable fields without a JPA `@Version` or HTTP version/ETag precondition. `UpdateTemplateRequest`, template responses, and the Web `TemplatePayload` carry no concurrency token, so two tabs can silently apply last-write-wins to body text, language, scene, and placeholder usage.
- This is recorded as finding #73 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is distinct from #25 (shared `usage_count` lost updates) and #64 (stale list/preview responses); no template, source behavior, or stored data was changed.
- Recommended acceptance is recorded with #73: add a version/ETag conditional update contract, return stable `409 CONFLICT` for stale edits, and test interleaved body/language/scene changes plus update/delete races. Existing template tests cover serial ownership and protection only, not concurrent edits.

### Communication-text storage-boundary audit (2026-09-04)

- Static review found no server-side size constraint on `SaveTemplateRequest.bodyText`, `UpdateTemplateRequest.bodyText`, or `SaveDraftRequest.draftText`. The template and draft tables use MySQL `TEXT` columns (about 65535 bytes), but the services validate placeholders and then write directly; the Web template textarea also has no `maxlength`. A global JSON request limit does not define a per-field or UTF-8 byte contract.
- This is recorded as finding #74 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is a P2 input/resource and storage-contract risk: oversized or multibyte text can reach regex/JPA/database work and turn a user validation problem into a generic 500 or excessive response/memory cost. It is distinct from #44 (generic AI task input), #50 (wide list reads), and #73 (concurrent template edits). No oversized text was sent and no source behavior or stored data was changed.
- Recommended acceptance is recorded with #74: define character and UTF-8 byte budgets for templates and drafts, validate before persistence with a stable client/server contract, and test ASCII/CJK/emoji boundary cases for create, update, preview, and draft-save. Over-limit requests must not persist partial rows or increment template usage. Existing tests cover only short serial content.

### Interview-record ordering audit (2026-09-04)

- Static review found that `interview_record` has an explicit `round_no` and V18 backfilled it using `created_at, id`, but repository reads still order only by `createdAt`. State assembly chooses the last record as the recent evaluation, reports render the returned list as rounds, and the next AI prompt uses the same list for conversation history.
- This is recorded as finding #75 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). With `DATETIME(3)`, records created in the same millisecond have no guaranteed database order, so the latest evaluation, report order, and provider history can drift. This is distinct from #71, which concerns consent-event ordering. No same-timestamp interview rows or AI/provider request were created.
- Recommended acceptance is recorded with #75: order record reads by `roundNo ASC, id ASC` (or an equivalent explicit sequence), remove misleading order from aggregate-only projections, and test same-timestamp rule/AI records, late retries, report rendering, state refresh, and prompt history. No source behavior, interview data, or test fixture was changed in this documentation-only pass.

### Async response and derived-result idempotency audit (2026-09-04)

- Static review found a current Web/backend contract failure in achievement guidance. `POST /api/ai/achievement-guidance` returns `202 Accepted` with `AiTaskStatusResponse` and `taskType=ACHIEVEMENT_GUIDANCE`; `AchievementGuidanceView` instead reads `data.questions` immediately and calls `.map()`. A successful enqueue therefore becomes a generic front-end generation error, while the background result is not polled or recoverable from that page. This is recorded as finding #76.
- Static review also found that both inline optimize and achievement guidance pass a fresh server-side UUID to `AiTaskService.create` and expose no `Idempotency-Key` contract. A lost response, timeout, refresh, or second tab consequently creates a new task and can consume another quota unit/provider call instead of replaying the original task. This is recorded as finding #77, distinct from the existing generic same-key race and inline polling-window findings.
- The scoring endpoint has the same missing replay contract at a different layer: every `POST /api/scoring/match` unconditionally inserts a `match_result`, while the request has only resume/JD IDs and the table has no input fingerprint or revision semantics. Exact retries can create equivalent history rows, but naive reuse would be wrong after a JD or rule-version change. This is recorded as finding #78.
- Recommended acceptance is recorded in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md): align achievement guidance with the shared `202 + taskId -> poll -> resultJson` contract; accept normalized request idempotency keys for both AI entry points; and define scoring fingerprints, explicit recompute revisions, stable history ordering/pagination, and retention. No AI/provider request, task creation, scoring write, browser state, or source behavior was changed in this static pass.
- The existing `InlineOptimizeControllerIT`, scoring tests, targeted Maven regression suite, Web build, and `git diff --check` only establish normal/serial baselines. They do not close the browser-side async response mismatch, lost-response/concurrent replay, provider/quota duplication, rule-version evolution, or large-result retention cases.

### Extensibility capability-registry audit (2026-09-04)

- Static review found that resume template codes are repeated in the Web editor, Java export validation, the export request pattern, and the PDF renderer. The current seven codes match, but there is no cross-runtime manifest or machine check; this is recorded as finding #79 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is distinct from #37's locale/presentation drift and was not changed in this pass.
- Static review also found that communication types, template scenes, placeholder names, deterministic draft rules, AI subject validation, SQL seed rows, and Web unions/labels are maintained separately. This is recorded as finding #80. It is an extensibility risk distinct from #39's whitespace behavior, #73's concurrent template edits, and #74's text-size contract.
- The architecture candidate report is available at `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-181245.html`. It recommends the existing AI task capability module first, followed by template and communication capability registries.
- This was documentation-only: no business source, migration, test data, AI/provider request, PDF render, account, or browser state was changed. Existing normal-path tests and Web build do not prove cross-runtime registration consistency for a future template or communication type.

### Resume-import parser extensibility audit (2026-09-04)

- Static review found that `ResumeImportService` owns media validation, extension dispatch, TXT/PDF/DOCX parsing, text cleanup, and basic-field normalization in one implementation. This is recorded as finding #81 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- #81 is distinct from #17: #17 covers file expansion, output-size, page-count, and timeout budgets; #81 covers the missing parser adapter seam now that three real formats already exist. Adding ODT, HTML, or OCR would otherwise require editing the same entry implementation and its shared error path.
- The latest architecture candidate report is available at `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-181522.html`. It recommends a parser interface/registry with TXT, PDF, and DOCX adapters, while keeping normalization and shared resource/error semantics testable through the import module interface.
- This was documentation-only: no file was uploaded, no parser behavior or dependency was changed, and no AI/provider or PDF renderer request was made. Existing TXT/PDF/DOCX tests establish current behavior only; they do not prove that a future parser can be added without cross-path drift.

### Interview-mode strategy extensibility audit (2026-09-04)

- Static review found that the four persisted `InterviewMode` values are passed to the AI prompt but are not inputs to the rule fallback engine. `InterviewRuleEngine` uses one fixed first question, topic list, and scoring rubric for every mode; this is recorded as finding #82 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- #82 is distinct from #41 (fallback language), #42 (mode/language missing from restored state), and #75 (record ordering). It concerns the missing strategy seam: a new mode cannot declare its deterministic questions and rubric without editing shared rule implementation.
- The latest architecture candidate report is available at `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-181731.html`. It recommends a mode strategy module consumed by both AI prompt construction and rule fallback adapters.
- This was documentation-only: no interview session, record, AI attempt, provider request, source code, migration, or test data was changed. Existing `InterviewRuleEngineTest` proves the single generic rubric only; it does not prove mode-specific fallback behavior.

### Career-material type extensibility audit (2026-09-04)

- Static review found 13 backend `MaterialType` values but only three type-specific validation branches and three specialized Web form branches. Excerpt projection, AI-safe snapshot handling, and generated-material type inference maintain additional independent mappings; this is recorded as finding #83 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- #83 is distinct from #67 (concurrent material edits), #47 (wide-row list reads), and #72 (search resource bounds). It concerns the missing capability seam that would let a new material type remain coherent across CRUD, search, AI selection/generation, confirmation, and the Web form.
- The latest architecture candidate report is available at `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-182006.html`. It recommends a career-material capability module with schema/form metadata, relation rules, excerpt projection, AI-safe projection, and generated-material mapping.
- This was documentation-only: no career material, source code, migration, AI/provider request, PDF render, account, or browser state was changed. Existing tests cover current types and do not prove a synthetic future type remains coherent across every consumer.

### JD / ATS runtime-contract audit (2026-09-04)

- Static review found a direct parse/read mismatch: `JobDescriptionService.parse` persists `parsedKeywordsJson.data.keywords`, while `ScoringService.score` only reuses top-level `parsedKeywordsJson.keywords`; a successful parse therefore falls through to a fresh parser call during scoring. This is recorded as finding #84 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md), distinct from #69's stale-result-after-edit problem.
- Static review also found that ATS task input records prompt/schema versions at creation, while `AtsAiPromptBuilder` uses the worker's current configuration and the result copies the older input versions. This is recorded as finding #85, an open P2 queued-task provenance/replay risk; no deployment rollover or provider call was performed.
- ATS insight fields and enum/size rules are repeated across prompt prose, Java validator/DTO, Web types, and E2E fixtures. This is recorded as finding #86, a P2/P3 cross-runtime schema drift risk, not a claim that the current happy-path schema is invalid.
- The architecture candidate report is available at `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-182447.html`; it recommends fixing the parse/read contract first, then task-pinned ATS policy and a versioned insight contract.
- This pass was documentation-only: no JD, score, ATS result, AI task, account, browser state, or test data was changed; no AI/provider or PDF renderer request was sent. `git diff --check` remains the formatting gate; the recommended parse→score, cross-version worker, and schema contract tests have not yet been implemented.

### Interview-feedback contract extensibility audit (2026-09-04)

- Static review found that the five interview score dimensions, feedback fields, and collection constraints are repeated across the AI prompt, Java DTO/Bean Validation, AI-to-Map conversion, rule fallback Map, report aggregation, state assembly, Web types/views, asset JSON, and E2E fixtures. This is recorded as finding #87 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- This is a P2/P3 cross-runtime contract and extensibility risk: a future feedback field or scoring dimension can be accepted by one path but rejected, omitted from rule fallback/report aggregation, or persisted without being rendered by another. It is distinct from #82 (mode strategy), #54/#55 (resume section context projection), and #86 (ATS insight schema); no current five-dimension happy-path failure is claimed.
- Recommended acceptance is recorded with #87: define a versioned `InterviewFeedback` interface/adapter for dimension metadata, limits, fields, and compatibility; make AI validation, rule fallback, persistence mapping, reports, and Web contract tests consume it; add synthetic new-field/dimension, old-record replay, mixed AI/RULE report, asset round-trip, and Web fixture coverage. Existing interview tests, Web build, and browser smoke cover current serial behavior only and do not close this cross-path consistency gap. No interview data, source behavior, AI/provider request, PDF render, account, or browser state was changed.

### Interview-question contract extensibility audit (2026-09-04)

- Static review found that `InitialQuestion`, `NextQuestion`, and follow-up `Candidate` repeat the same `question`/`focus`/`expectedSignals`/`coverageTags` fields and constraints across Java DTOs, AI prompt prose, async result mapping, and Web types. This is recorded as finding #88 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- This is a P2/P3 interview-question contract and extensibility risk distinct from #87's feedback/score schema, #82's mode strategy, #42's restored mode/language state, and #76's achievement-guidance response mismatch. A future question field can be accepted in one generation path but rejected or omitted in another; current four-field happy paths are not claimed broken.
- Recommended acceptance is recorded with #88: define a versioned `InterviewQuestion` interface/adapter used by initial question, next question, follow-up Prompt/validator/result and Web contracts; add optional-field, old-result replay, all three generation paths, async polling, and Web editing fixtures. Existing tests cover current fields and follow-up task creation, not the full provider-result-to-Web contract. No interview data, source behavior, AI/provider request, PDF render, account, or browser state was changed.

### Interview-task operation seam audit (2026-09-04)

- Static review found that `INTERVIEW_COACH` is overloaded for interview-related task operations and follow-up is distinguished only by the string `input.operation = FOLLOW_UP_PRACTICE`. `TaskExecutionService` routes that one recognized string to `InterviewFollowUpAiService`; all other operations fall through to generic provider execution and raw result persistence. This is recorded as finding #89 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- This is a P2/P3 worker routing and extensibility risk distinct from #10 (consent/data-category policy), #76/#77 (async response and idempotency), #82 (interview-mode strategy), and #88 (question object schema). A future operation can be marked successful without its dedicated validator/formatter, while the Web has no stable result contract.
- Recommended acceptance is recorded with #89: introduce a versioned interview operation registry/adapter that declares input, consent/quota categories, Prompt/version, result validator/formatter, and Web contract; reject unknown operations before provider calls. Existing follow-up creation and normal worker tests do not close unknown-operation, second-operation, retry, lease takeover, or old-task replay cases. No task, source behavior, AI/provider request, PDF render, account, or browser state was changed.

### Personal-profile import contract audit (2026-09-04)

- Static review found that `PersonalProfileService.importSuggestion` reads only `resumeJson.basics` and returns all five career-target fields as `null`, even though the resume JSON allow-list and editor support an `objective` section and the profile/AI context treat career targets as first-class data. The Web import handler replaces the in-memory profile with this basics-only suggestion, so unsaved target fields can also be overwritten by empty values.
- This is recorded as finding #90 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is a P2/P3 import-contract and extensibility risk, not an unconditional backend defect: if product intent is “basic identity only,” the API/UI scope and merge behavior should say so; otherwise the mapper should consume `objective`.
- Recommended acceptance is recorded with #90: define a complete-vs-basic import contract, map `objective` fields through a versioned mapper when complete import is intended, preserve existing profile values under the chosen merge policy, and test `basics + objective`, missing objective, legacy JSON, repeated import, and unsaved-field behavior. No source behavior, account, upload, AI/provider request, PDF render, or browser state was changed.
- Verification baseline: `mvn -q "-Dtest=PersonalProfileServiceTest,PersonalProfileControllerIT" test` passed (8 tests; Flyway V1–V24 on H2). `git diff --check` passed; its output only contains the existing LF/CRLF normalization warnings. These checks cover current basics-only serial behavior, not the missing `objective` mapping or merge semantics.

### Scoring-rule capability audit (2026-09-04)

- Static review found that `RuleRegistry` is a fixed three-field holder, while `ScoringService`, `MatchResult`, V1 DDL, Java DTOs, Web types, and `MatchResultView` each repeat `keyword/skill/experience` scoring fields. Adding a new rule therefore requires coordinated edits across registration, aggregation, persistence, API, and presentation; a partial edit can silently omit the rule from the total or result.
- This is recorded as finding #91 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is a P2/P3 scoring capability and cross-runtime contract risk, distinct from #78's replay/history semantics and #84's parsed-JD read mismatch; the current three-rule path is not claimed broken.
- Recommended acceptance is recorded with #91: define a versioned scoring-rule adapter/registry with weight, enablement, score, and evidence output; aggregate dynamically while preserving a compatibility projection for existing fields; test a synthetic rule, disabled/zero-weight rules, invalid weight totals, legacy results, and Web rendering. No score, source code, migration, AI/provider request, PDF render, account, or browser state was changed.
- Verification baseline: `mvn -q "-Dtest=ScoringServiceTest" test` passed. This covers the current three-rule serial path only; no synthetic rule or cross-runtime contract fixture exists yet.

### Cross-service health capability audit (2026-09-04)

- Static review found that the API and PDF `/health` responses each hard-code their own capability list/version and always return `UP`; the Web health type accepts only `UP`, and `HomeView` treats any successful response as “service online.” No dependency-aware `DOWN/DEGRADED` or registry-backed capability contract is present.
- This is recorded as finding #92 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is a P2/P3 cross-service capability-discovery and readiness-semantics risk, distinct from #15, #18, #20, and #79; it does not claim the current process-liveness endpoint is unavailable.
- Recommended acceptance is recorded with #92: define versioned capability descriptors and separate liveness/readiness states, derive API/PDF/provider capability responses from registries, and test added/disabled capabilities plus missing AI key, unavailable PDF renderer, database failure, degraded dependency, and old-client compatibility. No dependency was stopped and no source, account, browser, AI/provider, PDF, or database state was changed.

### AI-task type registration seam audit (2026-09-04)

- Static review found that adding an `AiTaskType` value does not force a complete capability registration. Consent categories, quotas, Prompt branches, worker dispatch, generic-controller restrictions, and quota gauges are maintained separately; several use safe-looking defaults (`empty categories`, quota `30`, generic Prompt, generic worker execution). This is recorded as finding #93 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- If a future task type is added to the enum but missed in one registration point, `POST /api/ai/tasks` can accept it and the worker can mark a raw provider result successful without a task-specific schema or recovery contract. The missing type also has no configured quota gauge. This is a P2/P3 fail-open extensibility risk, distinct from #10's existing task-category mismatch and #89's `INTERVIEW_COACH` operation routing.
- Recommended acceptance is recorded with #93: introduce a `TaskCapabilityRegistry`/adapter seam covering input policy, consent categories, quota, Prompt/schema version, execution/result formatting, retry/fallback, observability, and recovery; reject unknown or incomplete types before provider calls. Add a synthetic future task contract fixture, missing-registration cases, old-task replay, retry, and cross-page recovery tests. No enum, task, source behavior, database state, account, browser state, AI/provider request, or PDF render was changed.
- The audit also checked parser/scoring rule-version sources. The parser configuration and fixed current constant, plus derived-result freshness/read semantics, overlap with #69, #84, and #91, so no duplicate finding was created. The architecture candidate report is available at `C:\Users\lby0403\AppData\Local\Temp\architecture-review-20260904-185325.html`.
- Verification baseline: `mvn -q "-Dtest=AiQuotaServiceTest,TaskExecutionServiceTest,BailianAiProviderTest" test` passed. This covers the current registered task types and provider/worker normal paths; it does not prove the missing-registration rejection, synthetic future type, quota-gauge, or cross-runtime capability contract cases.

### Resume-version source contract audit (2026-09-04)

- Static review found that the five `ResumeSourceType` values are repeated in the Java enum, Web union, detail/compare mappings, interview mapping, multiple version selectors, and locale trees. ATS, application, communication, and achievement-guidance selectors render the raw `sourceType`, while detail/compare pass an unmapped value into `t()` without a fallback. This is recorded as finding #94 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- A future backend source value can therefore appear as an untranslated enum in one page and cause the detail/compare mapping to call `t(undefined)` in another; `t()` expects a string and calls `key.split()`. The issue is an extensibility and old-client compatibility risk, distinct from #56's stale in-page locale maps and #79's template registration.
- Recommended acceptance is recorded with #94: centralize source metadata and fallback in a `ResumeSourceDescriptor` module, make all version selectors consume it, and test the five current values, a synthetic new value, old-client replay, both locales, and all consuming pages. No source enum, version row, source data, account, browser state, AI/provider request, PDF render, or database state was changed.

### Interview-answer asset association audit (2026-09-04)

- Static review found that material-only interview assets are persisted with `section_key = ""` as a database `NOT NULL` workaround. The API then returns the empty string in `sectionKeys`; `InterviewAssetsView` renders it as a blank section tag, while `ResumeDetailView` can only filter by the 14 legal section keys. This is recorded as finding #95 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- Recommended acceptance is recorded with #95: model “no resume section + one or more materials” explicitly, never use an empty-string section sentinel, preserve `materialIds`, and add material-only create/update/list round-trip, legacy blank-row migration, section-filter, material-name display, and idempotency fixtures. No business source, migration, or test data was changed.

### Review remediation pass: async guidance, parsed JD reuse, and draft array decisions (2026-09-04)

- 修复成就引导 `202` 响应被前端当作同步结果的问题：创建接口现在按 `AiTask` 处理，页面等待任务成功后从 `resultJson.questions` 渲染问题；失败和超时进入统一错误路径。
- 修复评分无法读取正式 JD 解析结果的问题：评分同时支持历史顶层结构和 `data` envelope，并新增 envelope 复用测试，避免在已有解析结果时重复解析原文。
- 修复草稿确认数组索引漂移：标准化器在原始树上递归应用路径决策，拒绝数组前项不会让后项 EDIT/ACCEPT 指向错误条目。
- 修复 JD 原文修改后的过期解析状态：原文变化时清除旧的解析 JSON、解析时间和解析版本；新增服务层回归测试。解析请求与编辑请求的并发晚到写入仍是开放的版本条件问题。
- 验证通过：
  - `Set-Location server; mvn -q "-Dtest=ResumeJsonNormalizerTest,ScoringServiceTest,DraftCommitServiceTest" test`
  - `Set-Location web; npm run build`
- 本轮未发送真实 AI/provider 请求、未调用 PDF renderer、未创建或删除账号、未写入业务数据库数据。成就引导仍需后续用可控 mock provider 做浏览器级失败/超时/刷新恢复回归；入口幂等键问题 #77 与并发类问题仍开放。

### Built-in browser smoke follow-up (2026-09-04)

- The local Vite server was reachable at `http://localhost:5173`. With the explicitly permitted temporary account `codexqa20260904`, the built-in browser loaded the career-materials, jobs, resumes, generate, ATS, applications, interviews, communications, interview-assets, and account routes; the expected headings, empty states, forms, selectors, and primary buttons were present. No page-level error state appeared.
- Switching the interview-assets page to English changed the navigation, heading, and surrounding copy, but the section-filter options and related-section checkbox labels remained Chinese. This reproduces the already-recorded #56 stale in-page localization-map finding; it is not counted as a new finding.
- Browser coverage was smoke-only: no career material, job, resume, interview asset, interview record, AI task, file upload, provider call, or PDF export was created. `agent-browser` is not installed in this environment, so the built-in browser was used as a documented fallback. The temporary account remains local test data and was not used to send external requests.

### Web-navigation registration audit (2026-09-04)

- Static review found that router paths/meta, desktop/mobile navigation groups, the home four-step workflow, and `navGroups` locale entries are maintained separately. Dynamic navigation translation calls are not collected by the current static i18n-key checker, and the translation helper returns a raw key when a dynamic entry is missing. This is recorded as finding #96 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- Recommended acceptance is recorded with #96: introduce a typed route/navigation descriptor or registry, validate every navigation target and dynamic locale key, and test a synthetic new page, deep links, desktop/mobile navigation, and both locales. No router, navigation, i18n, or test source was changed.

### PDF-export status contract audit (2026-09-04)

- Static review found that the backend export response exposes `status` as an unconstrained string while the Web handles only five literal statuses. Unknown status values can reach `t(undefined)` and are not guaranteed to keep polling or expose a safe retry/refresh path. This is recorded as finding #97 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- Recommended acceptance is recorded with #97: define a versioned export-status descriptor/state contract, provide safe unknown-status fallback and polling semantics, and test current states, a synthetic future state, old-task replay, unknown terminal state, retry, and download behavior. No export task, renderer call, or source code was changed.

### Interview-source adapter audit (2026-09-04)

- Static review found that interview source semantics are repeated across enum validation, resume ownership lookup, AI-context sanitization, session persistence, start idempotency fingerprints, state restoration, and the Web setup/practice payloads. This is recorded as finding #98 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- The risk includes a concrete fail-open case: source validation treats every value other than `PLATFORM_RESUME` as external text, while session persistence stores external text only for the exact `EXTERNAL_RESUME` value. A future source enum can therefore pass validation and create a session whose AI context is `[empty resume]` unless every branch is updated together. This is distinct from #54/#55 (resume-section projection), #82 (interview-mode fallback strategy), #87/#88 (feedback/question schemas), #89 (task operation routing), and #94 (resume-version source display).
- Recommended acceptance is recorded with #98: introduce a versioned source descriptor/adapter contract covering input, ownership, persistence, sanitization, fingerprinting, restore/practice reuse, and Web metadata; reject unregistered sources before persistence/provider work; add current-source, synthetic-source, round-trip, stale/replay, ownership, and bilingual form fixtures. `mvn -q "-Dtest=InterviewPromptContextAssemblerTest,InterviewStartServiceTest" test` passed; no interview session, AI task, provider request, upload, or source behavior was changed in this documentation-only pass.

### AI-task confirmation lifecycle audit (2026-09-04)

- Static review found that the backend declares `ConfirmationStatus.NOT_REQUIRED` but also uses nullable `confirmationStatus` as the implicit “no confirmation” value. The Web `AiTask` type excludes `NOT_REQUIRED`, `useAiTaskStore.needsRecovery` treats every successful state except `CONFIRMED`/`REJECTED` as recoverable, and `GenerationConfirmView` only parses successful results with `PENDING`. This is recorded as finding #99 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md).
- The current task paths do not visibly return `NOT_REQUIRED`, so this is not claimed as a present-page failure. It is an extensibility risk: a new task that uses the existing enum for an explicitly non-confirmable result could be retained in local recovery while lacking a confirmation route, or could be excluded from the TypeScript contract. This is distinct from #93's missing task-type registration seam and #57's PDF/AI task-model mismatch.
- Recommended acceptance is recorded with #99: version the result/confirmation descriptor, define `requiresConfirmation` and a safe recovery route, normalize legacy `null`, and test all four statuses plus old tasks, no-confirmation success, confirmation-required drafts, failure retry, refresh, and cross-page recovery. No task, provider request, database state, or source behavior was changed in this static pass.

### Consent-withdrawal and browser closure recheck (2026-09-04)

- Static review rechecked `AiConsentService`, `AiTaskService`, and `TaskExecutionService`: a queued AI task revalidates scoped consent immediately before provider execution, so a withdrawn consent prevents that task from making a provider call. Cancellation/cleanup of already queued tasks and deletion-time worker fencing remain covered by existing findings #10, #26, and #71; no independent new finding was added.
- Built-in browser verification used the local Vite app at `http://localhost:5173`: temporary account `codexqa20260904b` registered successfully and reached the career-materials page; the AI consent page completed grant → `Authorized` (provider `bailian`, 9 scopes) → withdraw → `Withdrawn` without a page-level error. No career material, job, resume, interview record, AI task, PDF export, provider request, or upload was created.
- This browser check confirms the current authenticated consent happy path only. It does not close the synthetic future-task, consent-scope, async cancellation, or cross-runtime status fixtures tracked by #10/#26/#71/#99. The temporary account remains local test data; no password is recorded here.

### Resume-version pointer concurrency audit (2026-09-04)

- Static review confirmed that `ResumeService.setCurrentVersion`, `ResumeVersionService.archive`, and `restore` read/check/write the current pointer and archive marker through separate entity operations. `Resume` and `ResumeVersion` have no optimistic-lock field, and V1 only constrains `(resume_id, version_no)`; it does not enforce that `resume.current_version_id` references a non-archived version.
- This is recorded as finding #100 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). A switch/archive interleaving can therefore commit `current_version_id` to a version that the other transaction archived. This is distinct from #32 (downstream consumers using archived versions), #67/#70/#73 (snapshot/restore behavior), and #99 (AI confirmation lifecycle).
- Baseline: `mvn -q "-Dtest=ResumeServiceTest,ResumeVersionServiceTest" test` passed. These Mockito tests cover serial success/conflict paths and the version-number unique-constraint fallback, but not real-database interleavings, first-version creation races, or the invariant that the current pointer always targets an unarchived version. No business source, migration, test data, account, browser state, AI/provider request, or PDF renderer was changed.

### Review remediation: stable idempotency for inline AI entry points (2026-09-04)

- Fixed #77: `/api/ai/inline-optimize` and `/api/ai/achievement-guidance` now require a client-provided `Idempotency-Key` (1–128 characters) and pass it through to `AiTaskService`; the server no longer creates a random key that changes on retry.
- `web/src/api/ai.ts` sends the header. `ResumeEditorView` generates one key per opened AI assistant operation, while `AchievementGuidanceView` reuses a key for an identical request payload so an auth-refresh/network retry can resolve the existing task instead of creating another one.
- Added controller integration coverage for missing keys and same-key/same-payload task reuse, plus updated resource/consent fixtures to provide keys.
- Verification passed: `Set-Location server; mvn -q "-Dtest=InlineOptimizeControllerIT" test` and `Set-Location web; npm run build` (i18n guard, `vue-tsc`, Vite). No real AI/provider call was made.
- At this point #69 parse/edit late-write concurrency has been remediated below; #100 resume current-version pointer concurrency is remediated in the following pass. The remaining static extensibility findings remain open.

### Review remediation: reject late JD parse results (2026-09-04)

- Completed the remaining #69 lifecycle guard: `JobDescriptionService.parse` captures the source text before parsing, refreshes and locks the JD row with `PESSIMISTIC_WRITE` after parsing, then compares the current text before saving. An edit that completed during parsing now produces `CONFLICT` and the stale parsed snapshot is not written; the lock also closes the check-to-save race.
- Added `JobDescriptionServiceTest.parse_discardsLateResultWhenTextChanged`.
- Verification: the focused service test is included in `mvn -q "-Dtest=JobDescriptionServiceTest" test`; no provider request or business data was created.
- #100 is addressed in the following remediation entry; the remaining static extensibility findings remain open.

### Review remediation: serialize resume current-version transitions (2026-09-04)

- Completed #100's core state invariant: `ResumeRepository.findByIdAndUserIdForUpdate` locks the owned resume row with `PESSIMISTIC_WRITE`. Saving versions, switching the current version, restoring, archiving, and unarchiving now acquire that lock before checking or changing pointer/archive state.
- This makes switch/archive interleavings re-read the committed pointer and prevents a committed current pointer from targeting a version archived by a competing transaction. First-version creation and cross-service transactional version creation use the same lock.
- Existing service tests remain compatible and cover the serial state transitions; a real-database concurrent fixture is still a recommended follow-up for lock timeout/deadlock behavior.

### Full-suite validation boundary (2026-09-04)

- `mvn -q test` was intentionally stopped after the existing provider-facing integration tests issued real Bailian requests and received 4xx/401 responses. This is a test-environment safety issue, not a regression in the remediation: targeted tests for the changed paths had already passed, and no further full-suite execution should be used without a provider mock/disabled-provider profile.

### Draft confirmation array-path audit (2026-09-04)

- Static review found that `DraftCommitService` validates decision paths against the original draft, while `ResumeJsonNormalizer` removes all rejected list entries before applying edits and accepted paths. Because later decisions still use original array indexes, rejecting `work[0]` can make an edit for `work[1]` silently no-op; a pending item can also move to `work[0]` and be reported as undecided.
- This is recorded as finding #101 in [`docs/ideation/2026-09-04-project-optimization-ideation.md`](./ideation/2026-09-04-project-optimization-ideation.md). It is a confirmation data-integrity risk distinct from #53 (section allow-list loss), #83 (material-type registration), #90 (profile import merge), and #99 (AI-task confirmation lifecycle). The existing UI sends every draft item’s original path, so this combination is reachable when multiple entries in one array receive different decisions.
- Baseline: `mvn -q "-Dtest=ResumeJsonNormalizerTest,DraftCommitServiceTest" test` passed. Current tests cover single-path normalization and a mocked commit, not mixed reject/edit/accept decisions within one array. No AI task, provider request, resume/material data, source code, test data, account, browser state, or PDF renderer was changed.

### Review remediation: unify AI task consent categories (2026-09-04)

- Fixed the data-category part of finding #10. `AiTaskConsentPolicy` is now the shared policy used by task creation, retry, worker execution, and interview-domain consent checks. `MATERIAL_IMPORT` requires `CAREER_MATERIAL`; resume optimization and inline/achievement flows require `RESUME`; `INTERVIEW_COACH` requires `RESUME` and `INTERVIEW_ANSWER`; a supplied JD ID/text/context adds `JOB_DESCRIPTION`.
- JD detection reads both the task snapshot and its nested `input`, so the inline controller's nested `jobDescriptionId` is checked consistently before persistence and again before a provider call. Partial consent now rejects the task before the raw input snapshot is saved.
- Added regression coverage for the category matrix, nested JD handling, pre-persistence denial, worker enforcement, retry, and interview consent. Updated integration fixtures to use the server's uppercase category contract.
- Verification passed: `mvn -q "-Dtest=AiTaskServiceTest,TaskExecutionServiceTest,InterviewOperationSupportTest,InlineOptimizeControllerIT,AiTaskControllerIT" test` (62 tests, 0 failures/errors). No real AI/provider request was made; the existing full-suite provider limitation remains documented above.
- This closes the #10 category-mapping mismatch. Task inbox/recovery coverage remains separate lifecycle work; account-deletion cleanup is covered by the following #34 remediation entry.

### Review remediation: account-deletion async fencing (2026-09-04)

- Account deletion now locks and disables the user, appends a `WITHDRAWN` AI-consent event when the latest event is `GRANTED`, cancels active AI tasks, marks active PDF tasks as terminal `FAILED`, and then revokes refresh sessions.
- The PDF worker checks the owning user immediately before loading/rendering a resume, so a task for a disabled/deleted account is failed without a renderer call. Existing AI worker consent revalidation now observes the deletion withdrawal, while canceled task rows reject stale lease completion.
- Added regression coverage for idempotent consent withdrawal, account-deletion side effects, disabled-account PDF fencing, and existing access-token invalidation. Verification passed: `mvn -q "-Dtest=AuthServiceTest,AiConsentServiceTest,ExportTaskWorkerTest,AuthControllerIT" test` (28 tests, 0 failures/errors).
- No provider or PDF renderer request was made; the remaining narrow race/file-cleanup semantics for an already-running render are separate lease/resource-lifecycle work.

### Review remediation: PII snapshots, interview context, reactive labels, and material-only assets (2026-09-04)

- Fixed #35: `CareerMaterialAiSnapshotSanitizer` now recursively removes contact details, addresses, URLs, and other sensitive fields from source text, titles, and nested `contentJson` before AI snapshots are persisted or sent to a provider. Achievement metrics retain their existing display behavior, and regression tests cover nested payloads.
- Fixed #42: interview state responses now carry the session's original `interviewMode` and `outputLanguage`. Follow-up practice sessions restore those values from the session snapshot instead of using the current page locale or a default mode.
- Fixed #56: the static label maps in resume detail, interview assets, compare versions, and communication views are now locale-aware computed values, so in-page language switching refreshes section labels, filters, and related copy.
- Fixed #95: material-only interview assets now represent “no resume section” with a nullable `section_key`; migration V25 cleans legacy empty-string rows, service responses filter empty keys, and the affected pages show associated material names.
- Verification passed:
  - `Set-Location server; mvn -q "-Dtest=CareerMaterialAiSnapshotSanitizerTest,InterviewStateAssemblerTest,InterviewAssetServiceTest,InterviewAssetControllerIT,InterviewControllerIT" test`
  - `Set-Location web; npm run build`
  - `git diff --check`
- The focused server suite passed, including Flyway V25 on H2. `InterviewControllerIT` did reach the provider-facing path and logged the known 4xx response because no Bailian key is configured; no valid AI result was produced. No PDF render or business-data mutation was performed. Do not use this test selection when external provider calls must be fully disabled.
- Browser recheck remains bounded by the environment: `agent-browser` is not installed and a fresh attempt to reach `http://localhost:5173` returned `ERR_CONNECTION_REFUSED`. The code/build checks above are the current evidence for #35/#42/#56/#95; no new browser acceptance claim is made for this pass.

### Review remediation: task confirmation, export status, profile import, and interview source guards (2026-09-04)

- Fixed #99: the AI task status response now normalizes historical `confirmation_status = null` to `NOT_REQUIRED`; the Web contract includes all four confirmation states and only retains `SUCCESS + PENDING` in local recovery. The generation confirmation page no longer renders an empty review surface for already handled or non-confirmable tasks.
- Fixed #97: Web export status handling now normalizes unknown runtime values to `UNKNOWN`, uses a stable translated fallback, avoids treating them as downloadable or automatically pending, and exposes a manual status refresh action.
- Fixed #90: personal-profile import now maps the resume `objective` object into target roles, seniority, industries, location preferences, and career-positioning summary while retaining the existing `basics` mapping.
- Fixed #98's concrete fail-open seam: interview source validation now accepts only the two registered source branches; a null or future unregistered source cannot fall through to external-resume validation. Context projection also requires an explicit external source before using external text.
- Verification passed without invoking AI provider-facing integration tests:
  - `Set-Location server; mvn -q "-Dtest=AiTaskServiceTest,ExportServiceTest" test`
  - `Set-Location server; mvn -q "-Dtest=InterviewPromptContextAssemblerTest,PersonalProfileServiceTest" test`
  - `Set-Location web; npm run build`
  - `git diff --check`
- These changes close the concrete current/legacy-contract cases above; synthetic future capability registries, complete navigation registration (#96), and broader health/capability architecture (#92) remain separate open review work.

### Review remediation: source display, navigation, task capability, and health contracts (2026-09-04)

- Fixed #94: `web/src/utils/resumeSource.ts` is now the shared source-to-label fallback for resume detail, comparison, interview, ATS, applications, communication, and achievement flows. Unknown backend values remain readable instead of reaching `t(undefined)` or being rendered as raw enum text.
- Fixed #93's fail-open registration seam: `AiTaskCapabilityRegistry` explicitly describes every current `AiTaskType`, its consent categories, execution mode, and generic-entry policy. Task creation, retry, worker dispatch, quota checks, and Prompt templates fail closed for an unregistered type; adding an enum without a descriptor now fails the registry completeness check.
- Fixed #96's navigation drift seam: desktop/mobile groups and the home workflow now consume `web/src/navigation/registry.ts`. Router startup validates every registered target, and the i18n guard validates the registry's dynamic label keys in both locales.
- Fixed #92's health false-positive seam: API health now reports `UP`/`DEGRADED` with per-capability checks for the AI provider and PDF renderer; provider routing ignores unavailable providers. PDF `/health` probes Chromium readiness and reports renderer degradation instead of always returning `UP`. Existing string capability codes remain for old clients.
- Verification passed:
  - `Set-Location server; mvn -q "-Dtest=AiTaskCapabilityRegistryTest,AiTaskServiceTest,AiQuotaServiceTest,TaskExecutionServiceTest,SystemControllerTest" test`
  - `Set-Location server; mvn -q "-DskipTests" compile`
  - `Set-Location web; npm run build` (i18n guard, `vue-tsc`, Vite)
  - `Set-Location pdf-service; npm test` (18/18)
- Built-in browser smoke used a fresh local Vite tab at `http://127.0.0.1:5173/`: the app booted with the new router/navigation registry, English desktop navigation rendered, and all four workflow links were visible. The API/PDF backend was intentionally not started, so the footer correctly showed the expected offline state; this is not backend health acceptance. No AI/provider request or business-data mutation was performed.

### Review remediation: scoring weight and aggregation guard (2026-09-04)

- Closed the concrete #91 arithmetic gap: `RuleRegistry` now owns the named weight map, validates every weight is within `[0, 1]` and that the total is exactly `1.0`, and aggregates only when the score map contains exactly the registered rule set.
- `ScoringService` now uses the registry aggregation path and stores the named `ruleScores` in the explanation snapshot while keeping the existing three score fields and Web DTO unchanged.
- Verification passed: `Set-Location server; mvn -q "-Dtest=RuleRegistryTest,ScoringServiceTest" test`.
- The broader future adapter/descriptor surface for adding entirely new scoring dimensions remains a follow-up; the current three-rule path now fails closed on weight drift and omitted rule scores.
