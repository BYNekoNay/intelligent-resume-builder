# 代码疑点清单（注释工程副产品，2026-11-05）

> **时效性声明**：本清单是 2026-11-05 全仓注释工程（`29527d0`，374 个文件）的副产品。
> 由 5 个并行审查体在**读代码写注释**时记录，**一律未修改**，仅汇总待人工裁决。
> 引用前必须逐条核实当前状态——缺口清单会随修复而失效（docs/README 维护原则第 9 条）。
>
> 分级：🔴 建议尽快确认（可能影响正确性/安全）；🟡 择期处理；⚪ 死代码/噪音。

## 🔴 建议尽快确认

| # | 位置 | 问题 |
| --- | --- | --- |
| 1 | `AtsAiPromptBuilder.sanitize` | 对字符串**只截断不打码**：简历正文里手写的手机号/邮箱会原样进模型。目前三处脱敏口径各不相同（素材 Sanitizer 正则键名+值打码 / Communication 精确键名+值打码 / ATS 精确键名+无打码），需统一 |
| 2 | `ExportService.create` + `CreateExportRequest.templateCode` | `templateCode` 无 `@NotNull`，`@Pattern` 允许 null；`SUPPORTED.contains(null)` 对 `Set.of` 抛 NPE → 缺字段的请求 500 而非 400 |
| 3 | `AiTaskController`（无 Idempotency-Key 时） | 自动生成随机 UUID = 每次网络重试都是新任务 → 重复 AI 调用与重复计费；应要求客户端必传 |
| 4 | `AiTaskController` 大小校验 | 只覆盖 `input`；顶层字段 `jdText` 不受 `app.ai.max-input-json-bytes` 约束 |
| 5 | `AtsResultStateService.isCurrentTask`（132 行） | `longValueOf(...).longValue() == taskId` 在 null 时 NPE；`markFallback` 挡了 null，`markCompleted` 没挡 |
| 6 | `InterviewService`/`InterviewController.answer` | 作答前先调一次会**写库**的 `getState`（陈旧接管带行锁），且"读模式→分支"与 service 二次校验之间存在 TOCTOU 窗口 |
| 7 | `JobGenerationSchemaValidator` | 硬编码 `CURRENT_VERSION="v1.0.0"` 与配置 `app.ai.generation.schema-version` 双源，只改配置会让所有生成任务判 400 |

## 🟡 择期处理

| # | 位置 | 问题 |
| --- | --- | --- |
| 8 | `ConfirmationService.confirm` 重放分支 | 已 CONFIRMED 时返回空 `rejectedPaths`/`newMaterialIds`，与首次响应不等价；`idempotencyKey` 未参与"同键同结果" |
| 9 | ATS_ANALYSIS 重试豁免被收口抵消 | `AiTaskService.retry` 豁免 maxRetries，但 `claimBatch` 仍按 maxAttempts(3) 判 FAILED |
| 10 | `AiQuotaService.check` 与任务落库非原子 | 并发可同时通过配额检查（软上限） |
| 11 | `MaterialSelectionConfirmationService` 等 | 30 条上限三处硬编码（46/105 行 + 配置），改配置不同步 |
| 12 | `MaterialSelector.select` 的 `userId` 未使用 | 越权防护完全依赖调用方传已过滤列表；`unselectedReasons` 无生产读取方 |
| 13 | `ResumeParserViewRenderer` | `links` 渲染两遍（页头 + ParserViewSections）；basics 恒 itemCount=1 |
| 14 | `InterviewRuleEngine.ruleScore` | 四项之和上限 80，`min(100,…)` 永不触发，注释误导 |
| 15 | `InterviewReportService` | 五维均值按记录总数除，缺维度轮次按 0 参与平均，拉低维度分 |
| 16 | `ApplicationStatsCalculator` | `interviewingOrFurther` 实际从 SCREENING 起算，转化率口径与命名不符 |
| 17 | `ResumeService.create` vs `ResumeVersionService.createVersion` | 首版本 template_code 为 NULL，靠读取时惰性回填 |
| 18 | `JavaResumePdfRenderer.draw` | 不绘制 volunteering/awards/publications/courses/customSections，桌面 PDF 覆盖面与在线不一致 |
| 19 | `ExportService.retry` | 人工重试与 worker 领取共用 `retry_count`，两种上限互相消耗 |
| 20 | `InterviewContextSanitizer.sanitizeText` | 按 UTF-16 char 截断，可能切断代理对（emoji 半字符） |
| 21 | `useDraftReview.ts:167` vs `ResumePaper.vue` | 志愿经历字段名两侧不一致（position/summary vs role/description），编辑写回不显示 |
| 22 | `BackupPanel.vue` 恢复失败 | 错误信息渲染在弹窗外，用户只看到遮罩看不到原因 |
| 23 | `DraftContentFields.vue` | 用户真实填写"未填写"三个字会被哨兵逻辑清空 |
| 24 | `i18n/index.ts setLocale` | `localStorage.setItem` 未包 try/catch，隐私模式下语言切换半途而废 |
| 25 | `MaterialPromptTextBudget` | contentJson 占满额度时 sourceText 置空且不加 `[truncated]`，模型无法区分"本来没有"与"被裁掉" |
| 26 | `BailianAiProvider.invokeModelOnce` | 失败路径 errorCode 恒 null，`isQuotaExhausted(errorCode)` 永远 false（目前由 category 兜底，等价但意图分裂） |
| 27 | `Interview INITIAL_QUESTION` | round_no 为 null，而唯一约束对 NULL 不去重，首题并发重复只靠幂等键拦截 |

## ⚪ 死代码 / 噪音

| # | 位置 | 问题 |
| --- | --- | --- |
| 28 | `JobMaterialSelectionService` | ~~死注入×2 + 死方法~~ **已修**（`c41e824`） |
| 29 | `ResumeJsonNormalizer.INDEX_PATTERN` | 声明后未使用 |
| 30 | `DraftCommitService.commit:172` | `Resume target` 赋值后未使用，176 行重复查询 |
| 31 | 无引用 DTO×4 | `AnswerInterviewResponse`、`InterviewFeedback`、`StartInterviewResponse`、`InterviewCoachContext` |
| 32 | `UnselectedMaterialEntry` / `JobGenerationDraftResponse` | 无构造点，仅形状说明；`result_json.unselected` 实际是 CareerMaterial 列表，形状不符 |
| 33 | `RemoteImage.vue aspectRatio` | 死 prop，注释与行为不符 |
| 34 | `SelectMaterialsRequest` | `NotNull` import 未使用，`@Valid` 无效果 |
| 35 | `SecretProtector` javadoc | `{@link DpapiCredentialStore}` 悬空链接 |
| 36 | `ApplicationRecord` / `ResumeService` | Javadoc 位置在注解后 / import 前，javadoc 工具不识别 |
| 37 | `ResumeSourceType.AI_OPTIMIZED/MATERIAL_CUSTOMIZED` | 主代码无写入点 |
| 38 | `InterviewAssetSectionRepository.findByUserIdAndSectionKey…` | 声明无调用 |
| 39 | `DatabaseTaskWorker.poll` | 一个 try 包两个分组，HEAVY 异常会跳过本轮 LIGHT |
| 40 | `InterviewAiAttempt` 首题并发 | 见 #27；`useResumeEditorDraft.clearFor` 共用定时器可能丢一次防抖写入 |

---

## 已确认不是问题的（避免复查时重查）

- `CareerMaterialAiSnapshotSanitizer` 副本带 `userId`，但 `snapshot()` 只取 6 字段，**不进 prompt**；
- `30 - forced.size()` 不会越界：上游强制 `forced ≤ 30`；
- `MaterialPromptTextBudget.clip()` 逐条 1:1 映射、绝不丢条目 —— `buildDataSection` 的 `subList` 三段切分因此安全（但改 clip 必须同改那里）。
