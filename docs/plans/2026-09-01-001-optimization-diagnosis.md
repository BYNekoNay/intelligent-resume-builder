# 智历优化诊断报告（2026-09-01）

> 诊断人：架构师（高见远）｜分支：codex/resume-loop-enhancements @ fd3369a（工作区干净）
> 基线：后端 548 测试绿 / web build 绿 / 真实 AI 全流程 17/17
> 性质：只读诊断，不实施。每条优化项给出文件/行号依据，避免臆测。
> 范围：核实昨日遗留 4 项候选 + 本轮 6 大领域扫描（代码质量/性能/测试/安全/可观测性/文档）。

---

## 0. 遗留候选核实结论（4/4 完成）

| 候选 | 结论 | 定位 |
| --- | --- | --- |
| 1. pdf-service token 文档不一致 | ✅ 确认属实，P1（O-01） | README.md:32 vs pdf-service/src/server.js:9 |
| 2. 前端轮询窗口 vs AI 任务时长 | ✅ 确认属实，P1（O-02） | useTaskPolling.ts + CommunicationView:31 + AtsCheckView:14 |
| 3. BAILIAN_MODELS 默认列表缺 qwen3.7 | ✅ 确认，但问题更深（该配置是死配置），P2（O-05） | application.yml:137 无消费方 |
| 4. HomeView/ResumeEditorView 组件化复核 | ✅ 维持"建议不做" | HomeView script 174 行；ResumeEditorView epoch 风险 |

---

## 1. 优化清单

### O-01 【P1】pdf-service token 加载与文档/脚本不一致

- **影响范围**：`README.md:32`、`pdf-service/package.json`、`pdf-service/src/server.js:7-10`、`scripts/Start-LocalValidation.ps1:46`、`scripts/lib/LocalValidationHelpers.ps1`
- **依据**：
  - `pdf-service/package.json` dependencies 仅 `express` + `puppeteer`，无 dotenv。
  - `pdf-service/src/server.js:9` 只读 `process.env.PDF_SERVICE_TOKEN`，`pdf-service/.env` 文件实际不生效。
  - `README.md:32` 却指导"为 PDF 服务创建 `pdf-service/.env`，并确保其中的 `PDF_SERVICE_TOKEN` 与根 `.env` 一致"——按文档操作不生效。
  - `scripts/Start-LocalValidation.ps1:46` 启动 pdf-service 仅 `npm run dev`，不注入 `PDF_SERVICE_TOKEN`；当前能工作只是因为 server 端 `application.yml:203` 与 pdf-service 都 fallback 到同一个 `dev-pdf-token-change-me`。一旦用户在根 `.env` 配置自定义 `PDF_SERVICE_TOKEN`（server 生效），本地 pdf-service 仍是默认值 → PDF 渲染 401。
  - 部署路径正常（`deploy/docker-compose.prod.yml:57-60` 通过 env_file 注入），问题集中在本地开发。
- **建议做法**（推荐最小改动）：
  1. 给 pdf-service 加 `dotenv` 依赖，在 `server.js` 顶部 `import 'dotenv/config'`，使 `pdf-service/.env` 真正生效（与 README 描述一致）；
  2. 同时让 `Start-LocalValidation.ps1` 在启动 pdf-service 前把根 `.env` 中的 `PDF_SERVICE_TOKEN` 注入进程环境，保证根 `.env` 与 pdf-service 配置天然一致。
  - 备选（只改文档）：README 删除创建 pdf-service/.env 的指导，改为"通过环境变量注入，与根 .env 保持一致"，并在脚本注入。
- **风险/工作量**：改代码 2 处文件 + 1 个测试断言；改文档更轻。风险低。

---

### O-02 【P1】前端 AI 任务轮询窗口短于后端 readTimeout（300s）

- **影响范围**：`web/src/composables/useTaskPolling.ts:24-26`、`web/src/views/MaterialSelectionConfirmView.vue:104`、`web/src/views/GenerationConfirmView.vue:70`、`web/src/views/InterviewView.vue:290`、`web/src/views/CommunicationView.vue:30-31`、`web/src/views/AtsCheckView.vue:13-14`
- **依据**：
  - 后端 `application.yml:139` `read-timeout-seconds: ${BAILIAN_READ_TIMEOUT_S:300}`，昨天（97ff180）为 qwen 推理模型把 60s 提升到 300s；真实生成简历约 40-120s（见 2026-08-31 memory）。
  - `useTaskPolling` 默认 `maxAttempts=60 × 2s = 120s`，三个使用点均未覆盖 `maxAttempts` → 轮询窗口 120s 与任务最长耗时 120s 擦边；任务慢时前端先显示"任务超时"（`common.taskTimeout`），但后端任务仍在执行最终 SUCCESS → 用户困惑、刷新后任务又出现。
  - `CommunicationView:31` `POLL_TIMEOUT_MS = 90_000`、`AtsCheckView:14` `POLL_TIMEOUT_MS = 90_000` 更短。
- **建议做法**：
  1. `useTaskPolling` 默认 `maxAttempts` 提到 150（150×2s=5min），或按任务类型覆盖：JOB_GENERATION / JOB_MATERIAL_SELECTION / COMMUNICATION_GENERATE 使用 ≥300s 窗口；
  2. `CommunicationView`、`AtsCheckView` 的 `POLL_TIMEOUT_MS` 提到 300_000（并顺手 O-10 复用 composable）；
  3. 超时文案明确提示"任务仍在后台执行，可刷新查看"（避免用户误以为失败）。
- **风险/工作量**：只改常量/默认值，低风险；前端行为测试（e2e mock）需同步 `timeout` 参数，避免测试挂起。

---

### O-03 【P1】AI 失败日志范围过宽，存在隐私边界风险

- **影响范围**：`server/src/main/java/com/intelligentresume/ai/provider/BailianAiProvider.java:167-180`
- **依据**：
  - e8f49fb 为排查 gzip 问题新增：`log.warn("Bailian API response failure: ... body={}", ...)` 与 `message={}`，把百炼响应 body / 异常 message 各截断 800 字符写入日志。
  - `PROJECT_CONTEXT.md` §3 明确："Prompt、模型原文、用户资料、联系方式、Token、密钥和用户 ID 不得写入日志"。百炼 4xx 错误 body 通常只有 `{error:{code,message}}`，但部分网关错误会在 message 中回显请求片段；JOB_GENERATION 输入含完整简历/JD，一旦回显即泄漏到日志。
  - 同类：`TaskExecutionService.java:183-184` 把 `e.getMessage()` + 堆栈写入日志；`JobGenerationService` / `InterviewFollowUpAiService` 的 `log.warn(..., msg)` 也含 provider 错误信息。
- **建议做法**：
  1. `BailianAiProvider` 失败日志只保留 `status + category + providerRequestId`，body/message 降为 DEBUG 或完全去掉；
  2. 若确需诊断信息，只记录百炼错误 `code`（如 InvalidApiKey/Throttling），不记录 message 原文；
  3. 巡检 `TaskExecutionService:183`、`InterviewFollowUpAiService:205-210` 同类日志。
- **风险/工作量**：低风险、小改动；需确保后续 AI 排障仍有 status/category/requestId 可用（TraceId 已覆盖）。

---

### O-04 【P1】新功能前端 e2e 覆盖盲区（版本对比/拖拽/报告交互）

- **影响范围**：`web/e2e/`（现有 5 个 spec、103 用例）、`web/src/views/CompareVersionsView.vue`、`web/src/views/ApplicationsView.vue:275-331`、`web/src/views/InterviewView.vue:634-663`
- **依据**：
  - `CompareVersionsView.vue`（diff 页，364 行）在全部 e2e 中**零引用**（grep `compare|resume-compare|/compare` 无结果）；diff 逻辑 `utils/resumeDiff.ts`（247 行）无任何自动化测试。
  - `ApplicationsView.vue:275-331` 拖拽路径（onLaneDrop）无测试；现有覆盖仅 `local-services.spec.ts:337-341` 通过下拉 selectOption 走状态变更，未覆盖拖拽 + 非法迁移 toast + 乐观锁 40901 重拉。
  - `InterviewView.vue:634-663` 报告 rounds 展开/保存资产（saveRoundAsset）无 e2e；`local-services.spec.ts:207/450` 仅点开报告主界面。
  - 后端侧：`ApplicationControllerIT.java` 无 `/stats` 控制器测试（stats 仅 `ApplicationServiceTest` 覆盖 service 层，公式/空记录已测）。
- **建议做法**：
  1. 新增 CompareVersionsView e2e：路由 mock 两个版本 JSON，断言 ADDED/REMOVED/MODIFIED 摘要与字段行、仅看变更过滤；
  2. ApplicationsView 补拖拽路径（Playwright `dragTo` 或 dispatchEvent 模拟），断言非法迁移 toast 且不发请求；
  3. InterviewView 报告 rounds：断言展开详情、保存资产后"已保存"态幂等；
  4. 后端补 `ApplicationControllerIT` stats 用例（返回结构 + 归属隔离）。
- **风险/工作量**：e2e 3 个文件新增用例，中等工作量；拖拽用原生 HTML5 事件需 mock `dataTransfer`，注意 flaky 处理。

---

### O-05 【P2】`BAILIAN_MODELS` 是死配置：删除而非补充 qwen3.7

- **影响范围**：`.env.example:11`、`server/src/main/resources/application.yml:137`
- **依据**：
  - `application.yml:137` 定义 `models: ${BAILIAN_MODELS:qwen-plus,deepseek-v3.2,...}`，但全库 grep `server/src/main/java` 无任何消费 `app.ai.bailian.models` 的代码；`BailianAiProvider.java:52-54` 只注入 `app.ai.bailian.model`（单数）。
  - 因此 `.env.example` 中的 `BAILIAN_MODELS` 与实际生效的 `BAILIAN_MODEL` 无关；"默认列表未含 qwen3.7-plus-2026-05-26"只是表象，根因是该列表根本没有作用。
  - 真实验证模型 `qwen3.7-plus-2026-05-26` 通过 `BAILIAN_MODEL` 配置（见 2026-08-31 memory），运行链路无需该列表。
- **建议做法**：
  1. 删除 `application.yml:137` 的 `models` 行与 `.env.example` 的 `BAILIAN_MODELS`（消除误导）；
  2. 在 `.env.example` / README 的 `BAILIAN_MODEL` 注释中补充已验证模型名 `qwen3.7-plus-2026-05-26` 作为推荐值。
- **风险/工作量**：极低；注意确认 `application-prod.yml` 无对应 `models` 引用（已检查无）。

---

### O-06 【P2】`AiCallContext.timeoutMs` 死字段与三处误导性超时常量

- **影响范围**：`server/src/main/java/com/intelligentresume/ai/provider/AiCallContext.java:13`、`JobGenerationService.java:30`（DEFAULT_TIMEOUT_MS=60_000）、`InterviewAiService.java:33`（PROVIDER_TIMEOUT_MS=60000）、`InterviewFollowUpAiService.java:52`（PROVIDER_TIMEOUT_MS=60_000）
- **依据**：
  - `AiCallContext.timeoutMs` 全库仅定义无消费；`BailianAiProvider` 超时来自构造时 `read-timeout-seconds:300`（application.yml:139），与传入值无关。
  - 三处调用 `new AiCallContext(..., 60_000)` 均传入死参数。开发者会误以为 follow-up/生成任务 60s 超时，实际 300s；若未来有人按此实现 per-call 超时，反而会把 JOB_GENERATION 压回 60s（与 97ff180 修复相悖）。
- **建议做法**：
  1. 删除 `AiCallContext.timeoutMs` 字段与三处传参（当前唯一 provider 用全局配置即可）；
  2. 或保留字段但让 `BailianAiProvider` 消费 `min(ctx.timeoutMs(), 全局 readTimeout)`，并给 JOB_GENERATION 传 300_000。
  - 推荐方案 1（简单）；方案 2 仅在未来出现多 provider/按任务超时时再做。
- **风险/工作量**：低；涉及 4 个文件 + 相关测试断言检查。

---

### O-07 【P2】ApplicationService.stats 全量内存聚合 + 前端重复加载

- **影响范围**：`server/src/main/java/com/intelligentresume/application/service/ApplicationService.java:117-157`、`web/src/views/ApplicationsView.vue:90-105`
- **依据**：
  - `stats()` 用 `repository.findByUserIdOrderByUpdatedAtDesc(userId)` 拉取用户**全部**投递记录后在内存 count/ratio/平均时长。
  - `ApplicationsView.load()`（:93-96）`Promise.all([listApplications(...), getApplicationStats()])` 同一批数据加载两份（列表一份 + stats 再全量一份）。
  - 当前量级小（个人投递几十条），不构成瓶颈，但模式随数据增长退化。
- **建议做法**：
  1. stats 改为一条 SQL 聚合（`GROUP BY status` + 时长用 `AVG(TIMESTAMPDIFF(...))`），或至少用 `@Query` 只查需要的列；
  2. 前端保留双接口但让 stats 接口只返回聚合（避免全量列传输）。
- **风险/工作量**：中（SQL 聚合需重写 + 更新 ApplicationServiceTest 3 个用例）；属可延后优化。

---

### O-08 【P2】InterviewHistoryService 聚合 N+1

- **影响范围**：`server/src/main/java/com/intelligentresume/interview/service/InterviewHistoryService.java:45-54`
- **依据**：`summary()` 对每个 session 调 `recordRepository.findBySessionIdOrderByCreatedAtAsc`，N 个会话 = N 次查询。注释自述"个人数据量级小"，可接受但可优化。
- **建议做法**：改用 `recordRepository.findBySessionIdInOrderByCreatedAtAsc(ids)` 一次批量加载后分组，或 repository 层 `@Query` 返回 `(sessionId, count, avg(roundScore))`。
- **风险/工作量**：低-中；需补/改 InterviewHistoryServiceTest。

---

### O-09 【P2】ResumeDetailView 全量拉取面试答案资产

- **影响范围**：`web/src/views/ResumeDetailView.vue:112-117`、`web/src/api/interviewAsset.ts:34-36`、`server/.../interview/asset/controller/InterviewAssetController.java:35-45`
- **依据**：
  - `loadRelatedAssets()` 调 `listInterviewAssets()` **无过滤参数**全量拉取，前端再按 `relatedSectionKey` filter（:55-57）。
  - 后端 `GET /api/interview-answer-assets` 已支持 `sectionKey/jobDescriptionId/keyword` 过滤（controller:37-40）；`ResumeEditorView.vue:75-86` 已正确使用 `listInterviewAssets({ sectionKey })`——ResumeDetailView 是遗漏。
- **建议做法**：ResumeDetailView 传 `sectionKey` 或按当前简历相关参数过滤；资产量大时后端加分页。
- **风险/工作量**：低。

---

### O-10 【P2】残留自定义轮询实现未收敛到 useTaskPolling

- **影响范围**：`web/src/views/CommunicationView.vue:86-188`、`web/src/views/AtsCheckView.vue:33-90`
- **依据**：昨天 8f33b75 新建 useTaskPolling 并替换 2 处重复实现，但这两处仍各自维护 `pollTimer/pollGeneration/POLL_TIMEOUT_MS`（Communication 90s、Ats 90s），与 useTaskPolling 语义重复（含 O-02 的超时不一致）。
- **建议做法**：将两处改为 useTaskPolling（onError/onTimeout 语义已对齐：AtsCheck 需在超时后展示本地规则报告，Communication 需在超时后进入 waitingInBackground），删除本地常量。
- **风险/工作量**：低-中；注意 AtsCheck 超时后"保留部分报告"的行为需在迁移时保留（e2e ats-ai.spec 有超时相关断言）。

---

### O-11 【P2】监控告警无法区分 AI 失败类别

- **影响范围**：`monitoring/prometheus/rules/intelligent-resume-alerts.yml`（AiProviderSuccessRateLow）
- **依据**：现有规则只按 `outcome="success"` 全局算成功率；`AppObservability.recordAiProviderCall` 已记录 `category` 维度（BailianAiProvider:186 传入 AiFailureCategory），但告警未使用。生产上 4xx（密钥过期/配额）与 5xx（百炼故障）应触发不同处置。
- **建议做法**：新增 `AiProviderClientErrorsHigh`（`category="PROVIDER_4XX"` 比例 > 阈值，提示检查密钥/配额）与 `AiProviderServerErrorsHigh`（`PROVIDER_5XX`/`PROVIDER_TIMEOUT`，提示上游故障），保留全局规则。
- **风险/工作量**：低（纯告警规则模板）；需在部署环境验证规则语法（本机 docker 不可用，按 docs §7.3 验证）。

---

### O-12 【P2】README/文档未体现求职闭环新增能力

- **影响范围**：`README.md:14`、`docs/LOCAL_VALIDATION.md`
- **依据**：README"求职闭环"仍是一行概述（:14），未提及本轮新增的沟通模板库（`GET /api/communication/templates` + preview）、投递统计（`GET /api/applications/stats`）、版本对比（`/resumes/:id/compare`）、薄弱项练习（`POST /api/interviews/{id}/follow-up`）；LOCAL_VALIDATION 未说明 follow-up 验证（2026-08-31 已手工验证 PASS）。
- **建议做法**：README 能力段补 2-3 行（模板库/投递看板统计/版本对比/薄弱项练习）；LOCAL_VALIDATION 补 follow-up 验证步骤一句话。
- **风险/工作量**：极低。

---

### O-13 【P2】ApplicationsView.selectResumeVersion 顺序遍历 N+1

- **影响范围**：`web/src/views/ApplicationsView.vue:127-138`
- **依据**：`selectResumeVersion` 在版本不属第一个 resume 时 `for` 循环逐个 `loadVersions()`（每轮一个 HTTP 请求）直到命中；极端情况 = resume 数 N 次请求。量小，但可用一次性 `listVersions` 或后端按 versionId 反查优化。
- **建议做法**：优先从 `records` 找到所属 resumeId 再 `loadVersions`；或并行 `Promise.all` 所有 resume 的版本。
- **风险/工作量**：低。

---

### O-14 【P2】补 ApplicationControllerIT stats 接口测试

- **影响范围**：`server/src/test/java/com/intelligentresume/application/controller/ApplicationControllerIT.java`
- **依据**：stats 只有 service 层测试（ApplicationServiceTest:324-362），控制器层无 `/api/applications/stats` 契约测试（返回结构、归属隔离、未登录 401）。
- **建议做法**：补 2-3 个用例（正常聚合 + 他人数据不可见 + 未认证 401）。
- **风险/工作量**：低；可并入 O-04 一并实施。

---

## 2. 建议不做（避免过度工程）

| 项 | 理由（依据） |
| --- | --- |
| HomeView 组件化拆分 | script 仅 174 行、模板 114 行、样式 ~700 行；逻辑集中在 `workspaceActions` 计算属性，拆分收益低。维持昨日结论。 |
| ResumeEditorView 抽 AI 助手/素材库 composable | 涉及 `editorContextEpoch` 上下文隔离（2026-08-31 U3），抽离后旧请求污染新上下文风险高；昨日已抽 5 个纯 UI 组件完成主要瘦身。维持昨日结论。 |
| JobGenerationService(515)/InterviewAiService(404) 再拆分 | 两者为 AI 领域核心，含 legacy 快照兼容 + 严格契约校验；昨日已把 InterviewService 1278→111 完成第一轮拆分，再拆边际收益低、回归风险高。 |
| 引入 vitest 前端单元测试框架 | 现有 103 个 Playwright e2e 覆盖主路径，新增框架需同步 CI/依赖/基线；优先补 e2e（O-04）即可。 |
| 清理 i18n 未使用键 | `check-i18n` 已覆盖硬编码文案与 zh/en 键缺失；死键不影响功能，仅维护成本。如需可后续扩展脚本（P3，不列为主项）。 |

---

## 3. 优先级汇总

- **P0（必须）**：无。未发现数据丢失、越权访问或可直接利用的安全漏洞（新功能归属校验/占位符白名单/diff 渲染均经核查正确）。
- **P1（建议，近期）**：O-01（pdf token 文档/脚本）、O-02（轮询窗口）、O-03（隐私日志范围）、O-04（新功能 e2e 盲区）。
- **P2（建议，规划）**：O-05 ~ O-14（死配置清理、死字段清理、聚合/N+1/重复加载、轮询收敛、告警细分、文档补全、补测试）。

## 4. 核实通过的安全面（本轮确认无问题）

- communication_template 占位符注入：`TemplatePlaceholderService.validate()` 白名单校验（PLACEHOLDER_WHITELIST，非法占位符抛 40001），填充为纯字符串替换不经模型。
- follow-up 会话归属：`InterviewFollowUpAiService.createFollowUpTask` 用 `findByIdAndUserId` + COMPLETED 状态校验 + 复用 INTERVIEW_COACH consent 类别。
- stats 聚合归属：`ApplicationService.stats(userId)` 只查当前用户。
- diff XSS：`CompareVersionsView` 全部 Vue 插值渲染，无 v-html；`utils/resumeDiff.ts` 纯前端比较。
- i18n 文案注入：`check-i18n` 阻断硬编码用户文案。
- 密钥：BailianAiProvider 日志不含 Authorization 头（仅 body/message，见 O-03 收窄）。
