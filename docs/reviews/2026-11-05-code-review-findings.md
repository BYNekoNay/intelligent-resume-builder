# 代码疑点清单（注释工程副产品，2026-11-05 / 11-06 持续更新）

> **时效性声明**：本清单是 2026-11-05 全仓注释工程（`29527d0`，374 个文件）与 2026-11-06
> 小白可读升级（`e28faaf`，295 个文件）的副产品。由并行审查体在**读代码写注释**时记录，
> **一律未修改**，仅汇总待人工裁决。引用前必须逐条核实当前状态——缺口清单会随修复而失效
> （docs/README 维护原则第 9 条）。当前累计 86 条（编号至 #87，#78 跳号）：🔴16 / 🟡45 / ⚪25。
>
> 分级：🔴 建议尽快确认（可能影响正确性/安全）；🟡 择期处理；⚪ 死代码/噪音。
> **修复进度**：见文末「修复批次记录」。

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

---

## 第三批：小白可读升级轮新报（2026-11-06，11 路并发注释升级 `e28faaf` 的副产品）

> 与前两批重复的已去重（#2/#15/#18/#32/#35/#55/#57/#58 等不再重列）。
> 两处已当场顺手修的见文末「修复批次记录」。

## 🔴 新增

| # | 位置 | 问题 |
| --- | --- | --- |
| 59 | `MaterialSelectionConfirmationService.MAX_FINAL_MATERIALS=30` vs `app.ai.generation.max-selected-materials:30` | 两处独立来源，配置改动会造成「选材放行、确认拒绝」；与 #78（RuleVersion 双源）同族 |
| 60 | `ExportService.retry:171` | 限次检查处做了 retryCount null 判断，`+1` 处没做，防御口径不一致（修复批 R1-2026-11-06） |
| 61 | `GenerationConfirmView:163` | 幂等键 `confirm-${id}-${Date.now()}` 每次点击新键，刷新后无法去重；应对齐 MaterialSelectionConfirmView 的 localStorage 复用模式（修复批 R1） |

## 🟡 新增

| # | 位置 | 问题 |
| --- | --- | --- |
| 62 | `InterviewRetryService` TX1 | 用 `long[]` 传 5 个标量（-1 占位、opType 0/1 编码），下标顺序强耦合，建议改小结构 |
| 63 | `InterviewRuleService.ruleAnswer` | 复用 AI 失败的 attempt 时保留原 attemptCount，其代数会继续计入每日配额统计（sum(attemptCount)），是否合意待产品确认 |
| 64 | `LocalAiSettingService.delete/publishToRuntime` | 操作全局单份 AiRuntimeOverlay 快照：桌面单用户正确，未来多本地用户会互相覆盖 |
| 65 | `AiQuotaService` | JOB_MATERIAL_SELECTION 与 JOB_GENERATION 共用 jobGeneration 配额键，配置项名易被误解为未配额 |
| 66 | `JobGenerationPromptBuilder` | 「最多 12 条推荐」同时硬编码在 task prompt 文本与 `MAX_RECOMMENDED`，两处需人工同步 |
| 67 | `AtsAiAnalysisService.call:85` | 超时判定靠错误文案字符串匹配（timeout/超时），provider 改文案会退化为 PROVIDER_ERROR |
| 68 | `PdfServiceClient.estimateJsonSize` | `toString().length()*2` 粗估，中文密集时可能高估误拒（已知取舍） |
| 69 | `AuthService.issueNewFamily` | 「签发原因」存进 user_agent 列（双语义），按 UA 解析的桌面/审计会拿到 "register"/"login"，建议拆列 |
| 70 | `TokenService.hashToken` | 降级分支（SHA-256 不可用回 Base64）产出与主路径格式不可互比，外部按 64 位 hex 解析的脚本会失配 |
| 71 | `AuthController.refresh` 桌面分支 | 本地 /refresh 直接签发不经限流预算，与在线版口径不一致，且无契约测试覆盖 |
| 72 | `JdKeywordParser` | 教育关键词大小写敏感 contains，而关键词匹配大小写不敏感——同一解析器两套口径（历史取舍） |
| 73 | `ParsedKeywordsResponse` @param | 写「保持首次出现顺序」与实际「词典声明顺序」矛盾，@param 未同步 |
| 74 | `AccountPurgeService` | 多实例并发会同时领取同一任务（RUNNING 不落库），依赖语句幂等兜底；单实例无问题，扩多实例前需加分布式锁 |
| 75 | `ScoringService.score` | 结果复用判定用 created_at 与 JD updatedAt 毫秒级比较，同毫秒「评分后改 JD」会误复用旧分（精度边界） |
| 76 | `CommunicationService.saveDraft` | 手写重复模板可见性校验，与 `CommunicationTemplateService.visible` 同一事实两份副本 |
| 77 | `DesktopImportService.apply` | 重新 plan 后预览时 CONFLICT、apply 时变 SKIP 的记录，用户 resolutions 被 SKIP 静默忽略，无提示 |
| 79 | `GenerationWorkbenchView:234` | 幂等键 `Date.now()+Math.random()` 本地生成，未用 `newIdempotencyKey()`，风格不一致（功能等价） |
| 80 | AI 任务轮询三套实现 | stores/aiTask.ts（1s/2s/4s/5s）、api/ai.ts waitForAiTaskResult（恒 2s）、api/materialGeneration.ts waitForTask（1s/2s/4s 后恒 2s），节奏不一致未复用 useTaskPolling |
| 81 | `stores/aiTask.ts needsRecovery()` | 命名反直觉：非终态任务也返回 true（语义是「需要继续跟进」） |
| 82 | `InterviewController.answer` | 为路由 AI/规则分支先调一次带写副作用的 getState（陈旧接管），双保险但每次作答多一次加锁读 |

## ⚪ 新增

| # | 位置 | 问题 |
| --- | --- | --- |
| 83 | `InterviewAiService.generateFirstQuestion(String)` | 单参重载（默认 EN）已无调用方，仅兼容保留 |
| 84 | `InterviewAssetsView` | 新增/编辑后只改本地数组不重拉列表，新条目可能不符当前筛选却仍显示（已知取舍） |
| 85 | `InterviewHistoryView:27` | `filteredSessions` 是恒等 computed，无实际过滤（修复批 R1 清理） |
| 86 | `DraftSectionReview.vue` | scoped style 两套（写死旧配色 + CSS 变量版整体覆盖），前一套死代码 |
| 87 | `AiPreviewDialog.vue` | 顶部 HTML 注释与 defineProps 上方 JSDoc 内容重复 |

---

## 修复批次记录

### R1（2026-11-06，已完成，提交含本清单更新）

| 项 | 结果 |
| --- | --- |
| #2 templateCode 500 | ✅ `@NotNull` 补上，`ExportControllerIT` 先红（500≠400）后绿 12/12 |
| #5 isCurrentTask NPE | ✅ null → return false 双层兜底，新增 `AtsResultStateServiceTest` 3/3（先红：NPE） |
| #60 retry_count 防御口径 | ✅ 归一为 currentRetryCount；DB 列 NOT NULL DEFAULT 0（V1），null 生产不可达，纯防御；`ExportServiceRetryTest` 5/5（先红：NPE at :192） |
| #44 currentUserId 字面量 | ✅ 19 处控制器替换为 `CURRENT_USER_ID_ATTRIBUTE` 常量，等价替换，全仓字面量清零 |
| #45 5MB 文案脱钩 | ✅ 动态引用实际配置值（新增 formatBytes 辅助），`ResumeImportServiceTest` 15/15（先红：文案不随配置变） |
| #46 PDF 页数检查时机 | ⚠️ **评估后不做流式，留技术债注释**：PDFBox 2.0.32 无 3.x 延迟加载 API；getNumberOfPages 本身要解析 page tree；file.getBytes() 已物化。补页数超限测试覆盖 |
| #41/#42 InterviewAssetsView | ✅ Promise 各自兜底 + 用户可见错误提示；materialIds 渲染空值安全 |
| #43 ResumeListView remove | ✅ 对齐 create() 的错误处理模式 |
| #61 GenerationConfirmView 幂等键 | ✅ 按任务 id 存 localStorage 复用，对齐 MaterialSelectionConfirmView |
| #85 filteredSessions + 乱码字符 | ✅ 恒等 computed 已清理；**乱码字符核查为误报**（合法 UTF-8，`·` 为正当间隔符）——纠正此前队友的误报 |

- 回归：mvn test **1223 项 0 失败**（+6 个新门禁测试）；前端 build + i18n/draft-fields/bundle-budget 门禁全过。
- 当场顺手修（注释升级轮，`e28faaf`）：#35 SecretProtector 悬空链接改为 LocalAiCredentialStore、
  AiTaskService create() 步骤注释与代码顺序矛盾已统一、RemoteImage eager prop 反义注释已改正。
