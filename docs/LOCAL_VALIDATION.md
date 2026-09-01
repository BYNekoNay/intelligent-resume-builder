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

The start script uses the already-running local MySQL instance, then starts PDF service on `3001`, API on `8080`, and web on `5173`. It explicitly targets the web process at `http://127.0.0.1:8080`, so stale developer-only `web/.env` ports do not affect this validation lane.
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
