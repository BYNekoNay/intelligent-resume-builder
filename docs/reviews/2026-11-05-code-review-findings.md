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

---

## 第二批：收尾轮发现（2026-11-05 晚，5 路并发复查全仓后追加）

> 复查覆盖 auth/common/config/system、careermaterial/jobdescription/imports、communication/scoring/desktop、
> web views/router/stores/api/types 全部文件。大部分文件已被首批覆盖，本轮新发现如下。

## 🔴 建议尽快确认

| # | 位置 | 问题 |
| --- | --- | --- |
| 41 | `InterviewAssetsView.vue:155-161` | `onMounted` 的 `Promise.all` 只有 `listMaterials` 带 `.catch`；`listJobs()` 失败会使整个 Promise reject（unhandled rejection），岗位下拉为空且无任何错误提示 |
| 42 | `InterviewAssetsView.vue:219` | 模板 `asset.materialIds.length` 直接访问；script 侧（:97）用了 `?? []`，说明该字段可空，列表渲染存在空值风险 |
| 43 | `ResumeListView.vue:44-48` | `remove()` 的 `deleteResume`/`store.load()` 无 try/catch，删除失败无用户提示且 unhandled rejection，与同文件 `create()` 的错误处理不一致 |
| 44 | `AuthController.java:230` | 用字面量 `request.getAttribute("currentUserId")` 取用户 id，而 `JwtAuthenticationFilter` 的常量注释声称它是唯一入口——两处靠字符串约定同步，存在漂移风险 |
| 45 | `ResumeImportService.parse`（imports/service:104） | 报错文案硬编码 `"文件不能超过 5 MB"`，但上限来自可配置项 `app.resume-import.max-bytes`，改配置后文案失真 |
| 46 | `ResumeImportService.extractPdf`（同文件 :163） | 页数上限检查发生在 `PDDocument.load(file.getBytes())` **之后**，超页 PDF 会先被完整载入内存才被拒，页数上限起不到省内存作用 |

## 🟡 择期处理

| # | 位置 | 问题 |
| --- | --- | --- |
| 47 | `api/export.ts:10` | `ExportTaskStatus` 含 `'UNKNOWN'` 值，未在后端导出状态枚举中确认其来源 |
| 48 | `stores/auth.ts:37-38` | `initialize()` 先置 `initialized=true` 再发请求，靠 `initialized && error==='NETWORK'` 组合表达可重试，逻辑成立但较绕 |
| 49 | `JobDescriptionController.list` | 按 `updatedAt` 单键倒序、无 id 兜底，同毫秒更新时次序不保证稳定（不分页暂无翻页问题） |
| 50 | `CommunicationController` / `ScoringController` | 各有一份逐字相同的 `currentUserId`/`traceId` 私有 helper，可下沉公共层（重构建议） |
| 51 | `DesktopImportPlanner.REASON_VERSION_NO_OCCUPIED` | 命名应为 `NOT_OCCUPIED`（常量名语法瑕疵，无行为影响） |

## ⚪ 死代码 / 噪音 / 风格

| # | 位置 | 问题 |
| --- | --- | --- |
| 52 | `WebStaticConfig.java:8-9` | 未使用 import（`Ordered`/`Order`） |
| 53 | `scoring/rule/SkillRule.java:6,9-10` | 未使用 import（`RoundingMode`/`Matcher`/`Pattern`，疑为从 KeywordRule 复制残留） |
| 54 | `SpaFallbackFilter:17` / `WebStaticConfig:24` | 注释硬编码"22 个懒加载路由"，前端路由数变化后会静默过时 |
| 55 | `SystemController.java:63` | 版本号 `"0.5.0"` 硬编码（DTO 已声明"不要用它做版本判断"，属已知取舍） |
| 56 | `CareerMaterialService.validateAchievement:278-283` | `RANGE` 与 `QUALITATIVE` 两个 case 代码完全相同，内层 switch `default` 实际不可达（冗余无害） |
| 57 | `JobDescriptionService.parse:158` | `entityManager != null` 判空是为纯单测准备的防御分支，生产上不会为 null（可接受） |
| 58 | `api/materialGeneration.ts:73` / `api/resumeImport.ts:7` | 纯格式问题（headers 缩进不对齐、接口挤成单行） |

## 第二批已当场修复的（注释自身问题，属注释工程职责）

- `ImportColumnBinder.java:86,104` 注释引用已不存在的 `DesktopImportService#prepareValues`/`#jsonTextOf`
  （逻辑已迁至 `ImportColumnBinder#valueFor`，经 `ImportValueMapper.valuesFor` 调入）→ 已改为 `@link #valueFor`；
- `RuleRegistry.weightedTotal` 全仓唯一英文方法 Javadoc → 已译中文（语义不变）。

## 已确认不是问题的（两批汇总，避免复查时重查）

- `JobDescriptionService.parse` 的"不持锁解析、写回前取 PESSIMISTIC_WRITE 行锁"与 `update` 的
  "title 空白跳过 vs sourceText 空串清空"不对称均为刻意设计，注释已如实记录；

- `CareerMaterialAiSnapshotSanitizer` 副本带 `userId`，但 `snapshot()` 只取 6 字段，**不进 prompt**；
- `30 - forced.size()` 不会越界：上游强制 `forced ≤ 30`；
- `MaterialPromptTextBudget.clip()` 逐条 1:1 映射、绝不丢条目 —— `buildDataSection` 的 `subList` 三段切分因此安全（但改 clip 必须同改那里）。
