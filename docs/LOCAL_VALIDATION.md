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

## MySQL 持久化门禁（云服务器，双口径）

生产基线是 MySQL **8.4**（见 `docs/04`）；本机的 5.7 只是开发环境副产品，不是支持目标——V18 迁移用了只有 8.0+ 才有的窗口函数。因此门禁分两个口径，都在云服务器上执行：

| 口径 | 数据库 | 起点 | 证明什么 |
|---|---|---|---|
| `mysql84`（默认，生产口径） | 宿主 MySQL 8.4.11，建临时库 | **V1 全量** | V1→V36 的 **36 条**迁移都能在 8.4 上跑通 |
| `mysql57`（存量升级） | Docker `mysql:5.7.44`，临时容器，只绑 `127.0.0.1:3307` | V19 基线快照 | 停在 V19 的 5.7 老库能升级到 V36 的 **17 条**迁移 |

用法（本地 Git Bash；打包/上传/构建都发生在服务器上，服务器已有 JDK 17 + Maven 3.9 + `.m2` 缓存）：

```bash
bash scripts/Invoke-PersistenceGate.sh                  # 两个口径都跑
GATE=mysql84 bash scripts/Invoke-PersistenceGate.sh     # 只跑生产口径（热跑约 30s）
GATE=mysql57 bash scripts/Invoke-PersistenceGate.sh     # 只跑存量升级（首次含拉镜像 3-6 分钟）
```

**退出码语义**（不要混用）：

| 码 | 含义 |
|---|---|
| `0` | 绿 —— 用例**真实执行**且通过 |
| `1` | 红 —— 测试失败，或检测到「用例被 JUnit 跳过」 |
| `3` | 未测量 —— 镜像拉不到 / 容器起不来 / 内存不足 / 版本不符（结论未知，**不是通过**） |
| `75` | 并发占用 —— 另一次门禁正在跑 |

**「被跳过」不等于「通过」**：两个测试类都由 `@EnabledIfEnvironmentVariable` 门控。环境变量没生效时 `mvn` 仍返回 0，而 surefire 报告里是 `tests=1 skipped=1`。门禁脚本会解析 surefire XML 并当场判红——这是它与只看退出码的旧脚本（`Invoke-MySql57MigrationGate.ps1`）的关键区别，已用注入实验验证（把 `MYSQL84_LIVE_TEST` 置为 `false` → 门禁退出 1）。

**安全护栏**：`mysql84` 在与**生产库同实例**上建临时库，脚本要求库名匹配 `^intelligent_resume_gate84_[a-f0-9]{16}$`、`DROP` 前二次校验正则、并在运行前后各确认一次生产库 `intelligent_resume` 仍存在。`mysql57` 整个实例随容器消失，不需要 `DROP DATABASE`。

新增 Flyway 迁移时必须**同步改三处数字**：`MySql84FreshMigrationIT` 的 `36`、`MySql57MigrationLiveIT` 的 `17`、以及 `MySql57BaselineContractTest` 的静态对账，否则测试会红。

本机若已装 5.7，仍可使用 `.\scripts\Invoke-MySql57MigrationGate.ps1` 做快速校验；它假定本机 3306 已有 5.7，**官方入口是上面的 `.sh`**。

## Browser local-services smoke

Start the local MySQL + Bailian baseline first, then run:

```powershell
Set-Location web
npm run test:e2e:local
```

This command is opt-in and only accepts the documented loopback origin. Browser trace, video, and screenshots are disabled by default.


## 历史回归与审计记录

2026-09-03 至 2026-09-04 期间按时间顺序记录的浏览器手工回归与逐轮审计发现已归档至
[`docs/archive/browser-audit-log-2026-09.md`](./archive/browser-audit-log-2026-09.md)，仅用于追溯问题演进与证据链。

本文件只保留**当前有效**的验证步骤、判定标准与门禁。历史结论的闭环状态以 `docs/plans/` 与 `docs/reviews/` 中的报告为准。
