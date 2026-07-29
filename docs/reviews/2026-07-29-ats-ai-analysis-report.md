# ATS 规则检查接入 AI 深度分析实施报告

日期：2026-07-29

## 执行结论

ATS 检查已改为“本地规则评分 + AI 语义分析”混合流程。本地规则先执行、先持久化，并保持唯一权威分数来源；AI 仅补充岗位语义覆盖、证据质量、可读性风险和修改优先级。AI 不可用、未授权、配额耗尽、Provider 失败或输出不合法时，接口仍返回完整规则报告。

本次没有新增数据库表或迁移。AI 状态和洞察保存在现有 `ats_check_result.result_json`。未提交、未推送、未创建 PR。

## API 契约

- `POST /api/ats/check`：校验当前用户的简历版本和岗位，计算并持久化规则结果；AI 可用时创建 `ATS_ANALYSIS` 任务并返回 `ANALYZING`，否则返回 `RULES_FALLBACK`。
- `GET /api/ats/checks/{id}`：仅记录所属用户可读取，用于轮询和刷新恢复。
- `POST /api/ats/checks/{id}/ai-retry`：重试 AI 分析，不重新计算规则分数、不新建 ATS 记录。
- AI 幂等键：`ats:{atsResultId}:v1`。
- `ATS_ANALYSIS` 默认日配额：20，可由 `app.ai.quota.ATS_ANALYSIS` 覆盖。
- 响应新增 `analysisStatus`、`analysisSource`、`aiTaskId`、`aiInsights`、`fallback`；原有 `id`、`totalScore`、`checks`、`passedChecks`、`risks`、`priorities`、`disclaimer` 保持兼容。

## 状态与数据保护

- 成功：`COMPLETED / HYBRID`，规则分数保持不变，写入经过 Schema 校验的 `aiInsights`。
- 降级：`RULES_FALLBACK / RULES`，保留全部规则结果并写入结构化原因。
- 失败分类：`AI_DISABLED`、`CONSENT_REQUIRED`、`QUOTA_EXCEEDED`、`PROVIDER_TIMEOUT`、`PROVIDER_ERROR`、`INVALID_RESPONSE`、`UNKNOWN`。
- 创建任务前裁剪输入快照：字符串最多 6000 字符、列表最多 50 项、对象最多 80 个字段、嵌套深度最多 8 层。
- Prompt 将简历、JD 和规则结果包裹在不可信数据边界内，并禁止执行其中指令、泄露系统提示、虚构经历、输出新分数或招聘结论。
- AI 输出要求精确顶层字段、合法枚举和长度/数量限制；无法在简历输入中定位的 `quote` 会被清空。
- 完成或降级写入必须匹配当前 `aiTaskId`；Provider 不可用的手动重试也受该保护，避免并发覆盖新结果。
- AI 授权沿用策略 `v1.2.0`，ATS 要求任务范围 `ATS_ANALYSIS` 和数据类别 `RESUME`、`JOB_DESCRIPTION`。

## 前端行为

- 提交后先显示“正在合并规则与 AI 分析”，正式报告等待 AI 成功或确定降级后出现。
- 每 1.5 秒轮询 ATS 专用查询接口；URL 使用 `?result={id}` 支持刷新恢复。
- 前台等待 90 秒后显示规则结果和“AI 仍在后台分析”，刷新可恢复轮询。
- 混合结果显示“AI + 规则”；降级结果显示“规则降级”、具体原因以及授权或重试操作。
- 配额耗尽不显示无效即时重试；可重试 Provider 故障只重试 AI，不重复执行规则检查。
- 免责声明已统一为：规则分数与 AI 建议均用于简历改进参考，不代表真实企业 ATS 结果、录用概率或招聘决定。

## 主要改动文件

后端 ATS：

- `server/src/main/java/com/intelligentresume/ats/controller/AtsController.java`
- `server/src/main/java/com/intelligentresume/ats/domain/AtsCheckResult.java`
- `server/src/main/java/com/intelligentresume/ats/dto/AtsCheckResponse.java`
- `server/src/main/java/com/intelligentresume/ats/dto/AtsAiInsights.java`
- `server/src/main/java/com/intelligentresume/ats/dto/AtsAnalysisStatus.java`
- `server/src/main/java/com/intelligentresume/ats/dto/AtsAnalysisSource.java`
- `server/src/main/java/com/intelligentresume/ats/dto/AtsFallbackCode.java`
- `server/src/main/java/com/intelligentresume/ats/dto/AtsFallbackInfo.java`
- `server/src/main/java/com/intelligentresume/ats/repository/AtsCheckResultRepository.java`
- `server/src/main/java/com/intelligentresume/ats/service/AtsService.java`
- `server/src/main/java/com/intelligentresume/ats/service/AtsAiAnalysisService.java`
- `server/src/main/java/com/intelligentresume/ats/service/AtsAiAnalysisException.java`
- `server/src/main/java/com/intelligentresume/ats/service/AtsAiPromptBuilder.java`
- `server/src/main/java/com/intelligentresume/ats/service/AtsAiResultValidator.java`
- `server/src/main/java/com/intelligentresume/ats/service/AtsResultStateService.java`

复用的 AI 基础设施：

- `server/src/main/java/com/intelligentresume/ai/consent/service/AiConsentService.java`
- `server/src/main/java/com/intelligentresume/ai/provider/AiProvider.java`
- `server/src/main/java/com/intelligentresume/ai/provider/BailianAiProvider.java`
- `server/src/main/java/com/intelligentresume/ai/ratelimit/AiQuotaService.java`
- `server/src/main/java/com/intelligentresume/ai/task/domain/AiTaskType.java`
- `server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java`
- `server/src/main/java/com/intelligentresume/ai/worker/TaskExecutionService.java`
- `server/src/main/resources/application.yml`
- `server/src/test/resources/application-test.yml`

前端：

- `web/src/api/ats.ts`
- `web/src/api/ai.ts`
- `web/src/i18n/index.ts`
- `web/src/views/AtsCheckView.vue`
- `web/e2e/ats-ai.spec.ts`
- `web/e2e/local-services.spec.ts`
- `web/e2e/workflow.spec.ts`

新增或扩展的 ATS 测试：

- `server/src/test/java/com/intelligentresume/ats/controller/AtsControllerIT.java`
- `server/src/test/java/com/intelligentresume/ats/controller/AtsConsentFallbackIT.java`
- `server/src/test/java/com/intelligentresume/ats/controller/AtsQuotaFallbackIT.java`
- `server/src/test/java/com/intelligentresume/ats/service/AtsAiAnalysisServiceTest.java`
- `server/src/test/java/com/intelligentresume/ats/service/AtsAiPromptBuilderTest.java`
- `server/src/test/java/com/intelligentresume/ats/service/AtsAiResultValidatorTest.java`
- `server/src/test/java/com/intelligentresume/ai/worker/TaskExecutionServiceTest.java`
- `server/src/test/java/com/intelligentresume/ai/ratelimit/AiQuotaServiceTest.java`

## 验证命令与精确结果

1. 聚焦回归：
   - 命令：`mvn "-Dtest=AtsAiPromptBuilderTest,AtsAiResultValidatorTest,AtsControllerIT,AtsConsentFallbackIT,TaskExecutionServiceTest" test`
   - 结果：13 tests，0 failures，0 errors，0 skipped。
2. 新增降级证据：
   - 命令：`mvn "-Dtest=AtsAiAnalysisServiceTest,AtsQuotaFallbackIT,AtsAiPromptBuilderTest" test`
   - 首次结果：因测试把授权接口的既有 `201 Created` 契约误写为 `200` 而失败；修正测试断言后重跑。
   - 最终结果：5 tests，0 failures，0 errors，0 skipped。
3. 后端完整回归：
   - 命令：`mvn test`
   - 结果：399 tests，0 failures，0 errors，2 skipped；BUILD SUCCESS；总耗时 01:35。
4. i18n guard：
   - 命令：`npm run check:i18n`
   - 结果：9 guard tests 通过；36 个 Vue 文件、2 个 locale 通过审计。
5. 前端类型检查：
   - 命令：`npx vue-tsc --noEmit`
   - 结果：通过，无错误输出。
6. 前端生产构建：
   - 命令：`npm run build`
   - 结果：1744 modules transformed；构建成功；主 JS 592.49 kB，gzip 179.66 kB。
   - 已知警告：主 chunk 超过 500 kB；这是现有全局打包体积问题，本次未做跨模块拆包。
7. ATS E2E：
   - 命令：`npx playwright test e2e/ats-ai.spec.ts`
   - 结果：4/4 passed，耗时 12.0s。
   - 场景：AI 成功后一次展示混合报告；未授权时保留规则报告并跳转授权；Provider 失败后仅重试 AI 并成功；直接规则检查不创建 AI 任务。
   - 手机视口：390 x 844，验证无水平溢出；其余场景使用 Playwright 默认桌面视口。

## 失败降级证据

- `AtsControllerIT`：AI 未配置返回 `AI_DISABLED`，旧字段和规则结果可用；跨用户读取、重试和关联资源返回 404。
- `AtsConsentFallbackIT`：Provider 已配置但缺少 ATS 授权时返回 `CONSENT_REQUIRED`，不返回 403。
- `AtsQuotaFallbackIT`：ATS 配额设为 0 且授权有效时返回 `QUOTA_EXCEEDED`，保留规则得分且不可即时重试。
- `AtsAiAnalysisServiceTest`：`Read timed out` 分类为 `PROVIDER_TIMEOUT`；HTTP 503 分类为 `PROVIDER_ERROR`。
- `AtsAiResultValidatorTest`：伪造引用被清空；额外分数字段、缺失字段和非法枚举按 `INVALID_RESPONSE` 拒绝。
- `TaskExecutionServiceTest`：Worker 成功写入混合结果；终态无效响应写入规则降级；AI 完成前后规则总分不由 Worker 修改。
- `AtsAiPromptBuilderTest`：提示注入文本只出现在不可信数据边界；持久化任务输入执行长度裁剪。

## 未完成项与未验证风险

- E2E 使用网络 Mock 验证页面状态机，没有等待真实 90 秒；90 秒阈值由前端常量和逻辑实现，真实长任务体验仍需人工浏览器验证。
- 本轮没有新增数据库迁移，未进行生产数据量下的 ATS 轮询/任务表压力测试。
- 构建仍存在 593.19 kB 主 chunk 警告，与 ATS 功能正确性无关。

## 真实百炼回归补充

- 首轮真实调用暴露模型偶尔返回超过字段上限的叙述文本，导致严格校验将整份输出标记为 `INVALID_RESPONSE` 并降级到本地规则报告。
- `AtsAiResultValidator` 现在对必填叙述文本执行去除首尾空白和最大长度裁剪；空白必填字段、非法枚举、额外字段、错误结构与伪造引用仍严格拒绝。
- 聚焦回归命令：`mvn -q '-Dtest=AtsAiResultValidatorTest,AtsAiAnalysisServiceTest,AtsAiPromptBuilderTest' test`；结果 15 tests，0 failures，0 errors。
- 后端完整回归命令：`mvn test`；结果 404 tests，0 failures，0 errors，2 skipped，`BUILD SUCCESS`，Maven 总耗时 57.366s。
- 真实百炼冒烟：初始返回 `ANALYZING / RULES`，约 29.7 秒后完成为 `COMPLETED / HYBRID`；AI 摘要 186 字，语义覆盖 7 项，优先行动 2 项，`fallback=null`。
- ATS E2E 命令：`npx playwright test e2e/ats-ai.spec.ts`；结果 4 passed，耗时 12.0s。
- 生产构建命令：`npm run build`；i18n guard 9/9，通过 36 个 Vue 文件和 2 个 locale；共转换 1744 modules，构建耗时 6.28s。

### readabilityRisks 契约修复

- 真实任务 16 的两次百炼 Provider 调用均成功，但初次响应和自动修复响应都因 `readabilityRisks must be a non-blank string` 被拒绝，最终进入 `RULES_FALLBACK / INVALID_RESPONSE`。
- 根因是 Prompt 只限制了 `readabilityRisks` 的数组数量，没有声明元素必须为字符串；同时内部校验错误被放入不可信数据区，自动修复指令无法可靠利用该错误。
- Prompt 现已明确要求 `readabilityRisks` 为仅包含 1-500 字符非空字符串的 JSON 数组，无风险时返回空数组；内部校验错误移入可信修复指令，原始无效响应仍保留在不可信数据边界。
- 校验器忽略 `null` 和空风险项，但对象、数字等错误结构仍按 `INVALID_RESPONSE` 拒绝。Prompt 版本提升为 `v1.0.1`，Schema 版本保持 `v1.0.0`。
- 真实百炼任务 17：初始 `ANALYZING`，20.3 秒后进入 `COMPLETED / HYBRID`；AI 摘要 216 字，返回 4 条 `readabilityRisks`，`fallback=null`。
- 修复后聚焦测试：16 tests，0 failures，0 errors；后端完整回归：405 tests，0 failures，0 errors，2 skipped；ATS E2E：4 passed。
