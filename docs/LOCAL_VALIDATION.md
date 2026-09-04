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

### Reproduced defects

| ID | Route and reproduction | Observed result | Current evidence / likely contract gap |
| --- | --- | --- | --- |
| BR-001 | Open `/match/42` or `/match/43` for an existing valid match result. | The page remains in the loading state and the console reports `TypeError: Cannot read properties of undefined (reading 'matched')` at `web/src/views/MatchResultView.vue:212`. | `ScoringController.getResult()` returns the `MatchResult` entity, whose persisted field is `explanationJson`; the view reads `store.current.explanation`. Invalid IDs show the intended friendly alert, so the failure is specific to the valid-result response contract. |
| BR-002 | Open `/communications`, choose `模板库`, select `感谢` in `场景筛选`. | The list continues to show all templates (9 items). Clicking `刷新` then reduces it to the single matching template. | `web/src/views/CommunicationView.vue:503` binds the select with `v-model` but does not reload templates on change; `loadTemplates()` is invoked on mount and form submit. |
| BR-003 | Open `/communications` → `模板库`, edit `本地测试求职信模板`, and save without changing the name. | The dialog stays open and shows `模板包含非法占位符，仅允许白名单字段。`. | `openEditTemplate()` loads only the template name/scene. `saveTemplateDialog()` sends `previewDraft` or `draft` as `bodyText`, but the edit flow has not loaded the existing template body. See `web/src/views/CommunicationView.vue:336-363`. |

### Additional verified behavior

- Direct ATS rule checking succeeds and returns a persisted local report (`result=4`, score 80); missing version/job selection shows a validation alert.
- Template-generated cover letter, email body, and opening message work. `用于投递` carries the opening message into the new application form without sending anything externally.
- Application status changes from `APPLIED` to `INTERVIEWING` update the counters, lanes, home next-action card, and remain after refresh. The UI intentionally exposes forward-only transitions, so the synthetic test record remains in `INTERVIEWING`.
- The route-load error retry returns to the workbench. Resume import opens a single-file chooser, but no file was uploaded.
- Invalid resume detail/edit/compare routes and an invalid match ID render friendly alerts without console errors; valid match IDs remain covered by BR-001.
- Existing export task `#49` displays `PDF 已准备好`. A new export task (`#50`) first displayed `PDF 渲染失败`, then transitioned to `PDF 已准备好` about four seconds after clicking `重试导出`; the retry path is therefore verified. The page shows no error after clicking `下载 PDF`, but the browser automation did not observe a download event within 5 seconds, so client-side file delivery remains an open validation item rather than a confirmed product failure.

### Follow-up priority

1. Fix the valid match response/view contract (BR-001), because it blocks the primary matching result.
2. Reload templates when the scene filter changes (BR-002).
3. Fetch and preserve the existing template body during edits (BR-003).
4. Add a browser-level assertion for the PDF response/file itself to close the download validation gap.

No fix was applied during this browser-only pass; the findings above are the current baseline for the next implementation and verification cycle.

### Responsive smoke check (2026-09-03)

Using the Codex in-app browser at `390x844` with the same synthetic local session:

- `/communications`: the mobile header collapses into an accessible navigation menu; the template cards, scene selector, refresh action, and edit dialog remain usable. The existing BR-002 filter-refresh defect reproduces at this size as well: selecting `感谢` leaves all nine cards visible until `刷新` is clicked.
- `/applications`: the summary, funnel, search/filter controls, application card, and new-application form remain readable and operable. The follow-up filter updates the visible card list correctly.
- The new-application form can be opened and dismissed without creating a record. No new page console errors were recorded during this pass.

The temporary viewport override was reset to the browser default after testing.

### Continued browser regression (2026-09-03)

- `/generate`: selecting the existing target role enables the next step; the three evidence-boundary controls correctly update the `必须使用` / `AI 自动判断` / `不使用` counts. Generation was not started because the synthetic account remains `尚未授权` for AI data processing.
- `/ats`: the missing version/job guard shows `请先选择简历版本和目标岗位。`; a valid direct rule check completes with score 80, structure 100, and keyword coverage 75. No new console errors were recorded.
- `/ai-consent`: the page clearly reports `尚未授权` and presents the explicit consent action. `/interviews` also remains on the preparation step when the required external-resume text is empty; the input has a required constraint and no console error occurs.
- `/interviews/history`: the empty state and job filter render correctly. `/career-materials`: type filtering, asynchronous keyword search (including a zero-result state), and returning to the full list all work after waiting for the loading state to settle.
- `/resumes/82` → current version → `导出 PDF`: export task `#51` reached `PDF 已准备好` with a 204.9 KB file. Clicking `下载 PDF` left the page healthy, but no browser download event was observed within 5 seconds; file delivery remains an open validation item, not a confirmed product failure.
- Switching the home page between English and Chinese updates the navigation and content, and the test restored the Chinese UI.

### Further browser coverage (2026-09-03)

- `/match/42` still reproduces BR-001: after the valid result loads, the view remains on `正在加载规则覆盖度…` and the console reports `Cannot read properties of undefined (reading 'matched')`. An invalid match ID still shows the intended friendly alert.
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

- `/career-materials`: the new-material panel validates a missing title, but allows saving when only a title is present and `来源原文` is empty. This created the synthetic local record `临时资料校验`; it was opened in edit mode successfully and then left unchanged. Treat the optional source field as a product-contract decision: title-only evidence may be too weak for later AI/job matching, although the current UI permits it.
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

### Downstream evidence-quality follow-up (2026-09-03)

- The title-only synthetic material `临时资料校验` appears as a selectable item in `/generate` → `选择资料范围` even though it has no `来源原文` content. No AI generation was started; this confirms the earlier optional-source observation can reach the downstream evidence-selection boundary and should be reviewed as a data-quality contract.
- `/ats`: a valid direct rule report remains visible after a full page reload, including score `80`; no new console errors were recorded.

### Loopback-host session check (2026-09-03)

- The authenticated synthetic session at `http://127.0.0.1:5173/ats?result=6` remains valid after opening another tab.
- Opening the equivalent protected route at `http://localhost:5173/applications` redirects to `/login?redirect=/applications` instead of reusing that session. This is host-scoped browser-session behavior, not a data/API failure, but local validation must use one loopback hostname consistently.

### Export error-state follow-up (2026-09-03)

- `/exports/51` continues to show `PDF 已准备好` with a visible `下载 PDF` action after reload.
- `/exports/999999` shows the alert `导出任务状态无法获取，请检查网络后刷新。`, but still leaves `正在获取导出任务…` in the page status after 2.5 seconds and offers no clear return/retry action. This is a new error-state UX finding; no console error was recorded. The browser was restored to the valid export task page afterward.

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

### Job-parser coverage finding (2026-09-04)

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

### Communication-defect recheck (2026-09-04)

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

### Jobs narrow-layout follow-up (2026-09-04)

- At `320x568`, `/jobs` reports no horizontal overflow both before and after opening the parse-result panel.
- The existing job’s parse panel remains readable at this width; its keyword list still contains only `Java`, `Spring Boot`, `MySQL`, and `Redis`, so the previously recorded missing-`Kafka` parser coverage finding persists.
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

### Core-route smoke scan (2026-09-04)

- Fresh navigation through `/`, `/career-materials`, `/resumes`, `/jobs`, `/generate`, `/ats?result=6`, `/applications`, `/interviews`, `/interviews/history`, `/interview-assets`, `/resume-import`, `/account`, `/ai-consent`, and `/exports/51` produced the expected primary content with no new page console errors or alerts.
- `/match/42` remains the only route in this scan with a console error: `TypeError: Cannot read properties of undefined (reading 'matched')` at `web/src/views/MatchResultView.vue:212`, leaving the page on `正在加载规则覆盖度…`.
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
- Starting from `/generate`, selecting the saved role, and entering the evidence-boundary step displays all four synthetic materials, including the title-only `临时资料校验`. Toggling one `必须使用` control changes the summary from `0/4/0` to `1/3/0`. The test stopped before the AI request because consent remains unavailable.

### Account-profile validation follow-up (2026-09-03)

- `/account` marks the display-name input as required, but clearing it and submitting still shows `个人资料已保存。` and accepts the empty value instead of blocking submission. This is a new validation defect: the UI/server contract should reject an empty display name or make the field explicitly optional.
- The synthetic account was restored to display name `codexqa_mtldthak` and verified again after a full page reload. No account email, password, consent, or other setting was changed; no page console error was recorded.

### Profile, job, and interview-asset follow-up (2026-09-03)

- `/career-materials`: opening the `测试求职者` profile card exposes the personal-profile editor; `返回资料库` closes it without changing the profile or the four-record material count.
- `/jobs`: submitting a new job with only a title correctly focuses the required job-description textarea and creates no record. The temporary title was not saved.
- `/interview-assets`: the existing answer asset opens in edit mode with its question, original answer, AI suggestion, chapter, and material selections populated. Cancelling returns to the list with the single saved asset unchanged.
- No new page console errors were recorded during this pass.

### Profile-recommendation persistence finding (2026-09-03)

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
