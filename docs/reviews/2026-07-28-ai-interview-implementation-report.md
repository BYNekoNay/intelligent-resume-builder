# AI 模拟面试完整闭环 — 实施报告

日期：2026-07-29

---

## 执行摘要

| 单元 | 状态 | 说明 |
|------|------|------|
| U0 保护现场 | 完成 | 无新 commit；保留工作区既有修改；已发布 V18 恢复为 HEAD 原文 |
| U1 数据库迁移 | 完成（边界明确） | MySQL 8.x 支持全新 V1→V21；V21 已在本机 MySQL 8.0.43 与 H2 验证；MySQL 5.7 门禁已扩展为 V19→V21 但本轮未重跑 |
| U2 AI 深模块 | 完成 | InterviewAiService + InterviewContextSanitizer 实现并单元测试 |
| U3 Prompt 与 JSON 契约 | 完成 | interview-coach-v11，五维评分、结束规则、输出语言约束、语言漂移检测及一次受控修复重试 |
| U4 编排、幂等、配额、降级 | 完成 | 两阶段短事务、幂等检查+指纹比较、配额 60、同意/配额→AI_ACTION_REQUIRED |
| U5 同意与前端体验 | 完成 | v1.2.0 全量更新、同意跳转、重新授权入口、i18n 全覆盖 |
| U6 报告聚合 | 完成 | dimensionScores、MIXED、五维平均分、去重反馈 |
| U7 验证 | 非浏览器通过 | 后端 409 测试、前端构建、V1→V21 迁移及真实百炼中文会话通过；完整浏览器验收由用户执行 |

## 变更文件清单

### 后端 — 修改 (Modified)

| 文件 | 变更说明 |
|------|----------|
| `AiConsentService.java` | 默认政策版本 v1.1.0→v1.2.0 |
| `AiProvider.java` | 新增 `modelCode()` 接口方法 |
| `BailianAiProvider.java` | 实现 `modelCode()`；面试结构化任务温度设为 0.1 |
| `AiQuotaService.java` | 新增 INTERVIEW_COACH 配额注入 (60) |
| `AiTaskType.java` | 新增 `INTERVIEW_COACH` 枚举值 |
| `InterviewController.java` | 重写为 7 端点（start/getState/answer/retry/continue-with-rules/finish/report） |
| `InterviewRecord.java` | 新增 `evaluationSource`、`aiAttemptId` 字段 |
| `InterviewSession.java` | 新增 `targetQuestionCount`、`minQuestionCount`、`maxQuestionCount`、`executionMode`、`completionReason` |
| `InterviewStatus.java` | 新增 `GENERATING_QUESTION`、`EVALUATING_ANSWER`、`AI_ACTION_REQUIRED` |
| `AnswerInterviewRequest.java` | 回答必须为非空白文本，最大 8000 字符 |
| `InterviewReportResponse.java` | 新增 `DimensionScores` 内部记录、`dimensionScores` 字段、`evaluationSource` 支持 MIXED |
| `StartInterviewRequest.java` | 新增 `targetQuestionCount` (4-12，默认 6) |
| `InterviewService.java` | **完整重写**：两阶段事务、幂等+指纹、配额、规则降级复用、重试防迟到覆盖、统一轮次决策 |
| `application.yml` | 默认模型切换为 qwen-plus、政策版本 v1.2.0、INTERVIEW_COACH 配额 60、read-timeout 120→60 |
| `JobGenerationControllerIT.java` | 政策版本 v1.1.0→v1.2.0 |
| `InlineOptimizeControllerIT.java` | 同上 |
| `AiQuotaServiceTest.java` | 新增 interviewCoach 构造函数参数 |
| `AiTaskControllerIT.java` | 同上 |
| `DatabaseTaskWorkerIT.java` | 同上 |
| `FlywayMigrationIT.java` | 更新 V18 测试断言（AWAITING_ANSWER 替代 COMPLETED） |
| `InterviewAssetControllerIT.java` | 重写适配新 API |
| `InterviewControllerIT.java` | 19 项集成测试（边界、同意、幂等、规则降级复用、finish、getState） |
| `application-test.yml` | INTERVIEW_COACH 配额 60、政策版本 v1.2.0 |

### 后端 — 新增 (Untracked)

| 文件 | 说明 |
|------|------|
| `AiAttemptOperationType.java` | 枚举：INITIAL_QUESTION, ANSWER_EVALUATION |
| `AiAttemptStatus.java` | 枚举：PROCESSING, SUCCESS, FAILED, RULE_FALLBACK |
| `CompletionReason.java` | 枚举：AI_INFORMATION_COMPLETE, MAX_QUESTION_LIMIT, TARGET_REACHED_IN_RULE_MODE, USER_FINISHED |
| `EvaluationSource.java` | 枚举：AI, RULE, MIXED |
| `ExecutionMode.java` | 枚举：AI, RULE |
| `InterviewOutputLanguage.java` | 枚举：ZH_CN, EN；作为会话级输出语言真值 |
| `InterviewAiAttempt.java` | JPA 实体：interview_ai_attempt 表 |
| `InterviewCoachContext.java` | AI 上下文 DTO |
| `InterviewCoachResponse.java` | AI 响应 DTO（首题 + 评估），含 Jakarta Validation |
| `InterviewStateResponse.java` | 会话状态响应（12 字段 + LastEvaluation + AiFailureInfo） |
| `InterviewAiAttemptRepository.java` | JPA Repository |
| `InterviewAiService.java` | AI 面试教练服务（Prompt + Schema 校验） |
| `InterviewContextSanitizer.java` | 上下文脱敏服务 |
| `V19__optional_interview_job.sql` | interview_session.job_description_id → NULL |
| `V20__ai_interview_flow.sql` | AI 面试完整迁移 |
| `V21__interview_output_language.sql` | 新增非空 `output_language`，旧会话默认回填 `ZH_CN` |
| `InterviewContextSanitizerTest.java` | 30 项单元测试 |
| `InterviewAiServiceTest.java` | Prompt、严格 JSON、一次修复及配额预留单元测试 |
| `BailianAiProviderLiveIT.java` | 真实首题与回答评估契约门禁（显式启用） |
| `MySql57MigrationLiveIT.java` | 真实 MySQL 5.7 V19 baseline→V21 升级、索引及输出语言列门禁（显式启用） |

### 前端 — 修改

| 文件 | 说明 |
|------|------|
| `ai.ts` | v1.2.0 完整授权范围与完整授权判定 |
| `interview.ts` | 完整重写：7 API 函数 + 全类型；启动请求携带 `outputLanguage` |
| `index.ts` (i18n) | 新增面试状态文案、插值支持，并将授权说明扩展为全部 AI 功能 |
| `InterviewView.vue` | 完整状态机 UI、失败恢复、五维反馈、完成态、刷新恢复和幂等重试；界面 locale 映射为会话输出语言 |
| `workflow.spec.ts` | 面试 E2E 增加处理中轮询、完成报告恢复和失败答案保留 |

## 数据库迁移与回滚

### V20 迁移内容

- `interview_session` 新增 5 列 + `current_question` 改为 NULL
- `interview_record` 新增 2 列
- 新建 `interview_ai_attempt` 表（20 列 + 2 唯一约束 + 1 FK）
- 旧数据：`IN_PROGRESS → AWAITING_ANSWER`，历史会话/记录来源回填为 `RULE`

### V21 迁移内容

- `interview_session.output_language VARCHAR(16) NOT NULL DEFAULT 'ZH_CN'`
- 旧会话自动回填为 `ZH_CN`；新会话显式保存 `ZH_CN` 或 `EN`

### 回滚策略

V20、V21 均为增量迁移，无 `DROP COLUMN`。回滚需手动 `DROP TABLE interview_ai_attempt` 并删除新增列；回退 V21 需删除 `interview_session.output_language`。

### MySQL 5.7 兼容性

- 无 JSON 默认值
- 已发布 V18 保留 `ROW_NUMBER()` 原文以维持 Flyway checksum；已有 `intelligent_resume` 库已执行 V18/V19
- 项目生产与全新安装基线为 MySQL 8.x，执行标准 V1-V21；本轮实际数据库为 MySQL 8.0.43
- MySQL 5.7 不支持空库执行全部历史迁移；门禁从数据为空的 V19 schema baseline 执行 V20、V21，禁止 blanket `flyway repair`
- 本地真实数据验证使用 `-CloneDatabase`：逐表复制并校验行数，仅克隆库 baseline V19 后迁移至 V21，原库及其 Flyway 历史不变
- 无生成列 / CHECK 约束
- `DATETIME` + `ON UPDATE CURRENT_TIMESTAMP`（MySQL 5.6.5+）
- `TINYINT(1)` 替代 BOOLEAN

## HTTP 接口与 DTO

### 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/interviews/start` | 开始面试（Idempotency-Key 必需） |
| GET | `/api/interviews/{id}` | 获取会话状态 |
| POST | `/api/interviews/{id}/answer` | 提交回答（Idempotency-Key 必需） |
| POST | `/api/interviews/{id}/ai/retry` | 重试失败 AI 操作 |
| POST | `/api/interviews/{id}/continue-with-rules` | 切换到规则模式 |
| POST | `/api/interviews/{id}/finish` | 用户主动结束（至少 1 题） |
| GET | `/api/interviews/{id}/report` | 获取报告（仅 COMPLETED） |

### InterviewStateResponse

```
interviewId, status, executionMode, currentQuestion, currentQuestionNo,
completedQuestionCount, targetQuestionCount, minQuestionCount, maxQuestionCount,
lastEvaluation, aiFailure, completionReason
```

### AiFailureInfo

```
operationId, stage (INITIAL_QUESTION|ANSWER_EVALUATION),
retryable, reauthorizationRequired, messageCode
```

### InterviewReportResponse

```
totalScore, summary, strengths, weaknesses, resumeSuggestions, expressionSuggestions,
dimensionScores {relevance, evidenceSpecificity, structureClarity, roleCompetency, authenticityReflection},
targetQuestionCount, actualQuestionCount, completionReason,
evaluationSource (AI|RULE|MIXED), aiEvaluatedRounds, ruleEvaluatedRounds
```

## Prompt 版本与 JSON 契约

- 版本：`interview-coach-v11`
- 首题契约：`question`(10-500), `focus`(1-100), `expectedSignals`(1-5，每项最多 300 字), `coverageTags`(1-3)
- 评估契约：`dimensionScores`(五维), `strengths`(0-3), `improvements`(1-3), `evidenceQuotes`(0-5), `suggestedAnswer`(50-2000), `resumeSuggestions`(0-3), `expressionSuggestions`(0-3), `coverageTags`(1-5), `informationComplete`, `completionReason`, `nextQuestion`
- 五维上限：relevance=25, evidenceSpecificity=25, structureClarity=20, roleCompetency=20, authenticityReflection=10
- 总分由服务端 `total()` 方法求和
- 评分为授予分，禁止负数；Schema 校验失败时仅重试修复一次，第二次仍非法则进入 `AI_ACTION_REQUIRED`
- 启动请求将界面 locale 映射为 `ZH_CN` 或 `EN` 并持久化到会话；首题、每轮评估和 AI 重试始终读取同一会话语言
- Prompt 在可信边界外明确声明 `REQUIRED OUTPUT LANGUAGE`；中文会话中的明显纯英文叙述或英文会话中的中文叙述触发一次受控修复
- 修复 Prompt 同样携带输出语言约束；`evidenceQuotes` 和技术标签不参与漂移判断，避免 Java、Kafka 等术语误报
- 真实回归发现 `deepseek-v3.2` 对非负评分契约不稳定，默认模型改为 `qwen-plus`

### 输出语言问题根因与修复

旧流程没有把前端 locale 传入启动请求，也没有在 `interview_session` 保存输出语言；Prompt 只能猜测 JD/简历语言，因此同一次中文响应中的 `improvements` 仍可能局部漂移为英文。修复后，前端在启动时发送语言，后端将其纳入会话与幂等指纹，所有后续 AI 调用均读取该会话语言。结构合法但叙述语言错误的回答会触发一次修复，且首次与修复 Prompt 都包含相同的强制语言指令。

旧会话 `3` 的历史英文反馈不会被静默改写。真实百炼新会话 `5` 已验证首题和全部反馈字段均为中文。

## AI/RULE/MIXED 来源标识

- `ExecutionMode`：会话级别（AI 或 RULE），规则降级后永久切换
- `EvaluationSource`：每轮回答记录级别（AI 或 RULE）
- 报告 `evaluationSource`：AI（全 AI）、RULE（全 RULE）、MIXED（混合）
- 规则反馈标记 `RULE`，使用五维量表但不展示为 AI 分析

## 同意、隐私、配额

- 政策版本：v1.2.0（`application.yml` + `application-test.yml` + `ai.ts`）
- 面试始终要求 `RESUME`、`INTERVIEW_ANSWER`；选择岗位时额外要求 `JOB_DESCRIPTION`
- 授权页提交完整 v1.2.0 task scopes 和 data categories，旧的不完整授权会提示重新授权
- 同意缺失/撤回 → `AI_ACTION_REQUIRED`（`reauthorizationRequired=true`）
- 前端 403 响应 → 跳转 `/ai-consent?redirect=/interviews`
- AI 失败面板含重新授权链接
- 每日配额 60 次（`app.ai.quota.INTERVIEW_COACH=60`）
- 简历脱敏：邮箱→[EMAIL]、电话→[PHONE]、URL→[URL]、证件号→[ID]
- `[UNTRUSTED_USER_DATA]` 标记防止 Prompt 注入

## 幂等、并发与失败恢复

### 两阶段事务

1. TX1：短事务校验（所有权/状态/同意/配额/幂等）+ 创建 PROCESSING attempt
2. 事务外：AI Provider 调用
3. TX2：短事务重新锁定会话 + 确认状态 + 保存结果

### 幂等规则

- `start()`：相同键 + 相同指纹 → 返回已有结果；不同指纹 → CONFLICT
- `answer()`：相同键 + 相同指纹 → 返回已有结果；不同指纹 → CONFLICT
- 指纹使用 SHA-256；启动指纹覆盖用户、来源、平台版本/外部简历文本、岗位、模式和目标题数，回答指纹覆盖会话、轮次和回答

### 并发安全

- `PESSIMISTIC_WRITE` 悲观锁保护会话
- 非 `AWAITING_ANSWER` 状态的回答请求 → CONFLICT

### 失败恢复

- AI 失败 → `AI_ACTION_REQUIRED` + `AiFailureInfo`
- 同意/配额失败 → `AI_ACTION_REQUIRED`（`reauthorizationRequired` 区分）
- 用户可重试 AI 或切换规则模式
- GET 状态和后续操作会接管超过 75 秒的陈旧 `PROCESSING` attempt，并标记为可重试失败
- Provider 超时 60s（`read-timeout-seconds=60`）

## 测试结果

### 后端 `mvn test`

```
Tests run: 409, Failures: 0, Errors: 0, Skipped: 2
```

关键测试类：

| 测试类 | 数量 | 说明 |
|--------|------|------|
| InterviewControllerIT | 19 | 完整工作流、输入/幂等键边界、规则降级复用、配额、授权、finish、并发结束门禁、陈旧 PROCESSING 接管 |
| InterviewAssetControllerIT | 3 | 答案资产 CRUD |
| FlywayMigrationIT | 11 | V1-V21 迁移、V18 round 回填、V19 可选 JD、V20 唯一索引、V21 语言列约束 |
| InterviewAiServiceTest | 24 | Prompt、Provider 失败/成功、条件契约、Schema 修复、语言漂移及两次调用语言约束 |
| InterviewContextSanitizerTest | 31 | 外部/平台简历与 JD 脱敏、截断、历史上下文、标记 |
| AiQuotaServiceTest | 1 | 配额拒绝 + 可观测性 |
| 其他 | 320 | 项目其余测试全部通过 |

### 前端

```
npm run build: PASS
i18n guard: 9/9 rules; 36 Vue files / 2 locales
vue-tsc: PASS
vite build: PASS (1744 modules, main JS 593.23 kB)
npx playwright test --workers=1: 53 passed, 6 skipped（默认 mock 回归；本地服务场景显式启用）
```

其中 13 条面试 E2E 覆盖：无岗位确认、有岗位免确认、AI 动态完成、AI 失败重试与规则降级、处理中轮询、完成报告刷新恢复、失败答案保留、答案资产快照、启动幂等键复用、主动结束确认、授权跳转、移动端布局。

### 真实外部依赖验证

- `BailianAiProviderLiveIT`：真实首题与回答评估调用通过。
- 真实百炼会话 `5`：首题、3 条 `improvements`、`suggestedAnswer`、`resumeSuggestions` 和 `expressionSuggestions` 均为中文；两个 attempt 均为 `interview-coach-v11 / SUCCESS / attempt_count=1`。
- 修复部署后运行态：`http://127.0.0.1:8080/actuator/health` 返回 `UP`，`http://127.0.0.1:5173/interviews` 返回 HTTP 200；当前 V21 数据库中的 4 个会话均为 `ZH_CN`。
- `local-services.spec.ts --grep "real multi-round AI interview"`：真实 qwen-plus 四轮面试通过，生成报告并保存最终回答资产，`1 passed`（约 1.4 分钟）。
- `local-services.spec.ts --grep "PDF failure"`：渲染服务停止→用户可见失败→重启→重试成功，`1 passed`。
- 历史 `MySql57MigrationLiveIT`：MySQL 5.7.24 上 V19→V20 已通过；门禁代码现已扩展为 V19→V21 和 `output_language` 断言，但本轮机器运行 MySQL 8.0.43，未重跑 5.7 门禁。
- 既有 `intelligent_resume` 库运行于 MySQL 5.7.24，保留 103 用户、72 简历、10 面试和 V19 历史；隔离克隆保留业务数据并迁移到 V20，原库未执行迁移或 blanket `flyway repair`。
- 完整本地浏览器核心旅程按用户要求停止并交由用户手动验收，不在本报告中宣称全量通过。

## 未完成项与残余风险

### 残余风险

1. **旧 MySQL 5.7 原库不做就地升级**：其历史 V18 checksum 与当前 HEAD 不一致，标准启动会被 Flyway 拒绝；当前只支持隔离克隆 baseline 后验证。生产和全新安装继续使用 MySQL 8.4。
2. **无后台定时清理**：系统会在 GET 状态和后续操作时惰性接管超过 75 秒的 `PROCESSING` attempt，但无人再次访问的陈旧记录会继续保留。
3. **岗位简历生成 Provider 契约波动**：真实核心旅程中 `JOB_GENERATION` 曾返回缺少 `skills[]._sources[].materialId` 的草稿并被严格 Schema 拒绝，worker 在 `retryCount=1` 后成功。不得通过猜测 materialId 放宽事实来源约束；需持续监控最终失败率。
4. **前端产物体积警告**：主 JS chunk 约 593 kB，Vite 提示超过 500 kB；不阻断本功能，但后续可按路由拆包。
5. **历史报告不自动翻译**：旧会话中已保存的英文反馈保持原样；修复作用于新会话和后续新评估，避免静默改写审计数据。

## 最终 `git diff --stat`

```
37 files changed, 3088 insertions(+), 302 deletions(-)
```

## 最终 `git status --short`

```
 M (37 modified files)
?? (20 untracked new files，含本报告与独立审查报告)
```

## 回归审查回应（13 项逐项）

### P1 阻断项

| # | 问题 | 修复 | 验证 |
|---|------|------|------|
| 1 | AiConsentView 未提交 INTERVIEW_COACH/INTERVIEW_ANSWER | 授权页统一提交 v1.2.0 完整 scopes/categories，并用 `hasFullAiConsent()` 拒绝旧的不完整授权 | 面试和原有岗位生成授权返回流程均通过 E2E |
| 2 | answer() 未捕获 evaluateAnswer() 异常 | `InterviewService.answer()` Phase 2 的 AI 调用包裹在 try-catch 中，失败时 TX 将会话置为 `AI_ACTION_REQUIRED`、attempt 置为 `FAILED` | 不再卡死在 EVALUATING_ANSWER |
| 3 | lastEvaluation 始终为 null | `buildStateResponse()` 从最新 record 构建 `LastEvaluation`，并返回 `recordId/questionText/answerText` 快照、五维评分和建议答案 | 反馈展示及答案资产保存使用原题/原回答 E2E 通过 |
| 4 | 模型未获得真实简历和 JD | 注入 `ResumeVersionRepository` 和 `JobDescriptionRepository`；`buildFirstQuestionContext()` 加载 `resumeJson` 并经 `sanitizePlatformResume()` 脱敏；`buildEvaluationContext()` 加载 `jdText` 并经 `truncateJdText()` 截断 | 平台简历和 JD 内容实际传入 AI |
| 5 | 每日 60 次配额不生效 | 新增 `InterviewAiAttemptRepository.countByUserIdAndCreatedAtAfter()`；`InterviewService` 新增 `checkInterviewQuota()` 方法统计 `interview_ai_attempt` 表；替换所有 `quotaService.check()` 调用 | 配额按面试 attempt 表统计，每日 60 次 |
| 6 | 回答幂等重放失败 | 幂等检查位于创建 attempt 之前；启动与回答均使用覆盖完整业务输入的 SHA-256 指纹，前端相同失败请求复用同一幂等键 | 后端冲突/重放测试及前端幂等键 E2E 通过 |
| 7 | 旧数据没有回填来源 | `V20__ai_interview_flow.sql` 新增两条 UPDATE：`execution_mode IS NULL → 'RULE'`、`evaluation_source IS NULL → 'RULE'` | 旧记录不会被误算为 AI |
| 8 | Schema 允许关键字段为 null | 关键字段增加 Jakarta Validation；`informationComplete=false` 时强制 `nextQuestion`，完成态允许 `nextQuestion=null` | 畸形响应被拒绝，合法完成响应通过单元测试 |
| 9 | 刷新恢复没有实现 | 会话 ID 保留到用户显式开始新会话；处理中状态退避轮询，完成态刷新自动恢复报告 | 进行中与完成态刷新恢复 E2E 通过 |
| 10 | 构建门禁红色 | 修复 i18n 模板规则与插值兼容；Playwright mock 全部迁移到 `InterviewStateResponse` | 构建通过，完整浏览器回归 59/59 |

### P2 问题

| # | 问题 | 修复 |
|---|------|------|
| 11 | 结束按钮需最小题数且无二次确认 | `canFinish` 改为 `count >= 1`；`finish()` 新增 `window.confirm(t('interview.confirmFinish'))` 二次确认 |
| 12 | 完整上下文重复拼入 Prompt 两次 | `InterviewAiService.generateFirstQuestion()` 和 `evaluateAnswer()` 移除重复的 `+ contextData` 拼接 |
| 13 | provider_request_id 从未落库 | `InterviewAiService.AiInvocation` 显式返回 request ID；`InterviewService` 在 TX2 中写入 attempt，不使用 ThreadLocal |

### 残余项

- 真实百炼中文会话已通过；MySQL 5.7 V19→V21 门禁代码已更新但本轮未重跑；完整浏览器核心旅程由用户手动验收。
- 75 秒陈旧 `PROCESSING` 已支持请求触发的惰性接管，但没有后台定时清理。
- `JOB_GENERATION` 偶发遗漏 `skills[]._sources[].materialId`，当前由严格校验和 worker 重试处理；继续监控最终失败率。
- Vite 主 chunk 仍有超过 500 kB 的体积警告。

## 声明

**未提交、未推送、未创建 PR。** 所有修改保留在工作区。
