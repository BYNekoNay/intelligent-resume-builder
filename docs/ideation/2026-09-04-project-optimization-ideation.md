---
date: 2026-09-04
topic: project-optimization-and-error-audit
focus: 当前分支的可复现错误、已闭环缺陷与可扩展优化方向
mode: repo-grounded
---

# Ideation: 项目优化与错误审计

## Grounding Context

本审计针对分支 `codex/resume-loop-enhancements` 的当前工作树，保留既有未提交的职业资料证据质量修复，不覆盖或重置它们。

验证依据包括：后端定向测试（`CareerMaterialServiceTest`、`JobMaterialSelectionServiceTest`、`InterviewHistoryServiceTest`、`ApplicationServiceTest`）通过；Web `npm run build` 通过；PDF 服务 16 项测试通过；内置浏览器完成 `/register` 注册临时本地账号并自动进入 `/career-materials`，空资料状态正常，未启动 AI 请求。此前完整后端验证记录为 560 通过、0 失败、0 错误、3 个环境门控跳过。

### 已闭环的历史问题

旧诊断计划 `docs/plans/2026-09-01-001-optimization-diagnosis.md` 仍保留当时的“待做”描述，当前代码已经闭环的项目如下：

| 项目 | 当前证据 | 结论 |
| --- | --- | --- |
| O-01 PDF 服务 Token 本地注入 | `scripts/Start-LocalValidation.ps1` 注入根 `.env` 的 `PDF_SERVICE_TOKEN`，`README.md` 与测试已同步 | 已闭环 |
| O-02 AI 轮询窗口 | `web/src/composables/useTaskPolling.ts` 默认约 5 分钟，Communication/ATS 已接入 | 已闭环 |
| O-03 百炼敏感日志 | `BailianAiProvider` 不记录响应 body/message，只保留类别、状态、请求标识 | 已闭环 |
| O-04 新功能浏览器覆盖 | 已有版本对比、投递拖拽、面试报告轮次与 PDF 下载用例 | 已闭环，证据边界仍需补一组回归用例 |
| O-05 `BAILIAN_MODELS` 死配置 | 当前只保留实际消费的 `BAILIAN_MODEL` | 已闭环，旧计划已过时 |
| O-06 AI 调用死超时字段 | `AiCallContext` 已移除 per-call `timeoutMs`，统一由 provider 配置控制 | 已闭环 |
| O-08 面试历史 N+1 | `InterviewRecordRepository.findScoresBySessionIdIn...` 批量投影聚合 | 已闭环 |
| O-09 简历详情全量拉取资产 | `ResumeDetailView` 选中章节时将 `sectionKey` 传给后端 | 已闭环；“全部”筛选仍按设计返回全部 |
| O-10 重复轮询实现 | Communication/ATS 已统一使用 `useTaskPolling` | 已闭环 |
| O-11 AI 失败告警分类 | Prometheus 已按 `PROVIDER_4XX`、`PROVIDER_5XX`、超时/连接失败分开告警 | 已闭环 |
| O-12 文档漏记求职闭环能力 | README 与本地验证文档已补充模板库、统计、版本对比和薄弱项练习 | 已闭环 |
| O-13 版本定位串行 N+1 | `ApplicationsView.findResumeByVersionId` 已改为并行查询 | 已闭环 |
| O-14 投递统计控制器测试 | `ApplicationControllerIT` 已覆盖正常聚合、归属隔离和未认证 | 已闭环 |

### 当前仍存在的错误或风险

1. `GET /api/career-materials` 在 `CareerMaterialService.list` 中调用 `findByUserIdOrderByUpdatedAtDesc` 读取完整实体，再在 Java 内存中做类型过滤和 `evidenceReady` 计算。实体包含 `contentJson` 和 `MEDIUMTEXT sourceText`，这抵消了此前轻量 summary projection 的收益。当前数据量小，属于 P2 扩展风险，不是立即故障。
2. `CareerMaterialRepository.search` 只对 `title` 和 `sourceText` 做关键词匹配；`CareerMaterialService.excerpt` 却会从 `contentJson` 的 `skillName`、`outcome` 等结构化字段生成摘要。因此用户搜索“Java”或成果关键词时，若该词只存在结构化 JSON，可能得到错误的零结果。这是当前确认的功能性缺口。
3. `ApplicationService.stats` 已改为只取四列的 projection，但仍一次性读取该用户的全部投递行，在 Java 中聚合；`ApplicationsView` 同时请求投递列表和 stats。数据增长后会产生重复传输和重复计算，属于 P2 性能风险。
4. 新增的职业资料证据边界目前有服务层、控制器层和构建验证，但 `web/e2e` 没有命中 `evidenceReady`、`invalidEvidence` 或 `noEligibleMaterials` 的回归用例。未来 UI 重构可能再次让无证据资料进入可选范围。
5. AI 任务恢复接口目前只查询 `JOB_MATERIAL_SELECTION` 和 `JOB_GENERATION`。系统枚举还包含 `MATERIAL_IMPORT`、`ATS_ANALYSIS`、`COMMUNICATION_GENERATE`、`INTERVIEW_COACH` 等异步任务；这些页面依赖 URL 参数或页面内状态恢复，跨页面/跨设备无法统一找回。当前不是接口崩溃，但属于 P2 的任务可恢复性缺口。若直接放宽后端查询，`HomeView` 会把未知任务类型按生成任务路由，必须先建立任务类型到恢复入口的显式映射。
6. 账号删除存在安全生命周期风险：后端 `DELETE /api/auth/me` 只将用户标记为 `DISABLED`、写入 `deletedAt` 并撤销 refresh session；`JwtAuthenticationFilter` 只解析 access JWT，不查询用户状态。默认 access token 有效期为 3600 秒，因此删除后已签发的 access token 可能在剩余有效期内继续通过 `currentUserId` 访问受保护接口。当前没有对应的集成回归测试。该风险优先级高于一般体验优化，应先明确“删除后立即失效”口径。
7. 账号数据治理边界不完整：前端 `auth.ts` 没有导出/删除 API，`AccountView` 没有删除确认入口；后端没有用户数据导出接口。软删除保留了简历、职业资料、投递和 AI 任务等数据，但没有公开保留期限、恢复窗口或资源清理策略。这是 P2 的隐私与运维治理缺口，不代表当前误删。
8. 简历导入只返回解析文本和最小归一化字段，`originalFileStored` 固定为 `false`；前端进入素材生成时只把纯文本放入 `sessionStorage`，没有导入批次、文件哈希、章节映射或确认后的来源链。当前导入解析测试正常，但后续无法可靠回答“这张资料来自哪一份原始文件”，属于 P2 可追溯性缺口。
9. `JdKeywordParser` 使用平铺词典的 `contains` 匹配，例如 `Java` 可能命中 `JavaScript`，也无法表达同义词、技能族或岗位级别。当前确定性测试和生产词典注入均通过；这是解析质量和后续岗位画像扩展风险，不是当前运行时错误。
10. AI 同意的数据类别没有由统一的任务策略声明。`AiTaskService.requiredCategories` 对 `MATERIAL_IMPORT`、`INTERVIEW_COACH`、`RESUME_OPTIMIZE`、`INLINE_OPTIMIZE`、`ACHIEVEMENT_GUIDANCE` 默认返回空列表；其中 `INLINE_OPTIMIZE` 和 `ACHIEVEMENT_GUIDANCE` 的接口明确接收 `resumeVersionId`、简历正文和可选 JD，`MATERIAL_IMPORT` 又接收原始职业资料文本。`TaskExecutionService` 仅对 `INTERVIEW_COACH` 在 worker 阶段补充要求 `RESUME`、`INTERVIEW_ANSWER`（有 JD 时还要求 `JOB_DESCRIPTION`），上述其他任务走默认空类别分支。这样即使用户只授权任务 scope 而未授权相应数据类别，任务仍可能持久化并发送简历/职业资料；而 INTERVIEW_COACH 还可能先落库再在 worker 阶段失败，形成 P1/P2 隐私治理和功能一致性风险。代码证据：`server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java:62-64,198-204`、`server/src/main/java/com/intelligentresume/ai/worker/TaskExecutionService.java:145-163`、`server/src/main/java/com/intelligentresume/ai/optimize/controller/InlineOptimizeController.java:37-61`。
11. 多个用户列表接口仍直接返回无分页的 `List`，包括简历、JD、投递、面试资产和职业资料列表；只有职业资料搜索接口已有 `Page` 契约。当前数据量小且页面正常，但增长后会带来全量查询、DTO 映射、网络传输和前端重复渲染的 P2 扩展风险。
12. PDF 导出存储实现仍是绑定 `./pdf-output` 的具体本地文件服务。`ExportExpiryService` 已具备过期清理和重试语义，但多实例或容器部署时各实例的本地目录不共享，任务元数据与文件可见性会分裂，属于 P2 部署扩展风险；目前没有 S3/OSS 等共享存储适配器。
13. AI 异步任务的失败消息没有统一的公开契约。`TaskExecutionService` 的选材、生成、ATS 和通信分支会把 `e.getMessage()` 或 provider 返回消息交给 `TaskLeaseService.releaseFailed`，随后由 `AiTaskStatusResponse.errorMessage` 返回前端；`ai_task.error_message` 只有 `VARCHAR(1024)`。部分 provider 路径已返回通用文案，但校验、持久化或未来适配器异常仍可能超过长度或包含内部实现细节。这是尚未复现的 P2 可靠性与信息暴露风险，不是当前页面故障。
14. PDF 导出创建接口没有请求幂等键或确定性结果复用。`POST /api/exports/pdf` 每次都会创建新的 `export_task`；前端只在当前请求未完成时禁用按钮，网络重试、刷新或多端操作仍可为同一简历版本和模板重复排队、渲染和保存文件。现有导出测试覆盖归属、状态和过期，但没有重复请求的契约。这是 P2 的成本、队列吞吐和存储增长风险，不是当前导出错误。
15. 运行配置的来源和校验不够集中。当前后端有 65 处 `@Value` 注入，许多键同时在 `application.yml` 和构造函数中写 fallback；例如 `BAILIAN_READ_TIMEOUT_S` 在 YAML 默认 300 秒，而 `BailianAiProvider` 的 fallback 是 60 秒，`app.resume-import.max-bytes` 只在代码中提供默认值。除生产密钥外，超时、批量、TTL、权重和上限缺少统一的启动范围校验。这是 P2 的部署一致性和可运维性风险，当前默认环境未发现启动故障。
16. 数据库 worker 的领取查询没有兑现注释中的 `SKIP LOCKED`。`AiTaskRepository.claimableTasks` 和 `ExportTaskRepository.claimableTasks` 的实际 SQL 都是 `... FOR UPDATE`，多实例同时领取时可能在行锁上等待；AI 表的 `idx_ai_task_status(status, lease_expires_at)` 也没有覆盖 `ORDER BY id`，而 PDF 表已有 `(status, lease_expires_at, id)`。当前单实例/低队列未见故障，但这是 P2 的队列延迟与锁竞争风险；如果保留旧 MySQL 兼容门槛，不能简单假设所有环境都支持 `SKIP LOCKED`。
17. 简历文件导入的入口大小受 5 MB 限制，但解析资源边界没有统一收紧。PDF 路径调用 `file.getBytes()` 后再使用 PDFBox 2.0.32 的 `PDDocument.load(byte[])`，会把整份输入保留为内存数组，且没有页数、抽取文本长度或解析耗时上限；DOCX 路径使用 POI 5.3.0 的 `XWPFDocument(InputStream)`，库默认已有压缩比、单条目展开量（默认 4 GiB）和条目数（默认 1000）防护，但应用仍没有总展开量、段落数、输出文本长度或耗时上限。`normalizedText` 也没有独立最大长度，主 `application.yml` 没有显式声明 `app.resume-import.max-bytes`。这是可被高膨胀文件或超长文本触发的 P2 资源消耗/可靠性风险，当前正常文件测试未复现故障。
18. AI 提供者路由目前是“第一个支持者”策略。`AiProviderRegistry.route` 只过滤 `supports(type)` 后取 `findFirst()`，没有过滤 `isAvailable()`、优先级、租户/任务级策略或失败后的候选切换；而 `BailianAiProvider.supports` 对所有任务返回 `true`。当前只有一个正式提供者时页面流程可用，但加入 mock、备用 provider 或按任务拆分的 provider 后，Spring 注入顺序会成为隐含路由契约，不可用 provider 也可能先被选中。这是 P2 的 AI 扩展可靠性风险，未在当前单提供者环境复现。
19. 投递流水线的状态机和统计口径没有形成跨运行时的版本化契约。Java `ApplicationService` 维护迁移表，Web `ApplicationsView` 又维护 lanes、终态、迁移表和标签，`application.ts` 还复制状态联合类型；统计代码按 `ApplicationStatus.values()` 与固定的 APPLIED/INTERVIEWING/OFFERED 公式聚合，而 `ApplicationRecord` 只保存当前状态和少量时间字段，没有状态历史。新增筛选、面试轮次、撤回后重新投递或自定义阶段时，容易出现页面可见但后端不允许、统计分母不一致或无法准确计算阶段停留时间的 P2 扩展风险；现有六状态流程测试正常。
20. PDF 服务的健康和容量接口没有表达真实渲染能力。`/health` 直接返回 `UP`，不会验证 Chromium 是否能启动；`browserPool` 只复用一个 browser，但每个请求都直接创建 page，没有并发上限、排队长度、拒绝策略或 drain 状态。当前 API 单 worker 以间接方式限制了请求量，但多 API/PDF 实例或其他内部调用者扩容后，浏览器启动失败可能被 Compose readiness 掩盖，高并发也可能把 Node/Chromium 推向内存耗尽。这是 P2 的部署可靠性和容量扩展风险，当前单实例正常导出未复现。
21. 发布就绪脚本存在复合命令的失败码丢失风险。`scripts/Test-ReleaseReadiness.ps1` 的 `Invoke-CheckedCommand` 只检查命令字符串执行结束时的 `$LASTEXITCODE`，而 PDF 检查写成 `npm run check; npm test`；如果前一个语法检查失败、后一个测试通过，脚本可能仍继续并报告成功。当前 GitHub/Gitee 工作流分别执行两个命令，因此未发现 CI 已被该问题绕过；这是 P2 的本地发布门禁可靠性风险。
22. MySQL 5.7 增量迁移门禁已落后于当前迁移版本。`MySql57MigrationLiveIT` 和 `Invoke-MySql57MigrationGate.ps1` 仍断言 V19→V22、执行 2 个迁移，但仓库当前已包含 V23、V24；默认测试又因 `MYSQL57_LIVE_TEST` 未设置而跳过。启用该门禁时执行数量/最高版本断言会过期，并且 V23/V24 的真实 5.7 兼容性没有被该门禁证明。这是 P1/P2 的升级验证缺口，不等于已确认生产迁移失败。
23. AI 配额观测指标与实际限流口径不一致。`AiQuotaService` 按“用户 + 任务类型 + 当日尝试次数”限制，`AppObservability` 却用 `countByTaskTypeAndCreatedAtAfter` 统计全体用户的任务行数，并将另一条 gauge 命名为 `...limit_per_user`；重试次数、用户维度和任务创建数因此被混在同一张看板里。当前不影响配额判断，但用户数和重试量增长后会误导容量/成本判断，属于 P2 可观测性扩展风险。
24. 面试答案资产的“幂等创建”只停留在应用层。`InterviewAssetService.create` 先按 `(userId, interviewRecordId)` 查询再插入，但 `interview_answer_asset` 没有对应唯一索引，`InterviewAnswerAsset` 也没有 `@Version`；并发重试、双标签页或多 API 实例可能同时通过查询并产生重复资产，更新时则可能发生最后写入覆盖。这是 P2 的并发数据一致性与扩展风险，单请求路径目前正常。
25. 沟通模板使用计数采用非原子读改写。`CommunicationService.saveDraft` 对共享系统模板执行 `getUsageCount() + 1` 后 `save`，`CommunicationTemplate` 没有 `@Version`，多用户并发保存同一模板时会丢失更新；模板列表又按该字段排序，因此扩容后推荐顺序和运营统计会逐渐失真。这是 P2 的共享聚合并发一致性风险，不影响当前单请求保存。
26. AI 任务历史没有明确的保留/归档接缝。`ai_task` 只有 `created_at`/`updated_at`，没有 `expires_at`、归档状态或清理作业；输入快照和结果 JSON 可能包含内联 JD、原始职业资料、简历草稿和 AI 输出，控制器也没有用户级任务删除/清理入口。当前任务查询和恢复正常，但任务量、重试量和个人数据留存会随使用持续增长，属于 P1/P2 的隐私保留与数据库扩展风险。
27. 面试 AI 的陈旧 `PROCESSING` 接管阈值与 provider 超时不一致。`InterviewOperationSupport.PROCESSING_TAKEOVER_SECONDS` 固定为 75 秒，而 `application.yml` 的百炼读取超时默认 300 秒；重复请求或刷新状态时，75 秒后会把仍可能在 provider 中执行的 attempt 标记为失败并允许重试，原请求随后只能丢弃结果。这是 P1/P2 的异步一致性、重复计费和用户体验风险，当前没有真实慢 provider 复现。
28. 新增配额与跟进筛选查询缺少与谓词匹配的组合索引。AI 任务配额按 `(user_id, task_type, created_at)` 统计，但 V1 只有 `idx_ai_task_user(user_id)`；面试配额按 `(user_id, created_at)` 汇总，但 V20 的 `interview_ai_attempt` 没有用户/时间索引；V23 的投递跟进查询按 `user_id` 和 `next_follow_up_at` 过滤，而现有投递索引仍是 `(user_id, updated_at)`。当前数据量小，属于 P2 的查询退化风险。
29. 沟通 AI 的持久化副作用发生在任务租约最终提交之前。`CommunicationAiService.executeTask` 先保存 `communication_draft`，`TaskExecutionService` 随后才调用 `releaseSuccess`，且忽略其表示 stale owner 的返回值；租约过期、worker 重试或多实例接管时，草稿可能已写入但对应 AI 任务结果被丢弃，现有按“完全相同草稿文本”查重也无法防止不同输出的重复记录。这是 P2 的幂等与分布式一致性风险。
30. PDF worker 在文件已写入本地存储后忽略 `releaseSuccess` 的 stale 返回值；租约已被接管时，孤儿 PDF 文件不会被 `ExportExpiryService` 发现和删除，可能持续占用磁盘或对象存储。属于 P2 的异步资源生命周期风险，当前没有注入租约接管复现。
31. `ExportService.get` 调用 `expireIfDue` 后无条件把本地响应改成 `EXPIRED`，没有检查清理是否成功；当文件删除失败或任务被并发更新时，接口可能返回与数据库仍为 `SUCCESS` 不一致的状态。属于 P2 的状态契约风险，当前未注入存储删除失败。
32. 简历版本归档语义没有统一：`ScoringService.score` 只校验父简历归属，不检查 `ResumeVersion.deletedAt`，而 ATS、PDF 导出、投递和沟通路径会拒绝同一个归档版本。用户界面隐藏归档版本，但直接评分接口仍可使用，属于 P2 的跨模块契约风险。
33. 投递编辑页的版本定位只是把串行 N+1 改成并行 N+1：`ApplicationsView.findResumeByVersionId` 会对每份简历分别请求完整版本列表。简历数量增长后会形成请求扇出和重复数据库查询，属于 P2 的前端/接口扩展风险。
34. 账号删除只禁用用户并撤销 refresh session，没有取消或封存排队中的 AI/PDF 任务；AI worker 仅检查当前 AI 同意，PDF worker 直接按版本读取。若用户删除时仍有任务排队，已有授权下的 AI 任务仍可能继续发送数据，属于 P1/P2 的删除后处理与隐私生命周期风险。
35. AI 选材和岗位生成共用的 `CareerMaterialAiSnapshotSanitizer` 目前只处理 ACHIEVEMENT 的指标展示口径；普通资料的 `sourceText` 和 `contentJson` 会原样进入 prompt。职业资料支持高级 JSON 和自由文本，若其中包含邮箱、电话、地址或 URL，便可能违反“联系方式不得发送给模型”的产品契约。这是 P1/P2 的静态隐私风险，未调用真实 provider。
36. AI 选材只限制候选条数（最多 60），岗位生成只限制确认资料条数（最多 30），但没有统一的 prompt 字节/token 预算；单条职业资料允许约 64 KB，多个字段还会在 selection/generation 两条路径重复序列化。资料类型或字段继续扩展后，失败可能从应用层预算校验推迟到 provider 上下文超限，属于 P2 的成本与可靠性扩展风险。
37. Web 预览通过 `useLocale()` 使用中英文栏目文案，而 PDF renderer 在 `classic.js` 中固定 `lang="zh-CN"` 和中文栏目标题。当前中文导出正常，但英文界面或未来增加导出语言时，用户看到的预览与下载文件会出现语义漂移，属于 P2 的跨运行时展示契约风险。
38. 简历版本号通过 `(resume_id, version_no)` 唯一约束将并发保存转成冲突，但 `resume.current_version_id` 没有 `@Version` 或条件更新；`setCurrentVersion`、首版本自动设当前和恢复流程都采用最后一次实体保存。多标签页或多 API 实例同时切换当前版本时，缺少可解释的冲突/编辑代次契约，属于 P2 的并发扩展风险。

浏览器验证工具的限制：环境没有安装 `agent-browser`，本次使用 Codex 内置浏览器完成手工注册和页面 smoke，不能替代可重复的 Playwright CLI 流程。该限制不是产品故障，已同步到 `docs/LOCAL_VALIDATION.md`。

## Topic Axes

- 数据质量与证据边界
- 查询投影、分页与聚合性能
- 浏览器回归与契约验证
- 求职闭环的可执行性
- 职业证据的长期复用与可解释性

## Ranked Ideas

### 1. 为职业资料建立轻量读取模型，并让结构化内容可检索

**Description:** 将资料库列表与生成工作台使用的资料摘要改为专用 read model，只返回 id、类型、标题、偏好、更新时间、摘要和证据状态；同时为结构化字段建立可检索文本或明确的 JSON 搜索策略，使标题、来源原文、技能名、成果和结构化描述的搜索语义一致。保留详情接口读取完整 JSON。

**Axis:** 查询投影、分页与聚合性能；数据质量与证据边界

**Basis:** direct: `server/src/main/java/com/intelligentresume/careermaterial/service/CareerMaterialService.java` 的 `list` 当前读取完整 `CareerMaterial` 后再映射；`server/src/main/java/com/intelligentresume/careermaterial/repository/CareerMaterialRepository.java` 的 `search` 只匹配 `title` 与 `sourceText`；同一服务的 `excerpt` 已读取 `contentJson` 中的结构化字段。

**Rationale:** 这是唯一同时解决真实功能缺口和增长型性能风险的候选。用户能找到“资料里写过什么”，生成工作台也不必为列表传输长文本；后续证据图谱、技能标准化等能力可以复用这个稳定的摘要边界。

**Downsides:** 需要决定证据状态是否持久化、如何兼容历史 JSON，以及为结构化搜索选择数据库 JSON 查询或派生索引字段；迁移和查询测试成本中等。

**Confidence:** 95%

**Complexity:** Medium

### 2. 把投递统计升级为数据库聚合，并按视图需要拆分缓存边界

**Description:** 将状态计数、转化率和阶段时长改为数据库聚合 projection，避免 stats 接口把用户全部投递行带到 Java；前端对筛选列表与全局统计建立清晰的刷新策略，编辑或拖拽后只刷新受影响的统计数据。

**Axis:** 查询投影、分页与聚合性能

**Basis:** direct: `ApplicationRecordRepository.findStatsByUserId` 已经只选四列，但返回用户全部记录；`ApplicationService.stats` 仍按行计算；`web/src/views/ApplicationsView.vue:94-100` 同时请求列表和 stats。

**Rationale:** 当前修复已经减少了长文本传输，下一步应消除全量行和重复请求，避免投递数量增长后首页/投递看板变慢。

**Downsides:** SQL 日期差和空值口径需要同时兼容 MySQL 与 H2，测试要覆盖时区、历史空时间和状态分母。

**Confidence:** 91%

**Complexity:** Medium

### 15. 建立类型化运行配置与启动校验接缝

**Description:** 按领域把 AI、PDF、认证、导入和评分参数收敛为 `@ConfigurationProperties`，每个配置对象只保留一个默认来源；用 Bean Validation 或显式启动检查验证正数、范围、权重和相互关系，并在 health/info 中只输出非敏感的生效版本与关键开关。环境变量只覆盖配置，不再复制业务默认值。

**Axis:** 部署扩展性；可运维性与契约验证

**Basis:** direct: `rg` 统计当前 Java 源码有 65 处 `@Value`；`application.yml` 的 `app.ai.bailian.read-timeout-seconds` 默认 300，而 `BailianAiProvider` 的构造函数 fallback 为 60；`ResumeImportService` 使用的 `app.resume-import.max-bytes` 没有在主 YAML 中声明。现有 `ProductionConfigurationValidator` 主要保护生产密钥和 Cookie，没有覆盖业务参数范围。

**Rationale:** 配置是部署者使用的接口。集中、类型化的配置能让新环境、第二个 provider 或独立 worker 复用同一校验规则，避免“能启动但超时/配额/大小口径不同”；测试也能在应用启动边界验证配置，而不是等到请求或 worker 才暴露问题。

**Downsides:** 迁移 65 个注入点需要兼容现有环境变量名，并区分可热调整参数与必须重启参数；不应把敏感值写入 actuator 或日志。

**Confidence:** 90%

**Complexity:** Medium

### 16. 建立可观测、可移植的数据库队列领取策略

**Description:** 把 AI 与 PDF worker 的领取动作收敛到明确的队列接缝：在 MySQL 8 目标环境使用经过压测的 `SKIP LOCKED`/短事务方案，或在需要旧版本兼容时使用原子状态更新、分片游标等替代实现；统一领取批次、锁等待、租约过期和 backlog 指标，并为 AI 队列补齐与排序一致的索引。

**Axis:** 异步任务可靠性；多实例部署与数据库扩展

**Basis:** direct: 两个 repository 的注释都声明 `FOR UPDATE SKIP LOCKED`，但 SQL 文本只有 `FOR UPDATE`；`V1__m1_m2_init.sql` 的 AI 索引是 `(status, lease_expires_at)`，`V16__export_task_leases.sql` 的 PDF 索引是 `(status, lease_expires_at, id)`。项目生产基线记录为 MySQL 8.x，同时保留历史 MySQL 5.7 迁移门禁，数据库能力需要显式定版。

**Rationale:** 领取策略是 worker 集群的外部接口，锁等待会直接转化为任务延迟。一个小而明确的接缝能让 AI/PDF 两类任务共享并发测试、指标和故障语义，同时保留单实例本地运行的低成本。

**Downsides:** 需要在真实目标 MySQL 上做锁等待和吞吐基准；`SKIP LOCKED`、索引调整和旧版本兼容不能只凭 H2 通过来判断，且队列策略改变会影响迁移和运维文档。

**Confidence:** 93%

**Complexity:** Medium

### 17. 为简历文件导入建立受限解析管线

**Description:** 把上传大小、PDF 页数/文本量、DOCX ZIP 总展开量/条目数、段落数、归一化输出长度和解析时间预算收敛到一个可配置的导入策略；在超过边界时返回稳定的验证错误，并记录受控指标而不是原始文件内容。保留 PDFBox/POI 的底层安全阈值，同时增加应用级总量和业务语义上限。

**Axis:** 输入安全；资源可靠性与错误契约

**Basis:** direct: `ResumeImportService` 只校验 `MultipartFile.getSize()`；PDF 使用 `PDDocument.load(file.getBytes())`，DOCX 将所有段落文本收集后再生成 `normalizedText`。反编译当前依赖确认 POI 5.3.0 默认 `MIN_INFLATE_RATIO=0.01`、`MAX_ENTRY_SIZE=4 GiB`、`MAX_FILE_COUNT=1000`，但这些不是应用级总输出/耗时限制；`application.yml` 只显式配置了 multipart 5 MB/6 MB，没有 `app.resume-import.max-bytes`。

**Rationale:** 入口字节数不是解析成本的可靠代理。明确的应用级边界可以防止小体积高膨胀 DOCX、超长 PDF 文本和重复内存副本把同步请求拖成高内存/高 CPU 请求，也能让前端得到可解释的“文件过大/内容过长/解析超时”错误。

**Downsides:** 需要选定简历业务可接受的页数、字符数和段落数，过严会拒绝包含扫描/OCR 或复杂排版的真实简历；解析超时的实现必须避免留下未关闭的 PDFBox 临时资源，并补充不同运行时的基准测试。

**Confidence:** 94%

**Complexity:** Medium

### 18. 建立显式的 AI 提供者路由策略

**Description:** 将提供者选择收敛到一个深 module：按任务能力、`isAvailable`、配置优先级和失败可重试性选择 adapter，并记录最终 provider/model/request ID；需要时在同一次任务内按策略切换候选，而不是让调用者感知 `List` 顺序。

**Axis:** AI 扩展；故障转移与可观测性

**Basis:** direct: `AiProviderRegistry.route` 在 `supports(type)` 后直接 `findFirst()`；`AiProvider.isAvailable()` 只由 ATS 的两个分支显式检查，通用选材、生成、通信和面试路径不在 registry 过滤；`BailianAiProvider.supports` 对所有 `AiTaskType` 返回 `true`。

**Rationale:** 第二个 adapter 一旦加入，bean 顺序就会从实现细节变成调用结果。把可用性、优先级和 fallback 放在一个 seam 后，新增 provider 不需要逐个修改 AI caller，也能为失败分类、成本和模型版本建立一致的审计。

**Downsides:** 需要先确定“不可用”与“调用失败”的切换语义、同意/数据驻留/成本约束及是否允许跨 provider 重试；不能为了 fallback 把敏感输入无条件复制到多个 provider。

**Confidence:** 93%

**Complexity:** Low–Medium

### 19. 将投递流水线深化为版本化工作流契约

**Description:** 用一个工作流 module 声明状态、迁移、终态、显示元数据和统计分组，并增加 append-only 状态历史；Java 与 Web 只保留 adapter，统计从历史投影计算，兼容当前六状态默认工作流。

**Axis:** 求职闭环；跨运行时契约与分析扩展

**Basis:** direct: `ApplicationService.TRANSITIONS`、`ApplicationsView.statuses/terminalStatuses/allowedStatuses/statusLabel` 和 `application.ts` 的 `ApplicationStatus` 联合类型是三份状态知识；`ApplicationService.stats` 还对固定状态集合和固定转化公式做聚合，实体没有状态变更历史。

**Rationale:** 自定义阶段、面试轮次、阶段停留时间和历史回溯都会要求“状态如何变化”的事实，而不是只有当前值。一个版本化 seam 能让页面、迁移校验和统计共享同一接口，避免新增阶段时跨多个运行时手工同步。

**Downsides:** 需要设计历史表的幂等与回填、旧六状态数据的迁移，以及统计口径的产品确认；不能在没有明确指标定义前直接把所有状态开放为可配置。

**Confidence:** 90%

**Complexity:** Medium

### 20. 建立 PDF 渲染服务的 readiness 与容量契约

**Description:** 将 PDF renderer 的健康检查、Chromium 生命周期、并发许可、等待队列和优雅关闭收敛到一个深 module；readiness 至少能区分“进程存活”和“可启动浏览器”，render 接口在超出容量时快速返回受控错误，指标暴露 active pages、等待数、渲染耗时和启动失败。

**Axis:** 独立进程部署；资源容量与故障恢复

**Basis:** direct: `pdf-service/src/server.js` 的 `/health` 静态返回 `status: UP`；`createBrowserPool.withPage` 每次 `newPage()`，没有 semaphore、队列或上限；Docker healthcheck 只请求 `/health`，而 Chromium 直到第一次 `/render` 才懒启动。当前 `/render` 有 1 MB JSON 限制和服务令牌，但没有容量/ready 语义。

**Rationale:** 进程健康不等于渲染能力。把 readiness 与容量作为一个 seam 后，API worker、Compose/Kubernetes 和监控可以对“暂时不可用”采取一致的重试/降级策略，避免把浏览器启动故障误判为任务数据故障，也避免无界 page 创建拖垮 renderer。

**Downsides:** 需要选择浏览器实例复用、page 并发和等待队列的目标值，并定义 429/503 与 AI/PDF 任务重试的关系；readiness 探针不能在每次健康检查中无限启动浏览器或泄漏临时资源。

**Confidence:** 95%

**Complexity:** Medium

### 3. 为证据质量边界补一条浏览器级闭环测试

**Description:** 用固定 fixture 覆盖“只有标题/空来源”的历史资料：资料库显示修复提示，生成工作台禁止必须使用/排除操作，下一步按钮保持禁用，服务端选择接口也不会把它放进候选。再覆盖一条有来源原文或结构化内容的资料，确保正常流程不被误伤。

**Axis:** 浏览器回归与契约验证；数据质量与证据边界

**Basis:** direct: 当前分支新增了 `evidenceReady`、`invalidEvidence` 和 `noEligibleMaterials`，但 `rg` 扫描 `web/e2e` 未找到这些契约的回归命中；服务层测试已覆盖 title-only rejection 与 AI candidate exclusion。

**Rationale:** 该测试成本低，能把本轮修复的最重要产品不变量固定在用户可见层，防止后续改 API fixture 或组件逻辑时回归。

**Downsides:** 需要同步 mock 响应的兼容默认值，并维护一个历史脏数据 fixture；它提升的是可靠性而不是直接用户功能。

**Confidence:** 98%

**Complexity:** Low

### 4. 把投递跟进从“字段和筛选”扩展为可执行队列

**Description:** 基于已有 `nextFollowUpAt`、TODAY/OVERDUE 筛选和首页 next-action 卡片，增加统一待办队列、站内提醒和 `.ics` 日历导出；所有动作仍只保存本地数据，不自动向招聘方发送消息。

**Axis:** 求职闭环的可执行性

**Basis:** direct: `ApplicationRecordRepository.findByUserIdAndFollowUp` 已实现 TODAY/OVERDUE 口径，`ApplicationsView` 已维护跟进时间和筛选，但仓库中没有提醒调度、日历导出或独立跟进队列。

**Rationale:** 这会把已有投递看板从“记录状态”推进到“减少漏跟进”，复用现有数据模型，且不引入团队协作范围。

**Downsides:** 需要处理时区、重复提醒、用户关闭提醒和导出文件兼容性；通知能力若扩展到邮件会引入新的隐私和外部依赖边界。

**Confidence:** 88%

**Complexity:** Medium

### 5. 建立职业证据到投递结果的可解释追溯视图

**Description:** 将职业资料、确认后的简历版本、岗位 JD、投递记录、面试答案资产串成只读追溯链，回答“这份简历用了哪些证据”“哪些证据带来了面试反馈”“哪些岗位要求始终缺口”。先做单岗位/单版本视图，再扩展跨投递分析。

**Axis:** 职业证据的长期复用与可解释性

**Basis:** direct: 现有 `resume_material_reference` 快照、岗位定制生成、`ApplicationRecord`、`InterviewAnswerAsset` 和章节/素材关联已经分别存在，但当前 UI 没有统一追溯入口。

**Rationale:** 它把项目的核心差异——“每次投递都有依据”——从数据约束提升为用户可感知的复盘能力，也能为后续技能标准化提供真实反馈数据。

**Downsides:** 关系图数据量和跨版本语义会快速增加，必须坚持只读、按用户归属过滤和快照不可变，不能一开始做成全局图谱。

**Confidence:** 84%

**Complexity:** High

### 6. 建立统一 AI 任务收件箱与显式恢复入口

**Description:** 将所有可恢复的 `AiTask` 按状态、任务类型、所属业务对象和下一步动作返回；每种任务类型声明自己的恢复路由、重试条件和结果确认方式，支持跨页面、刷新和跨设备继续。保留现有用户归属过滤，失败任务提供统一重试入口。

**Axis:** 异步任务可靠性；跨页面恢复

**Basis:** direct: `AiTaskRepository.findContinuationsByUserId` 只包含两个任务类型；`HomeView` 对非选材任务统一拼接生成确认路由，而 Communication、ATS、Interview 和 Material Import 各自维护任务上下文。

**Rationale:** 先统一恢复契约，再逐步接入任务类型，可以避免继续堆叠用户级 `localStorage`、URL 参数和页面内状态。

**Downsides:** 需要为每种任务定义可恢复状态和安全展示字段，不能把 AI 输入快照或 PII 无差别放入首页列表。

**Confidence:** 93%

**Complexity:** Medium

### 7. 补齐账号数据导出、删除和 token 失效闭环

**Description:** 明确软删除/恢复/永久清理策略，增加用户数据导出预览与 JSON 下载、删除前二次认证和确认；删除成功后立即阻断旧 access token，并增加 refresh、access、跨设备会话和受保护接口的回归测试。

**Axis:** 隐私治理；认证安全

**Basis:** direct: `AuthService.deleteAccount` 已有软删除后端动作，但前端无入口；JWT 过滤器不检查 `User.status`，默认 access token TTL 为 3600 秒。

**Rationale:** 这是当前发现中优先级最高的治理风险，先固定删除语义和 token 失效边界，再扩展导出和清理，避免后续数据模型反复迁移。

**Downsides:** 永久删除会牵涉外键、AI 快照、导出文件和审计记录的保留口径，必须先做数据分类和法务/产品决策。

**Confidence:** 97%

**Complexity:** Medium

### 8. 为简历导入建立来源追溯与确认批次

**Description:** 生成导入批次 ID、文件元数据和哈希，保留用户确认前后的章节映射，并在生成的职业资料上记录来源批次；原始文件是否保存由明确的存储策略控制，不默认把文件内容写入长期存储。

**Axis:** 数据可追溯性；隐私边界

**Basis:** direct: `ResumeImportService` 返回 `originalFileStored=false`，前端只把 `extractedText` 写入 `sessionStorage`，没有来源 ID 或 hash。

**Rationale:** 可在不改变“用户确认后才落库”的隐私原则下，补上资料来源和重复导入去重能力。

**Downsides:** 需要定义文件哈希的生命周期、敏感元数据暴露范围和历史导入兼容策略。

**Confidence:** 89%

**Complexity:** Medium

### 9. 将 JD 关键词词典升级为技能本体与解释层

**Description:** 在保留确定性解析的前提下增加词边界、同义词、技能族、岗位族和经验级别；输出命中证据片段与缺口解释，避免只返回平铺字符串。

**Axis:** JD 解析质量；岗位画像

**Basis:** direct: `JdKeywordParser.extractKeywords` 对每个词典项执行大小写不敏感的 `contains`，当前没有 `JavaScript` 等边界反例和同义词层测试。

**Rationale:** 先把“命中了什么、为什么命中”结构化，后续才能把岗位缺口、资料检索和投递结果反馈连接起来。

**Downsides:** 词典维护和多语言语义成本高，必须用确定性 fixture 防止引入不可解释的 AI 结果。

**Confidence:** 86%

**Complexity:** Medium

### 10. 用统一任务策略绑定 AI 数据类别与恢复契约

**Description:** 为每种 `AiTaskType` 声明输入字段、数据类别、可创建入口、执行前检查和恢复路由；创建任务和 worker 执行共用同一策略，先完成类别校验再保存包含原始资料的输入快照。对部分授权、撤回同意和新增任务类型增加契约测试。

**Axis:** 隐私治理；异步任务可靠性

**Basis:** direct: `AiTaskService.requiredCategories` 与 `TaskExecutionService.hasExecutionConsent` 的任务映射不一致；`web/src/api/materialGeneration.ts` 直接将原始资料文本提交为 `MATERIAL_IMPORT`；通用任务接口仍允许该类型。

**Rationale:** 这是最小权限和任务扩展的共同边界。统一策略能避免“创建成功但执行失败”以及“没有类别授权却先持久化原始输入”的隐性分支。

**Downsides:** 需要梳理现有任务快照的字段分类，兼容历史任务，并为每种业务任务定义安全的恢复入口；不应通过简单地把所有任务都授权为全量来规避问题。

**Confidence:** 94%

**Complexity:** Medium

### 11. 统一用户列表分页与轻量 DTO 契约

**Description:** 为简历、JD、投递、面试资产和职业资料列表统一 `PageRequest` 或 cursor 契约；列表只返回 summary projection，详情接口保留完整内容；前端显式处理排序、跟进筛选和分页游标。

**Axis:** 查询投影、分页与聚合性能

**Basis:** direct: `ResumeController`、`JobDescriptionController`、`ApplicationController`、`InterviewAssetController` 和职业资料普通列表均返回 `List`，而职业资料 `/search` 已经采用 `Page`，说明项目已有可复用的分页方向。

**Rationale:** 统一契约可以把数据增长风险控制在 API 边界，避免每个模块各自引入分页参数和大对象列表。

**Downsides:** 需要兼容现有前端的全量排序和本地筛选，可能要为首页增加 capped summary；不能只在控制器外层切片，否则数据库仍会全量读取。

**Confidence:** 92%

**Complexity:** Medium

### 12. 将 PDF 文件生命周期抽象为可替换的共享存储

**Description:** 保留 `storageKey`、校验和、过期清理和幂等删除语义，抽出 `ExportStorage` 端口并提供本地实现与 S3/OSS 实现；任务状态与文件读取通过同一存储抽象完成，明确跨实例读取、失败重试和清理幂等性。

**Axis:** 部署扩展性；异步任务可靠性

**Basis:** direct: `ExportStorageService` 以 `app.pdf.output-dir` 和本机文件系统为唯一实现，`ExportExpiryService` 直接依赖它；当前导出服务没有共享对象存储适配器。

**Rationale:** 这能让容器/多实例部署继续共享导出结果，同时保留本地开发和测试的低成本路径。

**Downsides:** 引入对象存储凭据、下载权限和网络失败处理；必须先补跨实例和过期清理测试，不能只把本地路径替换成 URL。

**Confidence:** 90%

**Complexity:** Medium

### 13. 统一异步失败码、用户文案与内部诊断

**Description:** 为 AI 任务定义稳定的公开失败码和用户可见文案；内部异常只关联 trace ID 和受控诊断记录，统一在持久化边界截断长度并拒绝写入 prompt、SQL 或 provider 原始响应。保留重试性、fallback 和可观测分类作为结构化字段，而不是让页面解析异常字符串。

**Axis:** 异步任务可靠性；错误契约与隐私边界

**Basis:** direct: `TaskExecutionService` 多个分支将 `e.getMessage()` 传给 `TaskLeaseService.releaseFailed`；`AiTask.errorMessage` 和 `AiTaskStatusResponse.errorMessage` 对外共享这一字符串；数据库列和实体长度为 1024。PDF worker 已在自己的 lease 接缝截断到 1000 字符，但 AI task 没有等价的统一策略。

**Rationale:** 失败路径同样是用户流程的一部分。稳定的错误接口能避免超长异常导致二次落库失败，也能避免上游库、数据库或 provider 细节泄漏到 UI；测试可以直接通过任务状态接口验证错误码、重试性和 trace 关联。

**Downsides:** 需要给现有任务错误建立兼容映射，并决定哪些诊断信息只进入日志/指标；不能简单地把所有失败都改成同一条“AI 失败”。

**Confidence:** 88%

**Complexity:** Low

### 14. 为确定性导出建立幂等与结果复用接缝

**Description:** 为导出请求接受用户级幂等键，并以 `userId + resumeVersionId + templateCode` 作为可选的确定性复用索引；相同请求在任务进行中返回已有任务，已有未过期成功文件则直接复用并延长/确认 TTL。重试失败任务仍创建新的执行尝试，但不重复生成相同的成功文件。

**Axis:** 异步任务可靠性；部署与资源扩展

**Basis:** direct: `ExportController.create` 与 `ExportService.create` 没有 `Idempotency-Key` 或既有任务查询；`ExportTask` 没有幂等字段/复合唯一约束；`ResumeDetailView.exportPdf` 只做浏览器内存态的 `runningAction` 防重复点击。ATS、AI 任务和面试操作已有各自的幂等实现，导出语义尚未统一。

**Rationale:** 导出内容来自不可变简历版本和模板，具备天然的确定性缓存条件。一个小而明确的接口能把网络重试、多端重复点击和多实例 worker 的重复工作收敛到同一任务，提高队列和对象存储的杠杆，也让计费/限流策略更可预测。

**Downsides:** 需要定义文件过期后的复用口径、模板版本变化时的缓存失效，以及并发首次创建时的唯一键冲突处理；不能把所有导出都永久缓存。

**Confidence:** 91%

**Complexity:** Medium

### 21. 让面试 AI attempt 的接管阈值与真实执行预算统一

**Description:** 将 provider 读取超时、修复调用预算、attempt 陈旧接管时间和前端等待窗口收敛为一组有校验的配置；接管前区分“仍在外部调用”与“worker 已丢失”，并为原请求完成后的 stale 结果定义明确的丢弃/复用语义。

**Basis:** direct: `InterviewOperationSupport.PROCESSING_TAKEOVER_SECONDS` 为 75 秒，`application.yml` 默认 `app.ai.bailian.read-timeout-seconds` 为 300 秒；`InterviewStartService`、`InterviewAnswerService` 和 retry 入口会在读取状态或重复提交时调用 `isStale` 并改变 attempt 状态。

**Rationale:** 当前一个合法的慢响应可能被用户刷新动作提前判死，产生重复 provider 请求和不可解释的“重试”。明确的时间预算能让状态机的接口真正覆盖外部调用的生命周期，提升 locality，并让超时测试有稳定的 leverage。

**Downsides:** 需要接受较长的挂起状态，或引入 provider request cancellation/后台 worker；不能只把 75 秒机械改成 300 秒，否则数据库连接、前端等待和重试成本会一起延长。

### 22. 为配额与跟进查询建立按访问路径设计的索引

**Description:** 为 AI 任务配额、面试 AI attempt 配额和投递跟进筛选补充 `(user_id, task_type, created_at)`、`(user_id, created_at)`、`(user_id, next_follow_up_at)` 等候选索引，并用真实数据量的 `EXPLAIN`/基准确认排序与范围扫描成本。

**Basis:** direct: `AiQuotaService` 和 `InterviewOperationSupport` 都按用户与自然日统计历史尝试；`ApplicationRecordRepository.findByUserIdAndFollowUp` 按用户和 `nextFollowUpAt` 做 TODAY/OVERDUE 范围筛选。当前迁移只覆盖单列用户索引、`(user_id, updated_at)` 投递索引和面试 attempt 的唯一幂等索引。

**Rationale:** 这是低风险、高杠杆的 schema seam：它把增长型扫描成本留在数据库索引中，而不是每次请求都扩大全历史扫描；同时可以把清理/配额的查询计划纳入可重复验收。

**Downsides:** 每个索引都会增加写入和存储成本，且需要在 MySQL 8.x、历史 5.7 增量门禁和 H2 测试边界之间分别验证；不能凭列名直接假设索引一定改善计划。

### 23. 把沟通 AI 草稿写入与任务结果收敛为同一幂等接缝

**Description:** 让草稿副作用使用稳定的 task id/request fingerprint 作为唯一键，或在一个可验证的结果提交模块中同时完成草稿与任务状态提交；stale owner 不得留下不可追踪的草稿，重试应复用已提交草稿或明确生成新版本。

**Basis:** direct: `CommunicationAiService.executeTask` 在 `releaseSuccess` 之前写 `communication_draft`，当前查重条件是用户、简历版本、JD、类型和完整文本；`TaskExecutionService.executeCommunicationGeneration` 没有检查 `releaseSuccess` 的 boolean 结果。

**Rationale:** 把外部 AI 调用后的持久化副作用放到一个深 module 后，租约、重试和用户可见草稿会共享同一一致性规则，测试可以直接覆盖 stale completion、并发提交和结果复用，而不依赖猜测输出文本是否相同。

**Downsides:** 需要决定草稿是否是任务结果的唯一投影，兼容已有无 task-id 草稿，并处理任务提交与草稿提交跨事务时的补偿/重试策略。

### 24. 让导出文件与租约提交共享补偿清理接缝

**Description:** 将“写入文件”和“任务成功提交”包装为可补偿的生命周期：stale owner、提交失败和渲染失败都必须删除本次生成的临时文件；清理作业还应扫描无任务引用的孤儿 key，而不只扫描 `SUCCESS` 且已过期的任务。

**Basis:** direct: `ExportTaskWorker` 先调用 `storageService.store`，随后忽略 `ExportTaskLeaseService.releaseSuccess` 的 boolean；`ExportExpiryService` 只遍历数据库中已过期的 `SUCCESS` 任务。

**Rationale:** 这是异步文件流程中最小的资源所有权接缝，可以避免租约接管把一次渲染变成永久存储泄漏，并让本地目录和未来对象存储共享同一补偿语义。

**Downsides:** 需要定义临时文件命名/可发现性、孤儿扫描窗口和对象存储删除的最终一致性；不能仅依赖数据库状态清理未知 key。

### 25. 统一简历版本的“可消费”策略

**Description:** 为活动版本、归档版本和父简历软删除建立一个共享 eligibility policy；评分、ATS、导出、沟通、投递和面试上下文要么统一拒绝不可消费版本，要么明确允许“只读历史分析”并在响应中标记历史语义。

**Basis:** direct: `ScoringService.score` 未检查 `ResumeVersion.deletedAt`，而 `AtsService.ownedVersion`、`ExportService.create`、`ApplicationService.validateReferences` 和沟通服务均检查它；归档列表和当前版本切换又有独立规则。

**Rationale:** 把软删除判断集中在一个深模块后，新增消费方不必重新猜测“归档是否可用”，测试可以用同一组版本状态覆盖所有下游。

**Downsides:** 需要产品确认历史评分/导出是否应继续可读，并兼容已有投递与 ATS 历史记录；不应简单地全局禁止历史快照访问。

### 26. 为账号删除增加任务取消与 worker fencing

**Description:** 删除账号时写入不可逆的 deletion epoch 或撤回事件，批量取消未开始的 AI/PDF 任务；worker 在领取和提交前同时校验用户状态/epoch，已在外部调用中的结果只能进入受控丢弃路径，不得继续创建用户可见副作用。

**Basis:** direct: `AuthService.deleteAccount` 只更新 `User.status/deletedAt` 并调用 `logoutAll`；AI worker 的前置检查只验证同意，PDF worker 直接按 `resumeVersionId` 读取并渲染。

**Rationale:** 这把账号删除的隐私承诺延伸到异步系统，避免“接口已成功删除、后台仍在处理旧快照”的时间窗口；同一 fencing 机制也能复用到租约过期和多实例接管。

**Downsides:** 外部 provider 调用通常无法可靠取消，必须定义已发出请求的审计/保留语义，并处理删除事务与 worker 同时领取的竞态。

### 27. 用版本归属映射消除投递编辑页的请求扇出

**Description:** 让投递列表直接返回 `resumeId`/版本摘要，或增加一次性的“版本 ID → 所属简历”批量查询；编辑页不再对每份简历并行拉取完整版本列表。

**Basis:** direct: `ApplicationsView.findResumeByVersionId` 对 `resumes.value` 的每个元素调用 `listVersions(resume.id)`，并在前端逐个加载完整数组后才定位目标版本。

**Rationale:** 一次批量映射能保持编辑体验的低延迟，同时把数据库访问从 O(简历数) 降为 O(1)；它还为后端返回轻量版本摘要留下稳定 seam。

**Downsides:** 需要扩展现有应用 DTO 或增加查询接口，并补跨用户版本 ID、缺失版本和软删除版本的归属语义测试。

## 本轮新增核对与错误状态

| 范围 | 核对结果 | 状态 |
| --- | --- | --- |
| AI 任务恢复 | `AiTaskServiceTest` 11 项通过；现有契约明确只恢复岗位选材/生成，其他异步任务没有统一收件箱 | P2 缺口，未发现当前接口异常 |
| 简历导入 | `ResumeImportServiceTest` 11 项通过，TXT/PDF/DOCX、大小/类型/损坏文件和 PII 边界均正常 | 当前功能正常；来源追溯待增强 |
| JD 解析 | `JdKeywordParserTest` 7 项通过，生产词典集成测试已覆盖 Kafka/微服务/高并发 | 当前配置正常；平铺 contains 的误命中风险待补反例 |
| 账号删除 | 后端软删除和 refresh 撤销代码存在，但无前端入口、导出接口和 access token 失效测试；JWT 过滤器未查询用户状态 | P1 安全生命周期风险，需单独修复/验收 |
| 内置浏览器 | `/account` 显示资料、邮箱、密码和 AI 授权管理，未提供删除账号或导出数据按钮；本地临时账号可正常进入页面 | 当前页面 smoke 正常 |
| AI 同意类别 | 创建任务与 worker 执行使用两套类别映射；`INTERVIEW_COACH` 创建时可不要求实际输入类别，`MATERIAL_IMPORT` 可携带原始职业资料但默认类别为空 | P1/P2 隐私治理与任务一致性风险；全量授权流程暂未暴露故障 |
| 列表契约 | 多个核心资源普通列表仍返回无分页 `List`，职业资料搜索单独使用 `Page` | P2 扩展风险；当前小数据量页面正常 |
| PDF 存储 | `ExportStorageService` 绑定本地 `./pdf-output`，过期清理已存在但没有共享对象存储实现 | P2 多实例/容器部署风险；本地导出当前正常 |
| AI 异步失败消息 | Worker 多个 AI 分支直接持久化异常消息，状态 DTO 原样返回；AI 字段为 `VARCHAR(1024)`，没有统一长度/公开文案接缝 | P2 静态可靠性与信息暴露风险；未通过真实失败响应复现 |
| PDF 导出重复创建 | `POST /api/exports/pdf` 每次创建新任务，没有幂等键、复合唯一约束或成功文件复用；现有测试未覆盖同请求重放 | P2 资源/吞吐扩展风险；当前单次导出功能正常 |
| 运行配置分散 | 65 处 `@Value`、YAML 与代码 fallback 重复维护；百炼读取超时存在 300/60 秒默认差异，导入上限未在主 YAML 声明 | P2 部署一致性风险；当前默认配置可启动 |
| Worker 领取与索引 | AI/PDF 领取 SQL 实际只有 `FOR UPDATE`，AI 排序索引缺少 `id`；注释与实现不一致 | P2 多实例锁竞争/队列延迟风险；未做真实并发压测 |
| 文件导入资源边界 | PDF 通过 `getBytes()` 进入 PDFBox，DOCX 依赖 POI 底层 ZIP 防护但没有应用级总展开量/段落/输出/耗时上限；`normalizedText` 无独立长度上限 | P2 输入资源消耗风险；正常 TXT/PDF/DOCX 和 5 MB 限制通过，未构造恶意高膨胀文件 |
| AI 提供者路由 | Registry 取第一个 `supports=true`，通用路径不检查 `isAvailable`，没有优先级/fallback 契约 | P2 多 provider 扩展风险；当前单 provider 流程正常 |
| 投递流水线契约 | Java/Web 重复维护状态、迁移、终态和标签；统计固定依赖六状态，实体无状态历史 | P2 工作流/分析扩展风险；现有状态机和统计测试通过 |
| PDF readiness 与容量 | `/health` 只返回进程 UP，Chromium 延迟到首个渲染才启动；page 创建无并发/队列上限 | P2 独立进程部署与容量风险；当前单实例导出正常 |
| 发布就绪脚本失败码 | `Test-ReleaseReadiness.ps1` 将 `npm run check; npm test` 作为一个复合命令，封装层只检查末尾 `$LASTEXITCODE`；已用“前一步退出 7、后一步退出 0”复现最终码为 0 | P2 发布门禁可靠性风险；CI 工作流当前分别执行，未发现已绕过 |
| MySQL 5.7 迁移门禁 | `MySql57MigrationLiveIT`/脚本仍写死 V19→V22、2 个迁移；当前 Flyway 已到 V24，默认门禁被环境变量跳过 | P1/P2 升级验证缺口；未宣称生产迁移已失败 |
| AI 配额观测口径 | 限流按每用户尝试次数，quota gauge 却按全用户任务行数；测试只覆盖拒绝计数，未覆盖 gauge 与限流口径一致性 | P2 可观测性扩展风险；当前限流逻辑本身未改变 |
| 面试资产幂等与并发 | 服务层先查后插入，但 `interview_answer_asset` 无 `(user_id, interview_record_id)` 唯一约束，实体无乐观锁 | P2 并发数据一致性风险；单请求创建正常 |
| 模板使用计数 | `CommunicationService.saveDraft` 通过实体读改写 `usage_count`，共享模板无 `@Version` 或原子增量 | P2 共享数据并发一致性风险；单请求保存正常 |
| AI 任务保留 | `ai_task` 无 TTL/归档/清理字段和作业，快照/结果 JSON 可能包含原始资料；无用户级删除入口 | P1/P2 隐私保留与数据库增长风险；当前任务流程正常 |
| 面试 AI 超时接管 | `PROCESSING_TAKEOVER_SECONDS=75`，provider 默认读取超时为 300 秒；重复请求可先把仍在执行的 attempt 标为失败 | P1/P2 异步一致性与重复调用风险；未做真实慢 provider 复现 |
| 配额/跟进索引 | AI 配额和面试配额按用户+时间扫描，投递 TODAY/OVERDUE 按 `next_follow_up_at` 过滤，但现有迁移没有对应组合索引 | P2 查询退化风险；需要真实 MySQL `EXPLAIN` 和数据量基准 |
| 沟通 AI 副作用提交 | `CommunicationAiService` 先保存草稿，随后才提交 AI task 成功，且 stale 返回值未被处理；文本查重不能覆盖不同输出 | P2 分布式幂等与重复草稿风险；未做多 worker 失租约复现 |
| PDF stale 文件清理 | `ExportTaskWorker` 先 `store` 再忽略 `releaseSuccess=false`；清理作业只扫描已过期 `SUCCESS` 任务 | P2 异步资源生命周期风险；未注入租约接管 |
| PDF 过期状态一致性 | `ExportService.get` 不检查 `expireIfDue` 返回值，清理失败时仍把响应对象改成 `EXPIRED` | P2 状态契约风险；未注入删除失败/并发更新 |
| 简历版本消费策略 | 评分允许 `deletedAt` 非空的版本，ATS/导出/投递/沟通拒绝同一版本 | P2 跨模块软删除契约风险；未做跨接口归档回归 |
| 投递版本定位扇出 | `ApplicationsView` 对每份简历并行调用完整版本列表，仍是 O(简历数) 请求 | P2 前端/接口扩展风险；当前小数据量页面正常 |
| 账号删除与异步任务 | 删除只处理用户和 refresh session，AI/PDF worker 没有用户状态/deletion epoch fencing | P1/P2 删除后处理与隐私风险；未执行破坏性删除验证 |
| 跨运行时时间契约 | 后端以无时区 `LocalDateTime` 和服务器自然日计算，Web 以浏览器本地时区解析/展示；投递跟进、每日配额和日期显示可能跨时区漂移 | P2 多时区一致性与扩展风险；未注入非 Asia/Shanghai 时区验证 |
| 版本对比异步加载 | 选择变化同时触发显式加载和 watch 加载，`loadDiffs` 无请求代次；旧版本 JSON 可能覆盖新选择 | P2 前端请求重复与结果一致性风险；未注入延迟复现 |
| 沟通模板异步加载 | 模板列表/预览请求没有按筛选、语言或当前预览对象校验响应；快速筛选或切换模板时旧响应可回写 | P2 模板库一致性与多语言扩展风险；未注入延迟复现 |
| 章节关联资产异步加载 | 简历编辑器和详情页按章节加载资产但没有请求代次/取消机制；快速切换章节时旧资产可能显示在新章节下 | P2 证据追溯 UI 一致性风险；未注入延迟复现 |
| API 错误本地化契约 | 服务端 `ErrorCode` 与大量业务异常消息固定为中文，Web 部分路径直接显示 `response.data.message`；没有 locale key/参数化错误契约 | P2 多语言客户端与错误处理扩展风险；未在英文登录态下触发后端错误 |
| 职业资料并发更新 | `CareerMaterial` 没有 `@Version`，PATCH 请求不带版本/ETag；并发编辑采用最后写入覆盖 | P2 核心事实数据丢失与协作扩展风险；未注入双写并发验证 |
| 用户标识进入日志 | `ExportService` 的 debug 日志直接记录 `userId`；项目隐私约束禁止用户 ID进入日志/指标/报告 | P2 隐私合规与可观测性扩展风险；未对日志采集端做外泄验证 |
| JD 解析结果失效 | 更新 `jdText` 后仍保留旧的解析 JSON/时间/版本，解析写回也没有文本版本校验 | P2 派生数据一致性与解析能力扩展风险；未交错编辑/解析复现 |

本轮没有创建团队、共享数据或子进程，也没有删除任何测试数据。账号删除风险目前依据代码路径和缺失测试判定，尚未通过真实删除操作验证，以避免破坏本地测试会话。

### 下一轮建议的验证门槛

- AI 同意：用仅授权部分数据类别的测试账号验证 `MATERIAL_IMPORT`、`INTERVIEW_COACH` 创建与 worker 执行结果；确认原始输入不会在类别校验前持久化，并为每种任务建立同一份策略映射。
- 列表增长：使用固定的大数据 fixture 对普通列表和 `/search` 比较 SQL 行数、响应大小和首屏耗时，先选一个模块落地分页契约再扩展。
- PDF 多实例：在两个不同输出目录启动两个服务实例，验证任务状态、文件读取和过期清理的失败语义；再评估 S3/OSS 适配器，不把本地路径直接暴露给客户端。
- 异步错误契约：构造超长校验/适配器异常和包含内部细节的失败路径，确认任务仍能稳定落为 `FAILED`，接口只返回稳定失败码、可读文案和 trace ID；重试/fallback 分类不能依赖异常字符串。
- 导出幂等：连续重放相同 `Idempotency-Key` 以及并发提交同一版本/模板，确认只保留一个逻辑任务；进行中请求共享状态，未过期成功文件可复用，失败重试和模板版本变化仍有明确语义。
- 配置一致性：在默认、local-h2、prod 和缺失/非法环境变量场景启动应用，验证生效值只有一个来源，超时/批量/TTL/权重/上限的非法值在启动时失败，并确认 actuator 不泄露密钥。
- Worker 并发：在目标 MySQL 8.x 上启动两个 AI/PDF worker，注入足量 PENDING 任务，测量锁等待、领取吞吐、重复领取和租约接管；若需要兼容 MySQL 5.7，单独验证替代领取方案，不把 H2 结果视为数据库锁语义证据。
- 文件导入边界：构造压缩比/展开量超过应用预算的 DOCX、超多条目/段落的 DOCX、超页数或超文本量的 PDF，以及接近 5 MB 的 TXT；确认请求稳定失败、资源释放、错误文案不泄露路径/库细节，并用耗时/峰值内存基准确定默认上限。POI 底层 ZIP 防护不能替代这些应用级验收。
- AI 提供者路由：用两个 provider adapter 覆盖“一个不可用、两个可用、首选调用失败、任务不支持”四种情况；确认路由不依赖注入顺序，敏感输入不会因 fallback 无条件复制，失败状态保留稳定 provider/model/request-id 诊断。
- 投递流水线：建立 Java/Web 状态集合与迁移表的机器校验；覆盖新增阶段、终态、非法迁移、并发更新、历史回放和阶段时长统计，确认旧六状态数据迁移后页面、接口和统计口径一致。
- PDF readiness：注入 Chromium 启动失败、断开和并发峰值，确认 readiness 不误报、render 在容量耗尽时返回可重试错误、关闭时不接收新任务，并验证 active pages/等待数/失败分类指标与 API 任务重试语义一致。
- 发布门禁：让第一个检查命令失败、第二个检查命令成功，确认 `Test-ReleaseReadiness.ps1` 仍返回失败；将每个检查拆成独立调用或显式收集每一步退出码，并同时校验生产 Compose、registry overlay 和 IP test overlay 的配置。
- 数据库升级：将 MySQL 5.7 门禁的目标版本、迁移数量和断言更新到当前版本，覆盖 V23/V24 的表结构、种子行和幂等重跑；在真实 MySQL 5.7 环境执行，而不是用 H2 结果替代。
- 配额观测：明确“任务创建数”还是“尝试数”以及是否按用户聚合；让 gauge、dashboard 和限流查询使用同一口径，并增加重试、两个用户和跨自然日的指标契约测试。
- 面试资产并发：并发提交同一 `interviewRecordId`，确认最终只有一个资产；更新请求使用版本冲突而不是静默覆盖，并保持 `NULL` 记录 ID 的多资产语义。
- 模板计数并发：并发保存同一系统模板，确认使用计数等于成功保存次数；列表排序和计数接口在高并发下不能依赖丢失更新，且自定义模板仍按用户隔离。
- AI 任务留存：按任务类型定义最小保留期和“待确认任务不可清理”规则，执行归档/清理、索引和批量删除基准；账号删除、撤回授权和任务历史查询必须对快照/结果的留存语义一致。

## 架构深化审查（2026-09-04）

本轮按“深模块”标准做了当前会话内静态扫描，没有创建团队或子进程。架构报告已生成到本机临时文件：
`<architecture-review-temp.html>`。

### 首要候选：AI 任务能力注册表

- **问题**：`TaskExecutionService` 当前约 322 行，在 worker 内集中处理同意、任务类型分派、fallback、结果格式化和心跳；创建阶段的 `AiTaskService.requiredCategories`、配额 `AiQuotaService`、恢复查询 `AiTaskRepository` 与通用入口限制又分别维护同一任务的能力信息。
- **证据**：`TaskExecutionService` 106–124 行是任务分派分支，145–161 行是另一套同意类别分支；`AiTaskService` 198–206 行对多个任务默认空类别；恢复查询只包含 `JOB_MATERIAL_SELECTION` 和 `JOB_GENERATION`。
- **扩展价值**：新增 AI 任务可以在一个内部接缝声明输入投影、数据类别、配额、执行动作、重试/fallback 和恢复路由；创建、worker、重试和首页恢复共享同一策略，提升局部性和测试杠杆。
- **优先级**：Strong，且应先于普通性能优化推进，因为当前已经存在创建/执行同意规则不一致的 P1/P2 风险。

### 第二候选：版本化简历文档契约

- **问题**：章节键、ATS 别名、允许顶层字段和排序规则在 Web、Java 和 PDF 三个运行时重复维护。`web/src/resume/sectionRegistry.ts` 只保证 Web 内部一致；`InterviewAssetService` 再硬编码一份章节集合，`ResumeVersionService` 再硬编码一份 ATS 别名，PDF `classic.js` 通过 `sectionHtml` 自己推导默认顺序。
- **证据**：`JobGenerationSchemaValidator.SUPPORTED_SECTIONS` 当前只覆盖生成子集，未覆盖 Web 支持的 `links`、`awards`、`languages` 等全部章节；`web/src/types/resume.ts` 虽然定义了 `ResumeDocument`，当前只有 `ResumePaper.vue` 导入，`ResumeEditorView.vue` 仍以 `Record<string, any>` 读写。以上不是现有页面故障，但会让新增章节需要同步多个运行时，并增加“编辑器可见但生成/PDF/资产筛选不支持”的契约漂移风险。
- **扩展价值**：建立带版本号的简历文档契约和跨运行时 fixture；各运行时保留自己的渲染实现，但共同消费章节键、顺序归一化和迁移语义。
- **优先级**：Strong，适合作为新增章节、模板和导出能力扩展前的基础设施；不建议一次性把整个 JSON 改造成强类型对象。

### 第二梯队

| 候选 | 当前判断 | 触发条件 |
| --- | --- | --- |
| 统一列表读取模型与分页接缝 | Worth exploring；核心列表大多返回无上限 `List`，投递统计仍按用户全量行在 Java 聚合 | 大数据 fixture 显示响应大小、SQL 行数或首屏耗时明显增长 |
| PDF 导出文件生命周期接缝 | Worth exploring；当前只有本地文件实现，按“一种适配器只是假设接缝、两种才是实际接缝”原则暂不抽象过度 | 多实例/容器部署成为实际目标，或出现跨实例读不到导出文件 |
| 分布式认证限流 | Worth exploring；`RateLimitFilter` 使用进程内 `ConcurrentHashMap`，重启清零，多实例之间不共享桶 | 公开服务扩容到多个 API 副本，或登录/注册攻击需要跨实例统一限流 |

### 新增候选：机器校验的跨运行时 API 契约

- **问题**：Web API 类型、Java DTO/枚举和配置版本主要靠人工同步。`web/src/api/ai.ts` 的 `AiTask.taskType` 联合类型包含服务端 `AiTaskType` 没有的 `EXPORT_PDF`；AI 授权版本在 `web/src/api/ai.ts` 与服务端 `application.yml` 各维护一份；仓库未发现 OpenAPI 或生成客户端产物。
- **证据**：前端 API 层约 73 个导出类型，服务端约 67 组请求/响应 DTO，错误码和任务状态也由页面分别判断；当前构建通过，说明这更像契约漂移的扩展风险，而不是已复现的页面故障。
- **建议**：先建立机器可校验的 API/任务契约（错误码、枚举、版本、分页和异步任务状态），再按稳定范围生成或校验 Web 类型。不要立刻覆盖所有历史 DTO，也不要为了生成而引入团队协作范围。
- **优先级**：P2，适合在对外开放 API、第二个客户端或任务类型继续增加前推进。

报告中的前后结构图只表达候选方向，没有修改源代码。下一步应先选择“AI 任务能力注册表”或“版本化简历文档契约”中的一个进入设计和验收，不在本轮同时启动两个大型重构。

### 架构候选追加审查：提供者路由与投递流水线（2026-09-04）

- 本轮本地单进程扫描确认：`AiProviderRegistry.route` 只按 `supports(type)` 取第一个匹配 adapter，没有应用 `AiProvider.isAvailable()`、配置优先级或 fallback 策略；`BailianAiProvider.supports` 当前对所有任务返回 `true`。单提供者环境没有页面故障，但新增第二个 provider 后，bean/list 顺序会成为隐含 interface，记录为 P2 AI 扩展可靠性风险。
- 同一风险还有执行身份漂移：`JobGenerationService` 在实际调用前后分别执行 `providerRegistry.route(AiTaskType.JOB_GENERATION)`；若路由可用性或优先级在运行期间变化，结果中的 `provider` 记录可能不是实际完成调用的 adapter。建议一次选择 adapter，并将 provider/model/request-id 作为执行结果元数据随任务提交。
- 同一扫描确认投递工作流知识在 `ApplicationService.TRANSITIONS`、`ApplicationsView.vue` 的 lanes/终态/迁移/标签和 `web/src/api/application.ts` 的状态联合类型中重复维护；统计固定依赖六状态与固定转化公式，`ApplicationRecord` 没有 append-only 状态历史。当前六状态流程正常，记录为 P2 跨运行时契约与分析扩展风险。
- 本轮架构候选的可视化报告已生成并打开：`<architecture-review-temp.html>`。报告推荐先深化 provider routing seam，因为改动面小且第二个 provider 一出现就能验证收益。
- 本轮没有修改源代码、没有启动 AI 调用、没有创建团队/子进程，也没有改变数据库或浏览器数据。

### 部署扩展追加审查：PDF readiness 与容量（2026-09-04）

- 静态检查确认 `pdf-service/src/server.js` 的 `/health` 无条件返回 `UP`，而 Chromium 只在第一次 `/render` 时懒启动；Docker Compose healthcheck 因此只能证明 Node 进程在监听，不能证明浏览器可启动或模板可渲染。
- `pdf-service/src/browserPool.js` 复用一个 Chromium，但每个 `withPage` 调用都直接创建新 page，没有并发许可、等待队列、快速拒绝或 drain 状态。当前 API 的单个定时 worker 以间接方式限制了本地流量，不能作为未来多实例部署的容量契约。
- 这是 P2 部署可靠性/容量扩展候选，不是当前单实例导出故障；本轮未制造 Chromium 故障或压力请求。建议先补 readiness、容量和关闭语义测试，再决定是否扩展 PDF 实例。

### 发布门禁编排追加审查（2026-09-04）

- `scripts/Test-ReleaseReadiness.ps1` 的 `Invoke-CheckedCommand` 通过 `Invoke-Expression` 执行字符串，并在返回后只读取一次 `$LASTEXITCODE`。PDF 检查参数是 `npm run check; npm test`，所以它只可靠地反映最后一个 `npm test` 的退出码。
- 无副作用复现：前一步返回 7、后一步返回 0 时，PowerShell 观察到 `first=7, final=0`。因此语法检查失败可能被后续测试成功覆盖，发布脚本仍继续执行。
- GitHub CI 和 `.gitee/workflows/ci.yml` 当前把 `npm run check` 与 `npm test` 分成独立步骤/命令，未据此判定已有 CI 失效；问题集中在本地发布就绪脚本和任何未来复用该复合命令的调用方。
- 建议先用失败码回归测试修复脚本编排，再扩展 registry/IP-test Compose overlay 的静态校验；本轮未修改脚本或业务代码。

### 迁移门禁与配额观测追加审查（2026-09-04）

- 当前 `server/src/main/resources/db/migration` 已有 V1–V24，但 MySQL 5.7 live gate 的方法名、脚本错误文案和断言仍停留在 V19→V22；`mvn test` 默认会跳过它，因为该测试由 `MYSQL57_LIVE_TEST=true` 显式开启。
- `FlywayMigrationIT` 目前验证 V1、V18–V21 的部分结构和幂等约束，没有验证 V22 的 ATS 幂等字段、V23 的新表/字段与 14 条系统模板、V24 的两条英文种子，也不能代替真实 MySQL 5.7 语义。
- `AiQuotaService.check` 使用 `countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter`，而 `AppObservability.registerQuotaLimit` 注册的 `resume_ai_quota_daily_tasks_created` 使用全局 `countByTaskTypeAndCreatedAtAfter`；现有 `AppObservabilityTest` 只断言 counter，不断言这条 gauge 的来源和命名口径。
- 两项都属于可扩展性验证/观测契约缺口。本轮只做静态核对，没有启动 MySQL 5.7、执行真实迁移或修改观测代码。

### 面试资产并发一致性追加审查（2026-09-04）

- `InterviewAssetService.create` 的注释和查询把 `(userId, interviewRecordId)` 当作幂等键；但 V5 建表只有 `idx_interview_asset_user_created`，没有唯一索引。两个并发请求都可能在 `findByUserIdAndInterviewRecordId` 返回空后插入。
- `InterviewAnswerAsset` 继承的 `BaseEntity` 只有 ID、创建时间和更新时间，没有 `@Version`；`update` 会先保存资产，再删除并重建章节/素材关系，缺少并发版本接缝。
- 这不是当前单请求浏览器 smoke 的故障，但在网络重试、双标签页或 API 多实例后会放大为重复资产和最后写入覆盖。验收应覆盖唯一约束冲突的幂等返回、`NULL interviewRecordId` 的合法多资产，以及更新版本冲突。未修改资产代码或数据库迁移。

### 沟通模板计数并发追加审查（2026-09-04）

- `CommunicationService.saveDraft` 在同一事务中读取模板、将 `usageCount` 加一后调用 `save`；V23 的 `communication_template` 没有版本列或原子计数更新。系统模板 `user_id=NULL`，因此这个字段是跨用户共享写入点。
- 两个请求同时读取 `usage_count=10` 都可能写回 11，数据库不会把它们视为冲突。由于 `CommunicationTemplateRepository.search` 用 `usageCount DESC` 排序，这会让热门模板的排序和后续分析低估实际使用量。
- 当前单请求模板保存和既有测试正常；建议使用数据库原子 `usage_count = usage_count + 1`、独立使用事件表或明确的乐观锁策略，并增加并发计数验收。本轮未修改模板代码。

### AI 任务留存与数据增长追加审查（2026-09-04）

- `AiTask`/V1 `ai_task` schema 没有过期时间、归档标志、删除时间或分区/清理索引。仓库内未找到 AI 任务清理 `@Scheduled` 作业、按用户删除方法或任务历史保留配置；现有 `continuations` 只筛选活跃/待确认任务，不会清理终态记录。
- `CreateAiTaskRequest` 的 `input` 会进入 `input_snapshot_json`；`MATERIAL_IMPORT` 前端直接提交 `rawMaterialText`，而任务结果会进入 `result_json`。这些数据在终态后仍随任务行保留，可能与账号删除、AI 授权撤回和数据导出语义不一致。
- 这是静态留存/容量发现，不是当前任务执行故障。建议先按任务类型区分“审计所需元数据”和可删除的原始快照/结果，定义保留期、用户删除传播、批量清理和索引策略，再进行大数据量基准；本轮未删除任务数据或修改业务代码。

### 面试 AI 超时接管追加审查（2026-09-04）

- `InterviewOperationSupport.PROCESSING_TAKEOVER_SECONDS` 固定为 75 秒，`isStale` 只根据 `attempt.updatedAt` 判定；`InterviewStartService` 和 `InterviewAnswerService` 在重复请求/状态读取时会把超过该阈值的 `PROCESSING` attempt 标为 `FAILED` 并允许后续重试。
- 当前 `application.yml` 的 `app.ai.bailian.read-timeout-seconds` 默认是 300 秒，`BailianAiProvider` 的 fallback 是 60 秒；配置本身已经存在来源分裂，且 75 秒接管值没有和实际 provider/repair 预算建立约束。一个合法的慢响应可能仍在外部执行时被用户操作判死，原调用完成后只能因状态变更而丢弃结果，重试还可能再次调用 provider。
- 这是静态 P1/P2 异步一致性与成本风险，不是当前浏览器故障；没有发起真实慢 AI 调用，也没有人为制造重复计费。推荐验收：用可控延迟 provider 覆盖 74/76/超过读取超时的响应、重复 GET/POST、retry 和原请求晚到场景，断言最多一次可见结果、attempt 状态、用户文案和 provider 调用计数。

### 配额与投递跟进索引追加审查（2026-09-04）

- `AiQuotaService` 的查询按 `ai_task.user_id + task_type + created_at` 统计尝试次数；V1 只有 `idx_ai_task_user (user_id)`，任务幂等唯一键的列顺序也不能覆盖 `task_type/created_at` 的范围访问。
- `InterviewOperationSupport.checkInterviewQuota` 按 `interview_ai_attempt.user_id + created_at` 汇总 `attempt_count`；V20 只声明了 `(user_id, idempotency_key)`、`(session_id, operation_type, round_no)` 两个唯一约束，没有用户/创建时间索引。
- `ApplicationRecordRepository.findByUserIdAndFollowUp` 的 TODAY/OVERDUE 分支按 `user_id` 和 `nextFollowUpAt` 做范围过滤并按 `updatedAt` 排序；V4/V23 仍只有 `(user_id, updated_at)`。当前小数据量测试和页面路径正常，这是静态 P2 查询退化风险。推荐验收：在目标 MySQL 版本用接近生产的历史行数执行 `EXPLAIN` 和冷热缓存基准，确认索引选择、范围扫描、排序和写入成本，再决定索引列顺序。

### 沟通 AI 草稿副作用追加审查（2026-09-04）

- `CommunicationAiService.executeTask` 在 provider 返回并通过 schema 校验后立即 `draftRepository.save(...)`；随后 `TaskExecutionService.executeCommunicationGeneration` 才调用 `leaseService.releaseSuccess(...)`，但没有检查返回的 stale/owner 失败结果。
- 因此租约过期或另一个 worker 接管时，草稿写入可能已经提交，而 AI task 的成功结果被丢弃；重试可能生成不同文本，当前“完全相同的草稿文本查找”不是数据库唯一约束，也不能防止不同输出留下多条草稿。该路径与 ATS 分支在 `releaseSuccess` 后才更新状态的做法不一致。
- 这是静态 P2 分布式一致性/幂等风险；没有启动多 worker 或注入租约过期，也没有删除已有草稿。推荐验收：让 lease 在 provider 返回前过期、并发执行相同任务和不同 retry，断言 stale worker 不产生不可归属草稿，成功 task 与草稿一一对应，重试按明确策略复用或生成版本化草稿。

### PDF stale artifact cleanup audit (2026-09-04)

- 静态检查发现 `ExportTaskWorker.processTask` 先调用 `storageService.store(pdfBytes, "pdf")` 写入随机 `storageKey`，随后调用 `leaseService.releaseSuccess(task, stored)`，但忽略其 `false` 返回值。`ExportTaskLeaseService` 在租约已被接管时会丢弃提交，worker 仍记录“completed”。
- `ExportExpiryService` 只扫描数据库中 `SUCCESS` 且已过期的任务；stale worker 写出的 key 没有进入任何 `ExportTask.storageKey`，因此不会被过期清理发现。这个文件会成为无引用的本地/对象存储占用。
- 这是静态 P2 异步资源生命周期风险，不是当前单 worker 导出失败；没有注入租约接管，也没有删除或扫描本地导出文件。推荐验收：在文件写入后、成功提交前强制 owner 失效，断言 `releaseSuccess=false` 时本次文件被删除，重试/接管后没有孤儿 key，清理失败有可观测告警。

### PDF expiry response consistency audit (2026-09-04)

- 静态检查发现 `ExportService.get` 在 `SUCCESS` 任务过期时调用 `expiryService.expireIfDue(...)`，但不检查返回值，随后无条件把当前对象改成 `EXPIRED` 并清空文件字段。
- `ExportExpiryService.deleteAndExpire` 在 `storageService.delete` 失败时明确返回 `false`，保留数据库 `SUCCESS` 以便 scheduler 重试；因此删除失败或并发状态变化时，状态查询可能暂时返回 `EXPIRED`，而数据库仍为 `SUCCESS`，下载接口会再次走自己的过期分支。
- 这是静态 P2 状态契约风险，未注入存储删除失败或并发更新。推荐验收：让删除返回 `false`、让另一个事务先完成状态迁移，断言 GET、数据库状态、下一次 GET 和下载结果一致；若产品选择“先隐藏后重试”，应将该语义显式建模而不是依赖本地对象修改。

### Resume-version eligibility consistency audit (2026-09-04)

- 静态检查发现 `ResumeService.softDelete` 和版本归档都通过 `deletedAt` 隐藏资源；`ScoringService.score` 只检查版本父简历归属，不检查 `version.getDeletedAt()`。
- 同一归档版本在 `AtsService.ownedVersion`、`ExportService.create`、`ApplicationService.validateReferences` 以及沟通服务中会被拒绝；因此活动列表看不到它，但直接规则评分仍能创建新的 `match_result`。这是跨模块软删除/历史快照契约不一致，不是跨用户越权。
- 这是静态 P2 功能契约风险，现有归档/恢复 UI 测试没有覆盖所有下游消费方。推荐验收：归档一个非当前版本后依次调用评分、ATS、导出、投递、沟通和面试上下文，统一断言“拒绝”或“只读历史分析”策略，并覆盖恢复后各接口重新可用。

### Application version lookup fan-out audit (2026-09-04)

- 静态检查发现 `ApplicationsView.findResumeByVersionId` 为 `resumes.value` 中每份简历并行调用 `listVersions(resume.id)`，然后在浏览器内加载完整版本数组并定位目标版本。注释称其消除了 N+1，但实际只是把串行 N+1 改为并行 N+1。
- 这会让一次投递编辑操作产生 O(简历数) HTTP 请求和 O(简历数) 数据库查询；并行只降低了等待时间，不能消除请求扇出，简历和历史版本增长后还可能放大 API 峰值。
- 这是静态 P2 前端/接口扩展风险，当前小数据量页面未观察到错误。推荐验收：构造 100 份简历并测量请求数、响应总字节和首屏/编辑延迟，目标应是应用列表直接携带 `resumeId` 或一次批量 ID 映射，且跨用户、缺失和归档版本均有明确结果。

### Account-deletion async fencing audit (2026-09-04)

- 静态检查发现 `AuthService.deleteAccount` 只设置 `User.status=DISABLED`、`deletedAt` 并撤销 refresh sessions，没有撤回 AI 同意、取消 `ai_task`/`export_task` 或写入 deletion epoch。
- `TaskExecutionService` 在执行前只调用 `hasExecutionConsent`；若删除前最新同意仍为 `GRANTED`，排队 AI 任务仍可继续执行。`ExportTaskWorker` 直接通过 `resumeVersionRepository.findById` 读取版本，也没有检查用户状态或删除代次。当前没有账号删除后的 worker fencing。
- 这是静态 P1/P2 删除后处理与隐私生命周期风险；没有执行真实删除，避免破坏本地测试账号，也没有向 provider/PDF 服务发送请求。推荐验收：创建排队中的 AI/PDF 任务后删除账号，断言任务在领取/提交前被取消或安全失败、不发生 provider/render 调用、旧 access/refresh 立即失效，并覆盖删除与领取同时发生的竞态。

### Latest documentation-only verification (2026-09-04)

- `mvn -q "-Dtest=InterviewAssetServiceTest,FlywayMigrationIT" test` passed with exit code 0; H2 applied and revalidated all V1–V24 migrations, including the V17→V24 upgrade scenarios.
- Related normal-path regression bundle `ExportServiceTest,ScoringServiceTest,ApplicationServiceTest,ResumeVersionServiceTest,AuthServiceTest,TaskExecutionServiceTest` also passed with exit code 0; this confirms baseline behavior only and does not close the new concurrency/failure-injection gaps.
- `git diff --check` reported no whitespace errors. The new findings remain static review results; no source behavior, database data, AI call, or browser account state was changed by this pass.

## 可扩展性追加审查（2026-09-04）

### AI 安全输入投影 seam

- **静态发现**：`CareerMaterialAiSnapshotSanitizer.sanitize` 对非 `ACHIEVEMENT` 直接返回原实体；`JobGenerationPromptBuilder.appendMaterials` 和 `MaterialSelectionPromptBuilder.snapshot` 随后把 `sourceText`、`contentJson` 放进模型输入。相比之下，沟通 prompt 和面试上下文各自维护敏感字段/正则脱敏规则。
- **风险**：新增资料类型或字段时，维护者必须记住多个 sanitizer；遗漏一个路径就可能将联系方式或其他敏感内容发送给 provider。现有成果指标测试只证明 `metricExactValue` 口径，不证明 email/phone/url 边界。
- **深模块方向**：建立一个按任务能力声明字段投影、敏感类别和长度预算的 AI-safe input module；各 prompt builder 只消费该 interface，不再自行复制脱敏规则。这个 seam 直接提升 locality，也让同一策略能被同意校验和审计使用。
- **验收**：为普通 WORK/PROJECT、ACHIEVEMENT、SKILL_EVIDENCE 和高级 JSON 注入 synthetic email/phone/address/URL；对 selection、generation、communication、interview 四条路径断言 provider payload 不含原值，并验证允许的量化展示口径仍保留。

### AI prompt context budget seam

- **静态发现**：`CareerMaterialService` 允许 `sourceText` 到 65535 字符、`contentJson` 到 65536 bytes；selection 最多 60 条候选，generation 最多 30 条确认资料，但 `JobGenerationPromptBuilder` 没有按任务统一截断、token 估算或拒绝预算，`BailianAiProvider` 只在 provider 请求阶段暴露失败。
- **深模块方向**：把“资料投影 + prompt budget + 超限分类”收敛为一个小 interface。不同任务可声明预算与裁剪优先级，provider adapter 接收已经通过预算的 payload；测试可在 seam 上做确定性字节/token 基准。
- **验收**：构造 1、30、60 条接近最大长度的资料，测量序列化字节、估算 token、provider 请求体和失败分类；确认超预算在本地以稳定错误码拒绝，短字段优先级和敏感字段规则不被裁剪破坏。

### 多语言 PDF presentation module

- **静态发现**：`web/src/components/resume/ResumePaper.vue` 从 i18n 读取栏目标题；`pdf-service/src/templates/classic.js:158-168` 固定中文标题和 `lang="zh-CN"`，导出请求也没有 locale/output-language 字段。
- **深模块方向**：把 template code、locale、section labels 和 capability version 放进一个稳定的 presentation interface；Web preview 与 PDF adapter 各自渲染，但共同消费同一 presentation contract。
- **验收**：在 zh-CN/en-US 下分别预览并导出所有七个模板，逐项比较栏目顺序、标题、语言标签和自定义模块；未知 locale 明确回退，并保证不改变 resume facts 或模板安全约束。

### 简历当前版本指针 seam

- **静态发现**：`ResumeVersionService` 用 `MAX(version_no)+1` 加唯一键处理版本号冲突，但 `Resume`/`BaseEntity` 没有 `@Version`；`ResumeService.setCurrentVersion` 和恢复/首版本流程直接保存 `currentVersionId`。
- **深模块方向**：把“版本代次、当前指针、合法切换和冲突结果”封装成一个小 interface；可以选择 `@Version`、条件更新或显式 editor epoch，但必须将冲突语义传给 Web，而不是依赖最后写入。
- **验收**：两个并发编辑者分别创建版本并切换当前版本，覆盖首版本、恢复、归档版本和删除简历竞态；断言不会把当前指针静默覆盖，冲突可重试且历史版本完整。

### 本轮报告与范围

- 本轮架构候选报告已生成并打开：`<architecture-review-temp.html>`。报告记录四个候选及前后结构图，文件写入操作系统临时目录，不进入仓库。
- 本轮没有创建团队/子进程，没有修改源代码，没有发送真实 AI/PDF 请求，没有执行账号删除，也没有改变浏览器测试数据。

## Rejection Summary

| # | Idea | Reason Rejected |
| --- | --- | --- |
| 1 | 继续拆分 `HomeView` / `ResumeEditorView` | 现有代码已有组件化和 epoch 隔离，收益低且可能破坏异步上下文边界；旧计划已给出“不做”结论。 |
| 2 | 引入 Vitest 作为新的前端单元测试框架 | 当前主要用户路径已有 Playwright，新增框架会增加 CI、依赖和维护基线；先补证据边界 E2E 更直接。 |
| 3 | 修补或恢复 `BAILIAN_MODELS` 多模型列表 | 当前配置没有消费方，实际生效的是单数 `BAILIAN_MODEL`；继续扩展会重新引入误导性死配置。 |
| 4 | O-08 面试历史 N+1 | 当前已经使用批量 score projection，属于已闭环问题，不应重复立项。 |
| 5 | O-09 资产全量拉取 | 当前章节筛选已下沉到后端；未选择章节时返回全部是当前“全部”筛选的预期行为。 |
| 6 | O-10/O-11/O-13/O-14 | 当前轮询、告警、版本定位和 stats 控制器测试均已有实现，旧计划条目已过时。 |
| 7 | 团队协作、共享资料库和多人权限 | 用户已明确本轮不使用团队功能，超出当前单用户产品范围。 |

## 第二轮追加审查：面试、沟通与异步任务（2026-09-04）

### 39. 模板占位符空白语法校验与填充不一致

- **静态发现**：`TemplatePlaceholderService.PLACEHOLDER_PATTERN` 接受 `{{ candidateName }}` 这类带空白的占位符，`validate` 也会放行；但 `fill` 的替换循环只查找精确的 `{{candidateName}}`，不会替换带空白的实际文本。
- **影响**：自定义模板可以保存成功，预览结果却仍显示 `{{ candidateName }}` 等原始占位符；`missingPlaceholders` 又按解析后的名字判断为已满足，因此界面可能不会提示缺失。这是当前可复现的 P2 功能缺口，也会让后续新增占位符时继续出现“规则允许、执行不一致”的扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/communication/service/TemplatePlaceholderService.java:30-40,77-91`；现有测试只覆盖无空白的 `{{candidateName}}`。
- **建议验收**：覆盖无空白、两侧空白、重复占位符和缺失值；所有合法空白写法都应替换并返回准确的 `missingPlaceholders`，非法写法仍应被拒绝。模板预览、保存草稿和系统模板种子均需保持同一语法契约。

### 40. 素材导入任务的前端轮询窗口短于统一异步任务契约

- **静态发现**：`web/src/api/materialGeneration.ts:48-65` 的 `waitForTask` 最多执行 20 次，首轮 1/2/4 秒、随后 5 秒间隔，总等待约 91 秒；而共享 `useTaskPolling` 默认是 2 秒 × 150 次、约 5 分钟，后端百炼读取超时默认也是 300 秒。
- **影响**：素材导入在排队、worker 续租或 provider 慢响应时可能先在页面显示“任务超时”，但后台任务仍继续执行。该页面没有把任务恢复入口交给用户，且统一 continuations 当前不包含 `MATERIAL_IMPORT`，所以用户可能丢失一个最终已经成功的结果并重复消耗配额。这是 P2 的异步可靠性和成本风险；正常小任务仍可完成。
- **代码证据**：`web/src/api/materialGeneration.ts:20-25,36-65`；`web/src/composables/useTaskPolling.ts:5-9,16-18`；`server/src/main/resources/application.yml:138-144`。
- **建议验收**：用可控队列延迟和 90/180/300 秒 provider 响应验证页面不误报；所有 AI 任务使用同一轮询窗口或明确的任务级预算，超时后必须保留 taskId、提供恢复/继续入口，且同一任务重开不应重复创建任务。

### 41. 面试规则降级和报告没有遵守会话输出语言

- **静态发现**：会话持久化了 `InterviewOutputLanguage`，AI prompt 也按该字段约束输出；但 `InterviewRuleEngine` 的首题、轮转题目和 `InterviewRuleService` 的反馈文本固定为中文，`InterviewReportService` 的空报告和完成摘要也固定为中文。
- **影响**：英文界面启动面试后，如果 AI 失败并选择规则模式，问题和反馈会回落为中文；即使 AI 路径正常，报告摘要仍可能中英混杂。规则降级因此不是 AI 路径的语言等价替代，而是用户可见的功能契约漂移，属于 P2 多语言一致性缺口。
- **代码证据**：`server/src/main/java/com/intelligentresume/interview/domain/InterviewSession.java:17-18`；`server/src/main/java/com/intelligentresume/interview/service/InterviewRuleEngine.java:18-26`；`server/src/main/java/com/intelligentresume/interview/service/InterviewRuleService.java:148-158`；`server/src/main/java/com/intelligentresume/interview/service/InterviewReportService.java:76-124`。
- **建议验收**：分别以 `ZH_CN` 和 `EN` 运行首题失败、规则作答、主动结束和报告读取；题目、反馈、完成原因/摘要和错误提示应遵守同一语言策略，技术名词可按既定例外保留。

### 42. 面试状态响应缺少模式与语言，刷新后跟进练习会漂移上下文

- **静态发现**：`InterviewSession` 保存了 `interviewMode` 与 `outputLanguage`，但 `InterviewStateResponse` 没有这两个字段，`InterviewStateAssembler` 也不会返回它们。前端恢复会话时只保存服务端状态，不会恢复原模式/语言；`startPractice` 对 AI 会话使用当前页面残留的 `interviewMode`，输出语言则直接读取当前 locale。
- **影响**：用户以 `BEHAVIORAL` 或 `COMPREHENSIVE` 开始面试，刷新页面后针对薄弱项创建 follow-up，任务可能被提交为默认 `TECHNICAL`；用户在会话期间切换界面语言时，follow-up 又会改变输出语言。练习内容与原面试的策略和语言不再同源，属于 P2 的状态契约和事实来源一致性风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/interview/domain/InterviewSession.java:14,17-18`；`server/src/main/java/com/intelligentresume/interview/dto/InterviewStateResponse.java:15-30`；`web/src/views/InterviewView.vue:95-104,425-450`；`web/src/views/InterviewView.vue:352-362`。
- **建议验收**：恢复四种模式、两种语言的会话后创建 follow-up，断言 task input 与原会话完全一致；会话状态 API、前端类型和恢复流程必须共享同一个不可变的模式/语言快照，不能用当前页面默认值推断。

### 本轮范围与验证边界

- 以上 39–42 均为静态审查，其中 39 是由实现路径直接推导出的可复现模板行为；未修改模板、轮询、面试或任务代码。
- 本轮未发送真实 AI 请求、未执行账号删除、未创建团队/子进程，也未改变浏览器或数据库数据。现有正常路径测试不覆盖带空白占位符、长队列轮询、英文规则降级和恢复后 follow-up 上下文，因此这些验收项仍保持开放。

### 第三轮资源边界与浏览器验证审查（2026-09-04）

### 43. `RateLimitFilter.maxBuckets` 不是内存桶硬上限

- **静态发现**：`evictStaleBuckets` 只在桶数量达到 `maxBuckets` 时尝试删除旧桶；删除后仍然无条件执行 `computeIfAbsent`。当所有桶都属于当前或上一分钟、没有可清理桶时，新客户端标识仍会继续加入 `ConcurrentHashMap`，因此 `maxBuckets=10000` 不是严格上限。
- **影响**：在可信代理头开启时，大量不同的 `X-Forwarded-For` 值可使登录/注册/刷新限流器持续保留新桶，造成进程内存增长；即使不信任代理头，多客户端高基数也会触发重复的全表清理。该风险属于 P2 资源保护/可扩展性缺口，当前正常 IP 限流行为仍正常。
- **代码证据**：`server/src/main/java/com/intelligentresume/auth/ratelimit/RateLimitFilter.java:60-67,87-90`；`max-buckets` 仅使用构造函数 fallback，主配置没有明确的拒绝/满桶策略；现有测试只覆盖同 IP 超限和不同 IP 独立计数。
- **建议验收**：在达到容量且无过期桶时验证新桶请求被拒绝、合并到受控溢出桶或按策略淘汰；验证并发插入下 map 大小有硬上限；对可信代理头做规范化、可信链校验和高基数压测，并确认清理耗时不阻塞认证请求。

### 44. 通用 AI 任务输入缺少服务端语义大小约束

- **静态发现**：`CreateAiTaskRequest.input` 是没有 `@Size` 或任务级校验的任意 `Map`；`AiTaskService` 会把它原样写入 `input_snapshot_json`，`MATERIAL_IMPORT` 的 `rawMaterialText` 又由 `PromptTemplates.appendRawMaterialText` 原样拼进 provider prompt。前端 `MaterialResumeGenerationView` 的 `maxlength=30000` 只是浏览器约束，直接 API 请求可以绕过；`spring.servlet.multipart.max-request-size` 也只约束 multipart，不约束 JSON AI 任务。
- **影响**：认证用户可以提交远大于页面上限的素材文本或深层 JSON，导致任务表快照膨胀、provider 请求体/上下文超限、配额被无效消耗，甚至让 worker 长时间处理单个任务。这与“任务终态留存”和“prompt token 预算”相关但不同：本项是未受控的服务端输入入口，属于 P2 资源消耗与可靠性风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/task/dto/CreateAiTaskRequest.java:15-25`；`server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java:54-93`；`server/src/main/java/com/intelligentresume/ai/provider/PromptTemplates.java:181-186`；`web/src/api/materialGeneration.ts:21-25`；`web/src/views/MaterialResumeGenerationView.vue:73-74`。
- **建议验收**：为每个通用任务声明最大输入字节/字符/嵌套深度和字段白名单；绕过浏览器直接提交超限 `MATERIAL_IMPORT` 时在入队前返回稳定校验错误，不写入任务快照；对合法边界测量 JSON 字节、估算 token、队列耗时和 provider 请求体，并保持敏感数据和错误信息不进入日志。

### 本轮浏览器与联调边界

- 内置浏览器当前使用 `http://127.0.0.1:5173/`，启动了项目自带 Vite 前端和 `local-h2` 内存后端（API 8080）；健康接口返回 200，Flyway 已应用 V1–V24。通过 UI 注册了一个合成临时账号，验证了登录后职业资料页面、空表单校验、资料保存/详情展示、关键词搜索、AI 授权状态页和素材生成空输入提示。
- 首页、注册页中英文切换、主导航展开、未登录受保护路由跳转、正确业务路由加载和未知路由 404 均正常。没有点击 AI 授权、没有调用真实 AI/PDF、没有上传文件、没有删除数据，也没有把账号凭据写入文档或日志。
- 后端启动前的纯前端 smoke 曾在 Vite 代理中看到 `/api/auth/refresh` 和 `/api/system/health` 的 `ECONNREFUSED`；这是测试准备阶段后端未启动的预期环境错误，不是后端启动后的产品故障。H2 API 启动后健康检查和认证页面请求均正常。
- `agent-browser` 未安装，因此本轮不能声称完成该技能要求的可重复 CLI E2E；内置浏览器手工 smoke 仅作为补充证据，完整多浏览器回归仍应使用仓库已有 Playwright/安装后的 `agent-browser` 流程。

### 第四轮认证一致性审查（2026-09-04）

### 45. Refresh token 轮换缺少原子的一次性边界

- **静态发现**：`AuthService.refresh` 先按摘要查询 `AuthSession`、检查 `revokedAt`，再保存撤销时间并插入新的 session；`AuthSessionRepository.findByRefreshTokenHash` 没有悲观锁，`AuthSession` 仅继承没有 `@Version` 的 `BaseEntity`，V1 的 `auth_session` 也没有版本列或条件更新约束。
- **影响**：两个并发请求可以在任一请求提交撤销前同时读到同一个 active refresh token，随后都成功签发新的 access/refresh token。第二个请求不会读到已撤销状态，因此不会触发注释中约定的 family reuse 检测；同一个旧 token 由此可能产生多个仍有效的后继会话，削弱旋转对重放的防护。这是 P2 认证并发安全与扩展风险，串行刷新功能当前正常。
- **代码证据**：`server/src/main/java/com/intelligentresume/auth/service/AuthService.java:92-133`；`server/src/main/java/com/intelligentresume/auth/repository/AuthSessionRepository.java:9-12`；`server/src/main/java/com/intelligentresume/auth/domain/AuthSession.java:17`；`server/src/main/resources/db/migration/V1__m1_m2_init.sql:27-43`。现有 `AuthServiceTest` 和 `AuthControllerIT` 只覆盖串行轮换与之后的串行旧 token 复用，没有并发刷新测试。
- **建议验收**：使用两个事务并发提交同一个 refresh token，断言只有一个请求能完成轮换，另一个请求获得稳定的失效/冲突结果，并明确是否撤销整个 family；无论采用行锁、条件 `UPDATE` 还是乐观版本，都不能产生两个成功的后继 token，且重试行为要能被客户端安全理解。

### 46. 注册与改邮箱的唯一约束竞态可能返回 500

- **静态发现**：`AuthService.register` 和 `changeEmail` 都采用“先 `existsBy...`、后 `save`”的检查；数据库的 `user.username`/`user.email` 唯一键仍是最终并发裁判，但认证服务没有捕获 `DataIntegrityViolationException`，`GlobalExceptionHandler` 也只专门映射了乐观锁异常，其余异常进入通用 500 分支。
- **影响**：两个请求同时注册相同用户名/邮箱，或并发把两个账号改成同一个邮箱时，双方都可能通过前置查询，失败方在唯一键冲突后得到未稳定定义的内部错误，而不是与串行重复检查一致的 `409/CONFLICT`。数据完整性仍由数据库保护，但客户端会把可预期的业务冲突误判成服务故障并重试。这是 P2 认证 API 可靠性与扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/auth/service/AuthService.java:53-69,195-204`；`server/src/main/resources/db/migration/V1__m1_m2_init.sql:7-23`；`server/src/main/java/com/intelligentresume/common/error/GlobalExceptionHandler.java:42-71`。现有认证测试只 mock `existsByUsername/existsByEmail=true` 的串行分支，没有并发唯一键测试。
- **建议验收**：并发提交相同规范化用户名/邮箱时，断言恰好一个请求成功、其余请求稳定返回冲突码；唯一键异常必须回滚注册用户及其 session，不泄露数据库细节；同时明确邮箱大小写/空白的规范化策略，让检查、唯一键和登录查询使用同一口径。

### 本轮认证审查边界

- #45 和 #46 均为静态审查结果，未注入并发事务、未制造唯一键冲突、未撤销或删除任何本地账号数据，也未修改认证源代码。
- 既有 `AuthServiceTest`、`AuthControllerIT` 的正常/串行回归仍是基线证据，不能关闭上述并发验收缺口；认证并发测试应在目标 MySQL 事务隔离级别下补充，不能只以 Mockito 或 H2 结果替代。
- 本轮定向验证：`mvn -q "-Dtest=AuthServiceTest,AuthControllerIT" test` 通过；`git diff --check` 通过。该结果只确认正常/串行认证基线和文档格式，不关闭 #45/#46 的真实数据库并发缺口。

### 第五轮资料读取与 AI 任务接缝审查（2026-09-04）

### 47. 职业资料摘要列表退化为完整实体读取

- **静态发现**：当前工作树中的 `CareerMaterialService.list` 调用 `findByUserIdOrderByUpdatedAtDesc` 取回完整 `CareerMaterial` 后才在 Java 中按 `materialType` 过滤和映射摘要；此前仓储中的 `findSummaries` 投影已被移除。实体包含 `MEDIUMTEXT sourceText` 和 JSON `contentJson`，但列表 DTO 只需要 id、类型、标题、偏好、更新时间和 `evidenceReady`。
- **影响**：每次职业资料列表或生成工作台初始化都会读取用户全部资料正文/JSON，再丢弃绝大部分字段；资料条数或单条来源文本增长后，数据库传输、JPA 内存占用和序列化前置成本会线性放大，类型过滤也无法在数据库层缩小扫描范围。这是当前功能正常但由摘要接缝退化引入的 P2 性能与扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/careermaterial/service/CareerMaterialService.java:76-80`；`server/src/main/java/com/intelligentresume/careermaterial/repository/CareerMaterialRepository.java:19-21`；`server/src/main/java/com/intelligentresume/careermaterial/domain/CareerMaterial.java:25-43`；当前 diff 删除了原 `findSummaries` JPQL 投影。现有列表测试只验证结果数量和过滤，不验证 SQL 选择列、响应字节或大数据行为。
- **建议验收**：恢复专用轻量 projection/查询并让 `type` 过滤进入 SQL；若 `evidenceReady` 不能由查询稳定计算，应建立持久化或可索引的证据摘要字段。用 1/100/1000 条、单条接近上限正文的 fixture 比较 SQL 行列、数据库传输、堆峰值、响应字节和首屏耗时，确保详情接口仍是唯一读取全文的入口。

### 48. AI 任务幂等检查在并发下可能落为数据库 500

- **静态发现**：`AiTaskService.create` 先按 `(userId, taskType, idempotencyKey)` 查询，再执行配额检查并保存任务；V1 通过 `uk_ai_task_idem` 做最终唯一约束，但该创建路径没有捕获唯一键冲突并回读赢家。`GlobalExceptionHandler` 对这类异常只走通用 500。岗位选材和通信等多个入口都复用这条创建路径，因此新增任务类型也会继承同一缺口。
- **影响**：同一用户并发重放相同幂等键时，两个请求都可能看不到已有任务，最终一个成功、另一个在唯一键冲突后返回 500，而不是“相同指纹返回同一任务”或“不同指纹返回 409”。网络重试可能把可恢复的幂等重放误判为服务失败，甚至重复触发上游重试策略。这是 P2 异步可靠性与扩展风险；串行幂等测试当前正常。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java:57-94`；`server/src/main/resources/db/migration/V1__m1_m2_init.sql:176-193`；`server/src/main/java/com/intelligentresume/common/error/GlobalExceptionHandler.java:60-71`；`server/src/main/java/com/intelligentresume/ai/selection/controller/JobMaterialSelectionController.java:55-99`。现有 `AiTaskServiceTest`/`AiTaskControllerIT` 只覆盖串行已有记录和重复请求，没有真实事务并发。
- **建议验收**：对通用创建、岗位选材、通信生成和后续新任务类型各执行同一 key 的并发提交；相同指纹必须收敛到一个任务及一个配额消耗，异指纹必须稳定返回冲突，唯一键异常要回读并比较指纹而不是泄露 500；同时验证临时 JD 等外层事务不会留下孤儿记录。

### 49. AI 幂等键的长度与规范化契约分散

- **静态发现**：`AiTask`/V1 schema 将幂等键限制为 128 字符，但通用 AI 任务和岗位选材入口没有长度校验，也不统一 `trim`；通信入口会 trim 但不限制长度，ATS 入口 trim 且限制 128，面试入口限制 64 但不 trim。幂等键直接参与查询、唯一键和持久化。
- **影响**：带首尾空白的同一业务 key 可能在不同入口或不同客户端形成不同任务；超过 128 字符的 header 可能直到数据库写入才失败并返回 500。新增任务类型如果复制某个入口的校验方式，会继续扩大跨端幂等语义漂移。这是 P2 API 契约与可扩展性风险，正常短 key 流程不受影响。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/task/controller/AiTaskController.java:37-49`；`server/src/main/java/com/intelligentresume/ai/selection/controller/JobMaterialSelectionController.java:55-60,102-104`；`server/src/main/java/com/intelligentresume/communication/controller/CommunicationController.java:47-52`；`server/src/main/java/com/intelligentresume/ats/controller/AtsController.java:25-30`；`server/src/main/java/com/intelligentresume/interview/controller/InterviewController.java:37-67`；`server/src/main/java/com/intelligentresume/ai/task/domain/AiTask.java:43-44`。
- **建议验收**：定义一个共享幂等键规范（去除首尾空白、非空、最大长度、是否允许规范化后为空），所有 AI 入口在查库、指纹和保存前使用同一值；超限应在入队前返回稳定校验错误，并用跨入口测试确认同一规范化 key 的任务、重试和冲突语义一致。

### 50. 多个摘要/列表接口仍读取并返回完整实体长字段

- **静态发现**：岗位描述列表通过 `findByUserIdOrderByUpdatedAtDesc` 读取完整 `JobDescription`，随后在 Java 中对 `MEDIUMTEXT jdText` 做预览；简历版本列表读取完整 `ResumeVersion`，并把 `resumeJson` 之外的 `generationContext` 一并放进摘要 DTO；投递跟进列表通过 `findByUserIdAndFollowUp` 返回完整 `ApplicationRecord`，响应中包含多个 `TEXT` 字段（求职信、邮件、开场白和反馈）；沟通模板列表和面试历史列表也分别加载 `TEXT bodyText`、`MEDIUMTEXT externalResumeText`，但摘要不使用这些字段。这些接口的调用语义都是列表/摘要，但仓储没有对应的轻量 projection。
- **影响**：用户的岗位原文、简历 JSON、生成上下文和沟通长文本会在列表查询阶段被数据库传输、JPA 实体化并参与序列化；数据量或单条正文增长后，列表响应字节、堆占用和数据库 I/O 会线性放大。它与 #11 的“缺少分页”相关但更具体：即使未来加了分页，每页仍可能把不需要的长字段读出来。这是当前功能正常但摘要/详情读模型未分离导致的 P2 性能与扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/jobdescription/service/JobDescriptionService.java:58-61,141-149`、`server/src/main/java/com/intelligentresume/jobdescription/domain/JobDescription.java:31-43`；`server/src/main/java/com/intelligentresume/resume/service/ResumeVersionService.java:111-124,264-269`、`server/src/main/java/com/intelligentresume/resume/dto/ResumeVersionSummary.java:8-19`；`server/src/main/java/com/intelligentresume/application/service/ApplicationService.java:51-57`、`server/src/main/java/com/intelligentresume/application/repository/ApplicationRecordRepository.java:45-61`、`server/src/main/java/com/intelligentresume/application/dto/ApplicationResponse.java:7-12`；`server/src/main/java/com/intelligentresume/communication/service/CommunicationTemplateService.java:46-49`、`server/src/main/java/com/intelligentresume/communication/domain/CommunicationTemplate.java:34-36`；`server/src/main/java/com/intelligentresume/interview/service/InterviewHistoryService.java:43-60`、`server/src/main/java/com/intelligentresume/interview/domain/InterviewSession.java:11-12`。现有定向测试只验证列表结果和排序/过滤，不验证 SQL 选择列、响应字节或大数据量行为。
- **建议验收**：为岗位、简历版本、投递、模板和面试历史列表分别建立只读 projection/查询，列表 DTO 只保留列表所需字段；详情接口才读取全文，并让跟进/统计等场景使用专用读模型。用 1/100/1000 条、接近字段上限的 fixture 对比查询列、数据库传输、堆峰值、响应字节和延迟，确认分页、过滤和排序仍保持原语义。

### 51. 前后端简历版本详情契约缺少 `resumeId`

- **静态发现**：前端 `web/src/api/resume.ts` 将详情类型 `ResumeVersion.resumeId` 声明为必需字段；后端 `ResumeVersionDetail` record 没有 `resumeId`，`ResumeVersionService.toDetail` 也只映射版本自身字段，不会序列化该属性。当前页面没有依赖它，所以构建和现有运行路径仍可通过，但客户端类型与实际 JSON 已经漂移。
- **影响**：未来只依赖 `getResumeVersion` 响应的客户端逻辑会得到 `undefined`，不能可靠地从详情中确定父简历；维护者也会被 TypeScript 的必需字段误导，以为该字段始终存在。这是 P2 的跨运行时 API 契约风险，属于可扩展性问题而非当前页面必现故障。
- **代码证据**：`web/src/api/resume.ts:13-24`；`server/src/main/java/com/intelligentresume/resume/dto/ResumeVersionDetail.java:7-18`；`server/src/main/java/com/intelligentresume/resume/service/ResumeVersionService.java:264-269`。现有 `ResumeVersionServiceTest` 覆盖详情内容但未断言序列化后的字段集合，前端构建也不会验证服务端 JSON 是否包含必需属性。
- **建议验收**：在服务端详情 DTO 中明确补充并返回 `resumeId`，或将前端详情类型改为不声明该字段，不能保留当前“类型必需、运行时缺失”的状态；增加 controller/integration JSON 契约测试，并让生成的 OpenAPI/客户端类型成为单一事实来源。

### 52. 首页任务恢复列表携带完整 AI 结果 JSON

- **静态发现**：`GET /api/ai/tasks/continuations` 查询可恢复的岗位选材/生成任务后，直接把 `AiTaskStatusResponse.resultJson` 原样返回；该字段可能包含完整选材结果或整份生成简历。`HomeView` 只用任务类型、状态、ID 和更新时间构造恢复入口，并不会读取 `resultJson`，点击后页面还会按 taskId 再请求详情。
- **影响**：每次首页加载都可能重复传输大型生成结果，结果数量或简历章节增长后会放大数据库 JSON 读取、序列化、网络和浏览器内存成本；首页与详情页的读模型耦合，也让未来接入新的任务类型时难以控制敏感结果的暴露面。这是 P2 的响应性能、隐私最小化和异步恢复扩展风险，与 #42 的“任务类型覆盖不完整”不同：即使恢复类型不变，列表仍然过宽。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/task/repository/AiTaskRepository.java:31-43`；`server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java:112-115,139-154`；`server/src/main/java/com/intelligentresume/ai/task/dto/AiTaskStatusResponse.java:13-26`；`web/src/views/HomeView.vue:46-50,82-105,151-159`。现有 `AiTaskServiceTest` 和 `AiTaskControllerIT` 验证任务归属/顺序，但未断言恢复列表不加载结果 JSON、响应字节或首页重复请求成本。
- **建议验收**：为恢复列表定义 metadata-only read model，保留 `GET /tasks/{id}` 作为结果详情；前端首页只基于 metadata 渲染入口，点击后再按任务类型加载所需结果。用空结果、典型结果和接近上限的生成结果测量 SQL 列、响应大小、首页内存和详情请求次数，并确保任务归属、敏感数据和排序语义不变。

### 53. 素材生成路径使用私有章节白名单，扩展章节会被静默丢弃

- **静态发现**：Web 的共享 `sectionRegistry` 和后端 `JsonResumeValidator` 已允许 14 个简历章节；但 `web/src/api/materialGeneration.ts:18` 的 `RESUME_SECTIONS` 只保留 `basics`、`work`、`education`、`skills`、`projects`、`certificates`、`languages`、`awards`，`normalizeResumeJson` 会在用户确认前丢弃 `objective`、`links`、`volunteering`、`courses`、`publications`、`customSections`。当前 Prompt 也只要求其中的部分章节，因此小范围素材生成可以正常工作；问题在于这条路径拥有一份没有共享版本号或契约测试的隐式子集。
- **影响**：一旦 provider 开始返回被后端和编辑器接受的新增章节，或产品希望素材生成覆盖现有 14 栏，字段会在保存前无提示消失，用户只能看到一个不完整的草稿。每增加一个章节，维护者必须同时修改编辑器 registry、JSON validator、AI prompt、素材生成 allowlist、确认页和 PDF/资产适配器；漏改任何一处都会产生“生成有数据、保存后没有数据”的跨运行时契约漂移。这是 P2 的功能可靠性与简历文档扩展风险。
- **代码证据**：`web/src/api/materialGeneration.ts:18-30`；`web/src/resume/sectionRegistry.ts:10-19`；`server/src/main/java/com/intelligentresume/resume/service/JsonResumeValidator.java:37-65`；`server/src/main/java/com/intelligentresume/ai/generation/service/JobGenerationSchemaValidator.java:32-64`；`server/src/main/java/com/intelligentresume/ai/provider/PromptTemplates.java:50-61`。现有素材生成测试只验证任务轮询/结果外壳，没有用合法但超出私有白名单的章节断言“保留或明确拒绝”。
- **建议验收**：以带 `objective`、`links`、`volunteering`、`courses`、`publications`、`customSections` 的合法草稿 fixture 贯穿 provider 结果、前端 normalize、创建简历、编辑器、预览和 PDF；要么由一个带版本号的 document contract 统一声明可保留章节，要么在接口层返回稳定的 `droppedSections`/拒绝原因，禁止静默丢弃。增加章节集合一致性测试，并验证未知字段仍按安全策略处理。

### 54. 平台简历面试上下文只投影部分合法章节

- **静态发现**：平台简历面试通过 `InterviewContextSanitizer.sanitizePlatformResume` 生成上下文，但当前只提取 `basics` 的 `label/summary`、`work`、`projects`、`education`、`skills`、`certificates` 和 `languages`。编辑器 registry、`JsonResumeValidator` 与面试资产模块允许的 `objective`、`links`、`volunteering`、`courses`、`publications`、`awards`、`customSections` 不会进入 `resumeSummary`；`InterviewPromptContextAssembler` 也没有其他补充路径。现有 sanitizer 测试覆盖工作、项目和教育日期/PII，却没有断言这些合法章节的保留或明确丢弃语义，assembler 测试又 mock 了 sanitizer，因此不会暴露该差异。
- **影响**：用户在平台简历中维护的职业目标、志愿/实习经历、课程、研究成果、奖项或自定义模块可能不会被面试 AI 看到，面试提问和评估只能基于部分简历事实。这可能是出于隐私和 token 预算的“最小必要上下文”设计，但当前没有版本化的字段策略、用户可见提示或测试契约，新增章节也容易出现“编辑器/PDF/资产有内容，面试上下文无内容”的跨运行时漂移，属于 P2 上下文完整性与扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/interview/service/InterviewContextSanitizer.java:31-52`；`server/src/main/java/com/intelligentresume/interview/service/InterviewPromptContextAssembler.java:108-122`；`web/src/resume/sectionRegistry.ts:10-24`；`server/src/main/java/com/intelligentresume/resume/service/JsonResumeValidator.java:37-65`；`server/src/main/java/com/intelligentresume/interview/asset/service/InterviewAssetService.java:25-29`。产品验收要求平台简历面试可完成多轮问答，但没有定义平台简历章节投影范围。
- **建议验收**：先明确面试能力是“完整简历事实”还是“声明过的最小章节子集”。若是完整事实，为所有允许章节建立统一的安全字段投影、长度预算和 PII 脱敏测试；若是最小子集，为策略提供版本号/字段清单、前端可见提示和稳定的未纳入章节语义，并在新增章节时运行一致性测试。用包含全部 14 个章节的合法 fixture 验证平台简历创建、面试上下文、提问/评估和答案资产链路；未授权 AI 时只做本地 sanitizer/assembler 测试，不发送真实 provider 请求。

### 55. 沟通 AI 输入投影仍停留在旧版八章节白名单

- **静态发现**：`CommunicationAiPromptBuilder.RESUME_SECTIONS` 只序列化 `basics`、`work`、`education`、`skills`、`projects`、`certificates`、`languages`、`awards`。因此 `objective`、`links`、`volunteering`、`courses`、`publications`、`customSections` 即使存在于合法的 `ResumeDocument` 和 PDF/编辑器视图中，也会在 `buildTaskInput` 阶段被丢弃，且没有 `droppedSections` 或版本化 projection 元数据。项目设计文档的共享约定则要求 14 个章节键统一来自 `sectionRegistry`；当前沟通 AI 测试只传递 basics/skills，没有覆盖投影集合。
- **影响**：生成求职信、邮件或开场消息时，AI 看不到用户维护的职业目标、志愿/实习经历、课程、研究成果和自定义模块，可能遗漏与目标岗位最相关的事实。即使“只发最小必要字段”是有意策略，也应显式说明并与面试、素材生成使用同一套可审计的章节策略；当前私有白名单会让新增章节在编辑器/PDF 可见、沟通文案不可见，属于 P2 的跨能力上下文完整性与扩展风险。该项不同于 #54：它发生在沟通任务入队前，并影响文案生成而非面试评估。
- **代码证据**：`server/src/main/java/com/intelligentresume/communication/service/CommunicationAiPromptBuilder.java:18-21,69-75,106-112`；`web/src/resume/sectionRegistry.ts:10-24`；`web/src/types/resume.ts:171-187`；`docs/plans/2026-08-31-004-design.md:457-458`。现有 `CommunicationAiServiceTest` 仅检查语言、provider 调用和结果落库，没有断言 14 章节的保留/拒绝契约。
- **建议验收**：与 #54 共同定义按任务声明的安全 resume projection：明确沟通任务需要的章节、字段、长度预算和 PII 规则，并携带 projection version；用包含全部 14 个章节的合法 fixture 断言沟通任务输入保留约定章节，或返回稳定的 omitted-section 诊断，禁止无提示的私有白名单漂移。覆盖中英文、空章节、超长字段和敏感链接字段，确认草稿仍不自动发送且 provider payload 不含被禁止的联系方式。

### 56. 页面内切换语言后静态标签映射不会刷新

- **静态发现**：`ResumeDetailView`、`InterviewAssetsView`、`CompareVersionsView` 在 `script setup` 中直接把 `t('resumeEditor.*Label')` 的结果写入 `sectionLabels`；`CommunicationView` 同样把 `t('communication.scene*')` 的结果写入一次性的 `sceneLabels`。而 `useLocale` 的 `locale` 是共享响应式 ref，`LanguageSwitcher.setLocale` 只更新该 ref 和文档语言，不会重新挂载当前路由页面。
- **影响**：用户在这些页面内从中文切到英文（或反向切换）时，页面标题、按钮等直接调用 `t()` 的文案会刷新，但章节筛选、资产标签、版本对比栏目和沟通模板场景选项仍显示旧语言。问题不影响数据提交，但会造成明显的本地化不一致；继续增加语言或标签时，静态翻译缓存还会形成重复维护的扩展风险。
- **代码证据**：`web/src/i18n/index.ts:961,978-994`；`web/src/components/LanguageSwitcher.vue:5-12`；`web/src/views/ResumeDetailView.vue:38-54`；`web/src/views/InterviewAssetsView.vue:34-55`；`web/src/views/CompareVersionsView.vue:39-58`；`web/src/views/CommunicationView.vue:75-81`。本轮未启动浏览器语言切换回归，结论来自共享响应式实现与静态初始化顺序；前端构建仍可通过。
- **建议验收**：在不刷新、不离开路由的情况下，在上述四个页面逐一切换 `zh-CN`/`en-US`，断言筛选选项、标签和场景下拉项与页面其余文案同步更新；把映射改为翻译 key + 渲染时 `t()`，或改为依赖 `locale` 的 `computed`，并增加至少一个组件级语言切换回归测试。

### 57. Web AI 任务类型包含后端不存在的 `EXPORT_PDF`

- **静态发现**：`web/src/api/ai.ts` 的 `AiTask.taskType` 联合类型声明了 `EXPORT_PDF`，但服务端 `AiTaskType` 只有九种 AI 类型，没有该枚举；PDF 实际通过独立的 `export_task`、`/api/exports/*` 和 `ExportStatus` 流程处理。当前 Web 搜索未发现任何代码主动发送或分派 `EXPORT_PDF`，因此不是已复现的页面故障。
- **影响**：类型层允许未来页面把 PDF 任务当作 AI 任务读取、轮询或恢复，但服务端响应无法反序列化为该类型，且首页任务恢复的路由也不会为它提供明确入口。这会让“新增异步任务只改一侧”的契约漂移变成运行时错误，属于 P2 跨运行时 API 扩展风险。
- **代码证据**：`web/src/api/ai.ts:79-86`；`server/src/main/java/com/intelligentresume/ai/task/domain/AiTaskType.java:6-17`；`server/src/main/java/com/intelligentresume/export/controller/ExportController.java:31-66`；`server/src/main/java/com/intelligentresume/export/domain/ExportStatus.java:6-12`。前端构建通过且未发现 `EXPORT_PDF` 调用方，故本轮只记录契约缺口，不修改类型或合并两套任务模型。
- **建议验收**：确定 PDF 是否永远保持独立任务模型；若是，移除 Web AI 联合类型中的 `EXPORT_PDF`，并增加“所有前端任务类型都属于后端枚举或显式独立域”的机器校验。若未来统一任务模型，则同时定义状态、结果、恢复入口和迁移契约，不能只追加一个字符串字面量。

### 本轮新增审查边界

- #47–#57 均为静态审查，未发送真实 AI 请求、未制造并发任务、未写入超长幂等键，也未删除或修改现有资料/任务数据；#56 还未进行页面内语言切换的浏览器复现，#57 未发现实际调用方。
- 上述条目不替代已有的无分页、prompt 预算、任务留存和 provider 路由风险；本轮特别关注当前工作树中摘要投影退化和可复用 AI 任务接缝的扩展影响。
- 本轮定向验证：`ResumeVersionServiceTest` 与 `ExportServiceTest` 通过；PDF 服务 16 项测试通过。前一轮的 `CommunicationAiServiceTest` 2 项、`AtsAiPromptBuilderTest` 4 项、`InterviewContextSanitizerTest` 34 项和 `InterviewPromptContextAssemblerTest` 12 项均通过；前端 `npm run build` 也已通过。测试仍未覆盖真实数据库并发、SQL 列投影、列表响应字节、首页结果重复传输、跨 AI 能力章节 projection 一致性、超长 header 压测或页面内语言切换。
- 本轮架构候选报告已生成并打开：`<architecture-review-temp.html>`。报告聚焦 versioned resume document contract、AI task capability module 和 summary read-model seam 三个方向，没有修改源代码；需先选择一个方向再进入设计和验收，不同时启动多个大型重构。

## 第六轮异步接缝与浏览器复核审查（2026-09-04）

### 58. 简历版本选择器存在旧响应覆盖新选择的竞态

- 静态发现：useResumeJobOptions.loadVersions 每次调用时清空 versions 后直接用当前 selectedResumeId 发起 listVersions；没有请求代次、取消请求或响应前的当前选择校验。ATS、沟通、面试和成就指导页面都通过 @change="loadVersions" 复用它，而且各自的 resumeVersionId 不会在简历切换时统一清空。
- 影响：用户快速从简历 A 切到 B 时，A 的慢响应可能晚于 B 返回并覆盖版本列表；旧的 resumeVersionId 还可能与当前简历不匹配，导致提交错误版本。这是 P2 前端异步一致性风险。
- 代码证据：web/src/composables/useResumeJobOptions.ts:31-39；AtsCheckView.vue:16,29,167-168；CommunicationView.vue:37,83,467-475；InterviewView.vue:31,36,478-479。
- 建议验收：为 A/B 版本请求注入可控延迟，断言最终列表只属于最后一次选择；切换简历时版本值必须清空并禁用提交，旧请求失败也不能覆盖新状态。优先在共享 composable 内实现 epoch/AbortController。

### 59. 编辑器内联 AI 轮询窗口过短且没有恢复入口

- 静态发现：waitForAiTaskResult 默认只等待 30 次、每次 1 秒，约 30 秒后超时；ResumeEditorView 创建任务后直接调用它，虽已拿到 task id，却没有保存到可恢复状态。provider 默认读取超时为 300 秒，共享 useTaskPolling 约为 5 分钟，但内联路径没有使用它。
- 影响：排队或 provider 响应超过 30 秒时，编辑器会显示 AI 不可用，而任务可能仍在后台执行；用户无法恢复原任务，只能重试并可能重复创建调用。属于 P2 异步可靠性、体验和成本风险。
- 代码证据：web/src/api/ai.ts:192-202；web/src/views/ResumeEditorView.vue:458-475；web/src/composables/useTaskPolling.ts:5-9,16-18；server/src/main/resources/application.yml:139-144。
- 建议验收：让任务在 30/90/180/300 秒边界完成，页面不得误报失败；超时后保留 task id 并提供继续查看/取消/重试语义，恢复同一任务不能重复入队。

### 60. 通用 AI worker 租约与 provider 时间预算不一致

- 静态发现：通用 AI worker 租约默认为 180 秒，TaskExecutionService 每 60 秒尝试续租；provider 读取超时默认为 300 秒。续租失败时 worker 只记录 stale completion will be discarded 并继续当前 provider 调用，租约过期后其他 worker 可以重新领取同一任务。
- 影响：heartbeat 失联时，旧 worker 仍可能执行到 300 秒，第二个 worker 同时再次调用 provider；旧结果会被丢弃，但外部调用、费用和副作用可能已经发生。这是 P1/P2 之间的异步执行一致性风险。
- 代码证据：server/src/main/resources/application.yml:139-144；TaskExecutionService.java:100-107,296-306；TaskLeaseService.java:60-66,73-87；AiTaskRepository.java:62-82。
- 建议验收：用可控 provider 延迟和可注入 heartbeat/数据库故障覆盖租约过期、第二 worker 接管和旧 worker 晚到成功；应使租约预算大于最长执行时间、在续租丢失时取消调用，或引入 provider 幂等键与 fencing token。

### 61. 认证初始化断网后在当前页面生命周期内被永久锁定

- 静态发现：auth.initialize 第一次调用就把 initialized 设为 true；网络错误只设置 initializationError=NETWORK 并返回。后续路由守卫不会再次初始化，且对 NETWORK 状态放行受保护路由；项目中没有消费该状态的离线提示或重试入口。
- 影响：应用首次加载时后端短暂不可用，用户可能进入没有 access token 的受保护页面；服务恢复后页面导航仍不会重新尝试 refresh，页面 API 会持续失败，除非整页重载。属于 P2 会话恢复与可用性风险。
- 代码证据：web/src/stores/auth.ts:21-37,63；web/src/router/index.ts:95-104；web/src/api/client.ts:33-58。
- 建议验收：在 refresh 断网、服务恢复、再次导航的序列中，断言应用提供明确离线/重试状态并能重新初始化；没有 access token 时不得把受保护页面误当作已登录状态。

### 本轮复核与边界

- 内置浏览器使用 local-h2（API 8081）和 Vite（5173），注册本地临时账号并创建空白临时简历验证登录、简历列表、详情和版本历史路由；凭据未写入文档或日志，未执行账号删除。
- #56 已完成浏览器复现：在 /resumes/1 详情页从中文切换到 English 后，标题、按钮和页面说明切换为英文，但 Section filter 下拉菜单的章节项仍显示中文（如 个人信息、工作经历、自定义模块），正式升级为已确认的 P2 UI 本地化缺陷。
- agent-browser 未安装，无法运行 ce-test-browser 要求的可重复 CLI E2E；本轮浏览器证据来自 Codex 内置浏览器人工 smoke。未发送真实 AI/provider 请求、未调用 PDF renderer、未上传文件、未执行破坏性操作。
- #58–#61 仍为静态并发/故障路径审查；现有正常路径测试和前端构建不能关闭慢响应、租约接管、断网恢复或跨简历延迟竞态，验收条件已记录在上文。

## 第七轮跨运行时时间契约审查（2026-09-04）

### 62. 后端与 Web 的时间/时区契约未统一

- **静态发现**：后端业务广泛使用无时区的 `LocalDateTime.now()` 和 `LocalDate.now().atStartOfDay()`；`application.yml` 只显式配置 JDBC 时区为 `Asia/Shanghai`，API 返回的 `LocalDateTime` 没有 offset 或用户时区。Web 多个页面直接对这些字符串调用 `new Date(value)`，再按浏览器本地时区格式化；`ApplicationsView.isOverdue` 还把浏览器当前时间与后端按服务器自然日计算的 TODAY/OVERDUE 筛选并列使用。AI 配额、面试配额同样以服务端自然日切分。
- **影响**：在非上海时区的浏览器或部署环境中，同一时间可能在页面显示为相邻日期；投递跟进的“今天/逾期”标签可能与列表查询结果不一致；每日 AI/面试配额的重置边界也无法表达用户所在时区。夏令时、服务器迁移时区或未来多区域部署会放大这种漂移。这是 P2 的跨运行时一致性和可扩展性风险，当前 Asia/Shanghai 本地验证不能证明其他时区语义正确。
- **代码证据**：`server/src/main/java/com/intelligentresume/application/service/ApplicationService.java:53-55,147-148`；`server/src/main/java/com/intelligentresume/ai/ratelimit/AiQuotaService.java:59-60`；`server/src/main/java/com/intelligentresume/interview/service/InterviewOperationSupport.java:253-254`；`server/src/main/resources/application.yml:19,29`；`web/src/views/ApplicationsView.vue:247-259`；`web/src/views/HomeView.vue:137-139`；`web/src/views/ResumeDetailView.vue:65-67`。现有测试使用 JVM 默认时间和本地日期，没有覆盖非服务器时区、DST 或 API 字符串的 offset 契约。
- **建议验收**：先决定时间契约：推荐持久化/传输瞬时点使用 UTC `Instant` 或带 offset 的时间戳，并把“自然日/今天”定义为用户 profile 时区或明确的系统时区；随后让服务端筛选、配额、过期和统计统一通过注入的 `Clock`/`ZoneId` 计算，Web 仅按响应中的 offset/用户时区展示。用 Asia/Shanghai、UTC、America/Los_Angeles 和至少一个 DST 切换边界运行同一 fixture，断言 API、TODAY/OVERDUE、配额重置、过期时间和页面显示一致；禁止用字符串 `slice(0, 10)` 或浏览器默认时区作为业务日期判断。

### 本轮范围与验证边界

- #62 是跨运行时时间语义审查，不与已有的配额/跟进索引（查询性能）或各异步任务超时（执行预算）合并；它关注同一时间值在后端业务判断、API 序列化和 Web 展示之间的语义漂移。
- 本轮未修改时间实现、未改变账号或任务数据、未发送 AI/provider 请求、未调用 PDF renderer；结论来自源码和配置核对。既有 Maven 定向测试、PDF 16 项测试、Web 构建和 `git diff --check` 仍是绿色基线，但不能关闭非 Asia/Shanghai 时区验收缺口。

## 第八轮前端异步读模型审查（2026-09-04）

### 63. 版本对比在选择变化时重复请求且旧 diff 可覆盖新选择

- **静态发现**：`CompareVersionsView` 的两个版本选择器在 `@change` 中直接调用 `onBaseChange`/`onCompareChange`，同时 `watch([baseVersionId, compareVersionId])` 也会调用 `loadDiffs`；切换两侧或初始化时可能重复发起相同的两个 `getResumeVersion` 请求。`loadDiffs` 只读取启动时的两个 ID，返回后无请求代次校验，旧请求可以把 `baseJson`、`compareJson` 和展开章节写回当前选择。
- **影响**：快速切换版本、点击交换两侧或弱网络下，页面可能短暂显示与下拉选择不匹配的差异，重复请求还会放大版本正文读取和渲染成本。新增差异计算或更多版本字段时，这种“一个状态变化有多个加载入口”的浅接口会继续扩大竞态面，属于 P2 前端一致性与扩展风险。
- **代码证据**：`web/src/views/CompareVersionsView.vue:84-104,112-125,203-207` 以及模板中的版本选择器 `:230-235`。当前没有延迟注入、请求计数或响应快照测试；Web 构建正常不等于异步顺序正确。
- **建议验收**：让每次版本组合变化只经过一个加载入口，使用请求 epoch/AbortController，并在响应提交前比较请求快照中的两个版本 ID；开始新加载时清空或标记旧 diff。用 A/B、B/C 快速切换、交换两侧和重复提交验证最终 diff 永远对应最后一次选择，且每次状态变化只产生一组版本详情请求。

### 64. 沟通模板列表和预览缺少最新请求保护

- **静态发现**：`CommunicationView.loadTemplates` 直接用当前 `sceneFilter`/`outputLanguage` 发起请求，`watch(sceneFilter)`、模板页首次加载、手动刷新和保存后刷新都可能并发调用；返回时没有筛选快照或请求代次校验。`openPreview` 也没有校验当前 `previewing` 是否仍是发起请求的模板，模板 A 的慢响应可以覆盖模板 B 的预览。`outputLanguage` 从全局 locale 派生，但没有 watcher 触发模板列表重新加载。
- **影响**：快速切换场景、刷新或连续打开模板时，列表/预览可能展示旧筛选结果或错误模板内容；页面切换中英文后，模板数据还可能保持上一语言，而 #56 只覆盖静态标签不刷新的问题。随着模板类型、语言和场景增加，多个加载入口会让服务端读请求和前端状态更难推断，属于 P2 模板库一致性与多语言扩展风险。
- **代码证据**：`web/src/views/CommunicationView.vue:52-70,264-276,283-307,343-360,385-401,426`；`web/src/api/communication.ts:52-66`。当前没有延迟注入或连续筛选/预览测试，正常单次加载不关闭该风险。
- **建议验收**：为列表请求保存 scene/language 快照并只提交最新响应；为预览保存 template ID 快照，切换模板或关闭预览时令旧响应失效；监听输出语言变化并重新加载或明确保留旧语言。用场景 A/B、中文/英文、模板 A/B 的交错响应测试最终列表和预览内容，确认保存/删除后的刷新不会恢复旧列表。

### 65. 章节关联资产筛选可能显示旧章节结果

- **静态发现**：`ResumeEditorView.loadRelatedSectionAssets` 由 `activeSection` 的 watcher 触发，`ResumeDetailView.loadRelatedAssets` 由 `relatedSectionKey` 的 watcher 触发；两条路径都直接把响应写入共享 `related*Assets`，没有请求 epoch、取消请求或响应前的章节快照校验。用户从章节 A 快速切到 B 时，A 的慢响应可以在 B 的响应之后回写。
- **影响**：编辑器或简历详情页可能把错误章节的面试资产、事实证据展示在当前章节下，误导用户进行关联或判断证据覆盖；以后增加按简历、版本和岗位的复合筛选时，现有每页自有加载实现会复制同一竞态，属于 P2 证据追溯 UI 一致性与扩展风险。
- **代码证据**：`web/src/views/ResumeEditorView.vue:74-87`；`web/src/views/ResumeDetailView.vue:116-127`；后端筛选入口为 `web/src/api/interviewAsset.ts:27-33`。当前没有注入延迟或章节切换回归测试；接口单次返回正确不能证明交错请求顺序正确。
- **建议验收**：抽取共享的 latest-request seam，按 `sectionKey`、简历上下文和页面生命周期验证响应；旧请求完成时不得改变当前列表或 loading/error 状态。用 A/B/A 快速切换、组件卸载、空结果和请求失败交错测试，断言最终资产只属于最后一次章节选择。

### 本轮范围与验证边界

- #63–#65 均为静态异步顺序审查；它们不重复 #58 的“跨 ATS/沟通/面试/成就指导共享简历版本选择器”问题，而是分别覆盖版本对比、沟通模板和章节关联资产的独立加载入口。
- 本轮未注入网络延迟、未发送 AI/provider 请求、未调用 PDF renderer、未修改业务代码或测试数据。当前 Web 构建和正常页面 smoke 不能关闭交错响应顺序缺口，需按各条验收条件补充可控延迟测试。

## 第九轮错误契约与多语言扩展审查（2026-09-04）

### 66. API 错误码与用户文案没有分离，英文界面可能显示中文

- **静态发现**：服务端 `ErrorCode` 的默认消息全部是中文，多个 `BusinessException` 又直接携带中文或混合语言的业务文案；`GlobalExceptionHandler` 将该字符串直接作为 `ApiResponse.message` 返回，项目没有 `Accept-Language` 处理或稳定的 message key/参数字段。Web 的部分路径按错误码映射本地文案，但 `AccountView`、`GenerationWorkbenchView`、`GenerationConfirmView`、`MaterialSelectionConfirmView` 等路径直接展示 `response.data.message`，异步任务页面也直接展示 `errorMessage`。
- **影响**：用户把界面切到 English 后，修改邮箱/密码、生成失败、确认/重试失败或资源冲突等路径可能仍显示中文；新增客户端、移动端或第三种语言时只能解析不稳定的自然语言，无法可靠区分参数、可重试、授权和冲突等错误。它不同于 #13：#13 关注 AI 失败文本的长度、内部细节和持久化可靠性；#66 关注所有 API 错误的可翻译、可机器消费契约。
- **代码证据**：`server/src/main/java/com/intelligentresume/common/error/ErrorCode.java:3-19`；`server/src/main/java/com/intelligentresume/common/error/GlobalExceptionHandler.java:24-60`；`server/src/main/java/com/intelligentresume/common/api/ApiResponse.java:5-18`；`web/src/views/AccountView.vue:59-68`；`web/src/views/GenerationWorkbenchView.vue:193-220`；`web/src/views/GenerationConfirmView.vue:60-63,146-176`；`web/src/views/MaterialSelectionConfirmView.vue:96-128,168-187`。内置浏览器本轮仅验证首页可加载，没有登录态，因此未制造真实后端错误响应。
- **建议验收**：将错误响应拆成稳定 `code`、可选 `messageKey`、结构化 `params`、traceId 和安全的 fallback 文案；Web 统一通过错误映射按当前 locale 渲染，不直接显示服务端自然语言，异步任务失败也使用受控 public error code。用中文/英文登录态分别触发校验、未授权、冲突、限流、资源不存在和任务失败，断言文案语言正确、参数插值稳定、未知 code 可安全回退，且日志仍保留 traceId 而不暴露敏感输入。

### 本轮范围与验证边界

- #66 是新的跨运行时错误表示与本地化问题，不替代 #13 的 AI 失败消息安全/长度审查，也不与 #56 的静态页面标签刷新问题合并。
- 内置浏览器只打开了 `http://localhost:5173/` 并确认首页渲染；未创建账号、未登录、未提交表单、未发送 AI/provider 请求、未调用 PDF renderer。结论来自服务端异常契约、Web 消费路径和语言资源的静态核对。

## 第十轮职业资料并发一致性审查（2026-09-04）

### 67. 职业资料更新缺少乐观锁，核心事实可能被最后写入覆盖

- **静态发现**：`CareerMaterial` 继承的 `BaseEntity` 只有 `id`、`createdAt`、`updatedAt`，没有 JPA `@Version`；`UpdateCareerMaterialRequest` 只包含可变字段，`PATCH /api/career-materials/{id}` 通过 `findOwned` 加载后直接修改并 `save`。前端详情模型虽然返回 `updatedAt`，但 `careerMaterial.ts` 的更新 payload 不携带它，服务端也没有 If-Match/ETag 或条件更新时间校验。
- **影响**：同一资料在两个标签页、两个设备或未来导入/同步任务中同时编辑时，后提交的完整字段可能静默覆盖先提交的内容，用户无法知道自己的职业事实已被覆盖。随着资料被更多 AI 任务、证据关联和协作入口复用，最后写入语义会扩大数据丢失和历史可追溯性风险，属于 P2 核心数据一致性与扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/common/persistence/BaseEntity.java:20-42`；`server/src/main/java/com/intelligentresume/careermaterial/domain/CareerMaterial.java:24-57`；`server/src/main/java/com/intelligentresume/careermaterial/dto/UpdateCareerMaterialRequest.java:6-13`；`server/src/main/java/com/intelligentresume/careermaterial/service/CareerMaterialService.java:118-145`；`server/src/main/java/com/intelligentresume/careermaterial/controller/CareerMaterialController.java:70-77`；`web/src/api/careerMaterial.ts:41-54`。现有服务/控制器测试覆盖正常更新和归属隔离，但没有并发更新或版本冲突断言。
- **建议验收**：为职业资料引入明确的版本字段或 HTTP ETag/If-Match 契约；更新必须携带读取时的版本，版本不匹配返回稳定 `409 CONFLICT` 且不覆盖数据。用两个事务读取同一资料后交错更新标题、结构化内容、来源原文和偏好，断言只有一个更新成功、失败方能刷新重试；同时验证软删除、历史 `source_snapshot_json` 和列表 `updatedAt` 排序不被破坏。若产品明确接受最后写入，也应把该决策记录为契约并在 UI 提示，而不是保持隐式行为。

### 本轮范围与验证边界

- #67 不重复 #24（面试答案资产并发创建）、#25（模板使用计数丢失更新）或 #38（简历当前版本指针竞争）；它关注职业资料正文这一独立核心聚合的更新冲突。
- 本轮未修改实体/迁移/API、未注入并发事务、未改变测试数据、未发送 AI/provider 请求、未调用 PDF renderer。结论来自实体、请求 DTO、服务更新路径和前端 payload 的静态核对。

## 第十一轮隐私与可观测性边界审查（2026-09-04）

### 68. PDF 导出 debug 日志直接记录 `userId`

- **静态发现**：`ExportService.create` 在任务创建后记录 `log.debug("Export task created: id={}, userId={}, versionId={}", task.getId(), userId, req.resumeVersionId())`。`PROJECT_CONTEXT.md` 的 AI 与隐私边界明确要求用户 ID不得写入日志、指标标签、测试报告或 Git；当前日志模板违反该约束。日志级别虽然是 DEBUG，但生产日志级别、采集代理和临时排障配置可能改变它的可见范围。
- **影响**：集中日志、备份或第三方日志检索系统可能长期保存可关联到账户的标识；随着多实例、异步 worker 和外部日志平台扩展，删除账号或数据保留策略也更难保证日志中没有残留用户标识。任务 ID、版本 ID和 trace ID已经足够支持内部排障，无需额外输出 `userId`，属于 P2 隐私与可观测性扩展风险。
- **代码证据**：`PROJECT_CONTEXT.md:49-50`；`server/src/main/java/com/intelligentresume/export/service/ExportService.java:87`。本轮只做静态日志模板扫描，没有启动生产采集、读取日志文件或执行 PDF 导出。
- **建议验收**：删除日志中的 `userId`，保留 taskId/versionId/traceId 等非用户身份关联字段；对全仓库日志模板和指标标签做静态门禁，禁止 `userId`、邮箱、电话、prompt、resume/JD 原文及 token/key 出现。用测试 appender 触发创建、worker 成功/失败、过期和重试路径，断言日志事件不含用户标识或敏感正文，同时保留可通过 taskId 追踪完整生命周期的能力。

### 本轮范围与验证边界

- #68 是对既有隐私边界的直接违例，不与 #13 的 AI 失败文案泄露或 #35 的 AI prompt 联系方式泄露合并；它发生在 PDF 创建日志而非模型输入/错误响应。
- 本轮未修改日志代码、未执行 PDF 导出、未读取外部日志或改变数据；结论来自项目上下文约束和源码日志模板核对。

## 第十二轮 JD 派生数据一致性审查（2026-09-04）

### 69. 修改 JD 原文后旧解析结果仍被标记为有效

- **静态发现**：`JobDescriptionService.update` 修改 `jdText` 时没有清空 `parsedKeywordsJson`、`parsedAt` 或 `parsedVersion`；`JobDescriptionView` 仅根据 `parsedAt` 显示“已解析”。随后调用 `parse` 时，服务读取当时的 `jdText`，解析完成后无条件写回派生字段，没有原文版本、内容 hash 或条件更新时间校验。
- **影响**：用户编辑岗位描述后，详情/列表仍可能展示旧关键词和旧解析时间，无法区分“当前原文未解析”和“解析结果属于上一版原文”。如果编辑与解析请求交错，慢的旧解析还可能覆盖新原文对应的派生结果；未来异步解析、规则版本升级或多实例部署会扩大错误岗位画像的传播范围，属于 P2 派生数据一致性与扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/jobdescription/service/JobDescriptionService.java:82-96,119-139`；`server/src/main/java/com/intelligentresume/jobdescription/domain/JobDescription.java:33-44`；`server/src/main/java/com/intelligentresume/jobdescription/controller/JobDescriptionController.java:52-74`；`web/src/views/JobDescriptionView.vue:76-94,126-127`。现有解析测试覆盖单次解析结果，但没有更新后失效、原文 hash 或交错更新/解析测试。
- **建议验收**：更新 `jdText` 时显式让解析状态失效，或为解析结果保存 `parsedForContentHash`/原文版本；解析写回必须使用条件更新，只有仍对应同一原文版本的结果才能提交。覆盖“编辑后列表状态”“重复解析”“编辑与解析交错”“规则版本变化”和旧任务晚到等场景，断言旧关键词永不标记为当前有效结果。

### 本轮范围与验证边界

- #69 不重复 #9 的关键词词典误命中问题；#9 关注解析内容质量，#69 关注派生结果与当前 JD 原文之间的生命周期契约。
- 本轮未修改 JD 服务、未创建或修改岗位数据、未调用 AI/provider/PDF；结论来自更新、解析、DTO 和前端状态展示路径的静态核对。

## 第十三轮个人资料并发与幂等审查（2026-09-04）

### 70. 个人资料首次 upsert 竞态可能返回 500，后续编辑也缺少版本边界

- **静态发现**：`PersonalProfileService.upsert` 先按 `userId` 查询，查询为空时创建新实体，最后调用 `save`；数据库通过 `uk_personal_profile_user (user_id)` 保证每个用户只有一行，但服务没有捕获唯一键竞争并回读已创建记录。`PersonalProfile` 继承的 `BaseEntity` 没有 JPA `@Version`，请求和 Web payload 也没有版本/ETag/If-Match 字段，因此已有资料的并发完整更新仍是隐式最后写入。
- **影响**：新账号在两个标签页、网络重试或未来同步任务同时保存个人资料时，两个请求都可能看不到资料，失败请求在唯一键冲突后进入通用 500，而不是与 upsert 语义一致地返回成功/当前资料或稳定冲突；资料已存在时，姓名、联系方式、求职偏好等完整字段还可能被另一请求静默覆盖。个人资料包含联系方式等核心用户事实，随着简历导入和 AI 上下文入口增加，这会同时放大初始化可靠性和数据一致性风险，属于 P2。
- **代码证据**：`server/src/main/java/com/intelligentresume/personalprofile/service/PersonalProfileService.java:42-60`；`server/src/main/java/com/intelligentresume/personalprofile/domain/PersonalProfile.java:12-17`；`server/src/main/resources/db/migration/V13__personal_profile.sql:1-14`；`server/src/main/java/com/intelligentresume/personalprofile/dto/PersonalProfileRequest.java:8-20`；`server/src/main/java/com/intelligentresume/common/error/GlobalExceptionHandler.java:60-71`。现有 `PersonalProfileServiceTest` 和 `PersonalProfileControllerIT` 只覆盖串行创建/更新、校验和读取，没有真实数据库并发或唯一键竞争测试。
- **建议验收**：明确 `PUT` 的 upsert 幂等契约：首次并发保存时恰好一个实体被创建，其余请求应回读同一行或返回稳定的冲突/重试结果，不能泄露数据库异常；已有资料更新应携带版本或 ETag，过期版本返回 `409 CONFLICT` 且不覆盖最新字段。用两个事务交错保存完整资料和部分相邻字段，覆盖唯一键竞争、重复网络重试、软删除/账号删除边界，并验证联系方式不会进入日志或错误响应。

### 本轮范围与验证边界

- #70 不重复 #46 的用户注册/改邮箱唯一键竞态，也不重复 #67 的职业资料正文更新；它关注按用户单例的个人资料 upsert 初始化和个人资料聚合的版本契约。
- 本轮未修改个人资料实体、迁移、API 或测试数据，未注入并发事务，未发送 AI/provider 请求，未调用 PDF renderer；结论来自服务、实体、数据库唯一约束、异常映射和请求 DTO 的静态核对。

## 第十四轮 AI 授权事件顺序审查（2026-09-04）

### 71. AI 授权最新状态仅按毫秒时间排序，并发撤回可能读到错误事件

- **静态发现**：AI 同意采用 append-only 事件，但 `AiConsentService.current`、`withdraw` 和 `hasValidConsent` 都依赖 `findFirstByUserIdOrderByCreatedAtDesc`；`AiConsent.createdAt` 由应用层 `LocalDateTime.now()` 生成并落到 `DATETIME(3)`，查询没有 `id` tie-break、用户级事件序列或行锁/条件状态边界。两个事件在同一毫秒写入时，数据库返回哪条记录没有稳定契约；并发授权/撤回的提交顺序也没有被显式建模。
- **影响**：授权页面和任务创建可能对同一用户看到不同的最后状态：撤回已经完成时仍可能把较早的 `GRANTED` 视为最新并允许新的 AI 任务，或授权完成后仍暂时读到 `WITHDRAWN` 而拒绝合法任务。随着多实例、批量授权、撤回重试和更细数据类别策略增加，这会变成隐私控制与任务恢复之间的 P1/P2 状态一致性风险；正常串行授权/撤回不受影响。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/consent/service/AiConsentService.java:63-111`；`server/src/main/java/com/intelligentresume/ai/consent/repository/AiConsentRepository.java:13-20`；`server/src/main/java/com/intelligentresume/ai/consent/domain/AiConsent.java:24-64`；`server/src/main/resources/db/migration/V1__m1_m2_init.sql:155-169`。现有 `AiConsentServiceTest` mock 单条最新记录和串行事件，没有同毫秒事件、并发提交或撤回与任务创建交错测试。
- **建议验收**：定义每个用户授权事件的严格顺序和线性化点，至少让查询使用稳定的 `(created_at DESC, id DESC)` 规则，并在需要时使用用户级序列/锁或条件写入保证撤回与授权的提交顺序可见；撤回成功后新 AI 任务不得通过旧授权。用同一用户并发 grant/withdraw、重复撤回、撤回与任务创建交错和跨实例时钟偏差 fixture，断言 `current`、`hasValidConsent`、任务创建与审计列表收敛到同一个状态，且不因 tie-break 暴露原始数据。

### 本轮范围与验证边界

- #71 不重复 #10 的任务与数据类别映射缺失，也不重复 #34 的账号删除后任务未取消；它关注 append-only 授权事件自身的最新状态排序与并发线性化契约。
- 本轮未修改授权实体、仓储、迁移或任务策略，未发起真实 AI/provider 请求，未改变账号/同意数据，未注入并发事务；结论来自事件实体、时间精度、仓储派生查询和授权服务调用路径的静态核对。

## 第十五轮职业资料搜索资源边界审查（2026-09-04）

### 72. 职业资料搜索词没有服务端长度边界，并对长文本做无索引包含扫描

- **静态发现**：`CareerMaterialController.search` 直接接收没有 `@Size`/字符预算的 `q`，`CareerMaterialService.normalizeQuery` 只做 trim 和通配符转义，不限制长度；仓储查询随后对 `lower(material.title)` 和 `lower(coalesce(material.sourceText, ''))` 执行带前置 `%` 的 `LIKE`。其中 `sourceText` 是 `MEDIUMTEXT`，当前搜索没有全文索引、截断投影或查询预算；前端搜索框和 debounce 只能改善正常页面输入，不能约束直接 API 调用。
- **影响**：攻击者或误用客户端可以提交很长的搜索词，触发每次请求的字符串处理和数据库扫描；随着用户资料数量、来源原文和搜索并发增长，查询 CPU、磁盘读取和响应延迟会随正文总量放大，并可能与分页列表的宽行读取叠加。这是 P2 的资源保护与搜索扩展风险，正常短词小数据搜索仍可用。
- **代码证据**：`server/src/main/java/com/intelligentresume/careermaterial/controller/CareerMaterialController.java:52-62`；`server/src/main/java/com/intelligentresume/careermaterial/service/CareerMaterialService.java:84-100,302-309`；`server/src/main/java/com/intelligentresume/careermaterial/repository/CareerMaterialRepository.java:22-36`；`server/src/main/java/com/intelligentresume/careermaterial/domain/CareerMaterial.java:34-43`。当前测试覆盖筛选、排序和分页边界，但没有超长 `q`、长 `sourceText`、并发搜索或 `EXPLAIN` 基准。
- **建议验收**：定义服务端搜索词最大字符/字节数和空白语义，超限在 SQL 执行前返回稳定校验错误；明确搜索范围是标题/摘要投影还是全文检索，并为选定方案建立索引或专用搜索结构，避免在请求路径对 `MEDIUMTEXT` 做无界 `lower + leading-wildcard LIKE`。用 1/10 万条资料、短词/边界词/超限词、短文/接近上限原文和并发请求执行 `EXPLAIN`、响应延迟、扫描行数、CPU/内存基准，确认分页仍保持稳定排序和用户隔离。

### 本轮范围与验证边界

- #72 不重复 #11 的通用列表分页缺口或 #47 的职业资料摘要宽行读取；它聚焦搜索入口自身缺少输入预算和对 `MEDIUMTEXT` 的查询算法选择。
- 本轮未执行大数据压测、未修改搜索代码/数据库索引、未改变资料或账号数据，未发送 AI/provider 请求，未调用 PDF renderer；结论来自 controller、service、repository、实体字段类型和前端输入路径的静态核对。

## 第十六轮沟通模板编辑一致性审查（2026-09-04）

### 73. 自定义沟通模板更新缺少版本条件，正文可能被最后写入覆盖

- **静态发现**：`CommunicationTemplateService.update` 通过 `ownedCustom` 加载实体后直接替换场景、语言、名称、描述和 `bodyText` 并保存；`CommunicationTemplate` 继承的 `BaseEntity` 没有 JPA `@Version`，`UpdateTemplateRequest`、`TemplateSummaryResponse`/`TemplateDetailResponse` 和 Web `TemplatePayload` 都没有版本或 ETag/If-Match 字段。模板使用计数的并发丢失更新已在 #25 记录，本项只关注自定义模板正文编辑。
- **影响**：同一用户在两个标签页或设备上分别编辑模板时，后提交的完整模板对象可能静默覆盖先提交的正文、语言和占位符集合，用户无法知道自己的模板变更已被覆盖；随后预览、AI 草稿和模板库排序会基于不可预期的版本。随着模板版本、团队外的个人同步或批量导入能力增加，这会成为 P2 内容一致性和编辑扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/communication/service/CommunicationTemplateService.java:87-97`；`server/src/main/java/com/intelligentresume/communication/domain/CommunicationTemplate.java:14-46`；`server/src/main/java/com/intelligentresume/communication/dto/UpdateTemplateRequest.java:13-20`；`server/src/main/java/com/intelligentresume/communication/controller/CommunicationController.java:91-97`；`web/src/api/communication.ts:28-59,92-98`。现有 `CommunicationTemplateServiceTest` 覆盖本人更新、他人模板和系统模板保护，但没有交错更新或版本冲突测试。
- **建议验收**：为自定义模板返回并要求携带 `version` 或 ETag；更新使用条件写入，过期版本稳定返回 `409 CONFLICT`，不得覆盖最新正文。用两个编辑者交错修改正文、占位符、语言和场景，验证预览/列表只反映成功提交的版本；同时确认 `usage_count` 的原子计数策略与正文版本更新不会互相覆盖，并覆盖删除与更新竞态。

### 本轮范围与验证边界

- #73 不重复 #25 的系统模板使用计数丢失更新，也不重复 #64 的模板列表/预览异步旧响应；它关注自定义模板聚合的正文编辑版本契约。
- 本轮未修改模板实体、迁移、API 或测试数据，未注入并发更新，未发送 AI/provider 请求，未调用 PDF renderer；结论来自模板服务、实体基类、请求/响应 DTO、控制器和 Web API 类型的静态核对。

## 第十七轮文本资源边界与面试记录顺序审查（2026-09-04）

### 74. 沟通模板和草稿正文缺少服务端长度契约，超出 TEXT 边界可能落成 500

- **静态发现**：`SaveTemplateRequest.bodyText`、`UpdateTemplateRequest.bodyText` 和 `SaveDraftRequest.draftText` 都只有 `@NotBlank`，没有字符/UTF-8 字节预算；`CommunicationTemplateService` 只做占位符校验后 `saveAndFlush`，`CommunicationService.saveDraft` 也直接保存请求正文。数据库迁移把 `communication_template.body_text` 与 `communication_draft.draft_text` 定义为 MySQL `TEXT`，上限约为 65535 字节；Web 模板编辑器的正文 `textarea` 也没有 `maxlength`。全局 JSON 请求大小限制不能替代字段和列边界，且中文/emoji 的字符数与 UTF-8 字节数不同。
- **影响**：直接 API 调用、未来导入或异常长的生成结果可以通过页面限制，进入占位符正则扫描、JPA 序列化和数据库写入；超过 `TEXT` 字节边界时可能触发数据截断/完整性异常并由通用处理器返回 500，而不是在持久化前给出稳定校验错误。即使正文未超列上限，无界正文也会放大模板详情、预览和草稿保存的内存/响应成本。这是 P2 输入资源与跨运行时存储契约风险；它不同于 #44 的通用 AI JSON 输入预算、#50 的列表宽行读取和 #73 的并发覆盖。
- **代码证据**：`server/src/main/java/com/intelligentresume/communication/dto/SaveTemplateRequest.java:14-19`；`server/src/main/java/com/intelligentresume/communication/dto/UpdateTemplateRequest.java:14-19`；`server/src/main/java/com/intelligentresume/communication/dto/SaveDraftRequest.java:11-16`；`server/src/main/java/com/intelligentresume/communication/service/CommunicationTemplateService.java:72-82,88-97`；`server/src/main/java/com/intelligentresume/communication/service/CommunicationService.java:75-95`；`server/src/main/resources/db/migration/V23__career_loop_enhancements.sql:12-24`；`server/src/main/resources/db/migration/V17__communication_draft.sql:1-12`；`web/src/views/CommunicationView.vue:571-576`。现有模板测试只覆盖正常短正文和占位符语义，没有 ASCII/CJK/emoji 边界、超限请求或“拒绝后没有半成品/计数变化”的断言。
- **建议验收**：为模板正文和草稿正文分别定义最大字符数与最大 UTF-8 字节数，并让 DTO、服务层和 Web 共享/生成同一份可审计契约；在 SQL/JPA 写入前返回稳定校验错误，不能依赖 MySQL 截断行为。以 ASCII、中文、emoji、合法边界和超限输入覆盖创建、更新、预览、AI 草稿保存，断言超限请求不写入数据库、不增加模板使用计数，且手动确认语义不被改变。

### 75. 面试记录读取只按 `created_at` 排序，已有 `round_no` 不能保证同毫秒顺序

- **静态发现**：`interview_record` 已有 `round_no`，V18 回填时明确使用 `ORDER BY created_at, id` 生成轮次；但 `InterviewRecordRepository.findBySessionIdOrderByCreatedAtAsc`、分数 projection 查询和 JPQL 历史读取仍只按 `createdAt ASC`。`InterviewStateAssembler` 用该结果的最后一条构造最近评估，`InterviewReportService` 用它生成报告轮次，`InterviewPromptContextAssembler` 用它拼接下一轮 AI 的 Conversation History。表的时间精度是 `DATETIME(3)`，没有把 `roundNo` 或 `id` 作为稳定的第二排序键。
- **影响**：同一会话的快速规则作答、重试完成或高并发多实例写入可能落在同一毫秒；数据库对相同 `created_at` 的返回顺序没有业务契约。这样最近评估可能不是最高轮，报告的轮次展示和后续 AI 历史上下文可能逆序，进而让用户看到错误的上一轮反馈或让模型接收不稳定的对话顺序。正常间隔较大的单次回答不受影响，但会话轮数、规则模式和异步评估扩展后风险会上升，属于 P2 状态读取与跨能力顺序契约风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/interview/repository/InterviewRecordRepository.java:12,23-28`；`server/src/main/java/com/intelligentresume/interview/service/InterviewStateAssembler.java:45-52`；`server/src/main/java/com/intelligentresume/interview/service/InterviewReportService.java:70-75`；`server/src/main/java/com/intelligentresume/interview/service/InterviewPromptContextAssembler.java:86-101`；`server/src/main/java/com/intelligentresume/interview/domain/InterviewRecord.java:13,24-25`；`server/src/main/resources/db/migration/V7__interview_session.sql:18-28`；`server/src/main/resources/db/migration/V18__interview_record_round.sql:1-10`。现有面试测试验证串行轮次和报告聚合，但没有构造相同 `created_at` 的记录并断言报告、最近评估和 prompt 历史顺序。
- **建议验收**：所有面向顺序的记录读取统一按 `roundNo ASC, id ASC`（或等价的显式序列）排序；分数 projection 若不需要顺序也应移除误导性的时间排序，或明确其稳定 tie-break。用相同时间戳、规则/AI 混合评估、重试晚到和会话刷新 fixture，断言最高轮始终是最近评估，报告轮次与 `round_no` 一致，且发送给 provider 的历史顺序稳定。

### 本轮范围与验证边界

- #74 不重复 #44 的通用 AI 任务输入预算、#50 的摘要宽行读取或 #73 的模板并发版本缺失；它关注沟通正文与数据库 `TEXT` 列之间的服务端大小契约。
- #75 不重复 #71 的 AI 授权事件 tie-break；它关注面试记录已有 `round_no` 却未用于读取排序的跨页面/AI 上下文顺序。
- 本轮只做静态审查，未写入超长模板/草稿、未制造同毫秒面试记录、未启动 AI/provider 请求、未调用 PDF renderer，也未修改业务代码或测试数据。正常路径验证仍不能关闭字节边界、数据库异常映射和同毫秒排序验收。

## 第十八轮异步响应与派生结果幂等审查（2026-09-04）

### 76. 成就引导把异步任务创建响应当成同步问题列表，成功路径会在前端失败

- **静态发现**：后端 `InlineOptimizeController.guide` 返回 `202 Accepted` 和 `AiTaskStatusResponse`，创建的是 `ACHIEVEMENT_GUIDANCE` 任务，响应字段包含 `id`、`taskType`、`status`、`resultJson` 等；Web 的 `guideAchievement` 却把同一响应声明为 `AchievementGuidanceResponse`，`AchievementGuidanceView.guide` 随即读取 `data.questions` 并调用 `questions.value.map(...)`。任务创建响应本身没有 `questions` 字段，真正的问题列表只会在 worker 成功后进入任务的 `resultJson`。
- **影响**：成就引导请求即使已经成功入队，页面也会把 `questions` 设为 `undefined` 并在 `.map()` 处抛错，随后显示通用生成失败文案；后台任务仍可能继续消耗配额并完成，但页面没有保存/轮询 task ID 的路径来读取结果。该错误直接影响一个已暴露的功能入口，属于 P1/P2 异步契约和用户流程可靠性风险。它不同于 #42 的首页恢复类型不完整和 #59 的内联润色等待窗口过短：本项是成就引导创建接口与前端读取模型不一致。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/optimize/controller/InlineOptimizeController.java:51-62`；`server/src/main/java/com/intelligentresume/ai/task/dto/AiTaskStatusResponse.java:13-26`；`web/src/api/ai.ts:116-119,204-205`；`web/src/views/AchievementGuidanceView.vue:22-27`；现有 `InlineOptimizeControllerIT.validAchievementGuidanceReturns202` 只断言 202 和 `taskType`，没有验证 Web 消费该响应或成功任务的 `resultJson` 形状。
- **建议验收**：统一采用当前项目的异步任务接口：前端创建后保存 task ID，使用共享轮询读取 `SUCCESS.resultJson` 中的 `questions`，覆盖失败、超时、刷新恢复和重试；或者若产品明确要同步接口，则必须改变后端状态码和实现，不能只修改 TypeScript 类型。增加服务端 JSON 契约测试、Web 组件/浏览器测试和一个可控 mock provider fixture，断言 `PENDING/RUNNING -> SUCCESS` 后问题列表可编辑，失败不会留下不可见的后台任务，超时后继续查看不会重复入队。本轮未发送 AI/provider 请求。

### 77. 内联润色和成就引导由服务端随机生成幂等键，网络重试会重复入队并重复消耗配额

- **静态发现**：`InlineOptimizeController.optimize` 和 `guide` 都把 `java.util.UUID.randomUUID().toString()` 直接传给 `AiTaskService.create`；控制器没有读取 `Idempotency-Key`，Web 的 `inlineOptimize` 与 `guideAchievement` 也不发送该 header。虽然 `ai_task` 有 `(user_id, task_type, idempotency_key)` 唯一约束和通用幂等查询，但每次重试都会得到新键，因此重试无法命中原任务。
- **影响**：响应丢失、页面超时、刷新、双标签页或用户重复点击时，相同的简历版本/章节/正文会创建多个独立 AI 任务；配额检查和 provider 调用也会按多个任务计算。#59 已记录内联润色在 30 秒后没有恢复入口，但本项关注即使请求重试发生在接口返回前/后，系统也没有逻辑请求身份；#48 关注调用方已提供相同幂等键时的并发唯一键异常，本项则是入口根本丢弃了幂等键。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/optimize/controller/InlineOptimizeController.java:38-61`；`server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java:73-98`；`server/src/main/resources/db/migration/V1__m1_m2_init.sql:171-194`；`web/src/api/ai.ts:188-205`；`web/src/views/ResumeEditorView.vue:458-475`；`web/src/views/AchievementGuidanceView.vue:22-27,51-57`。现有控制器测试验证任务创建和授权/输入校验，但没有模拟响应丢失、重放或并发提交并断言任务数与配额次数。
- **建议验收**：让两个入口接受并规范化 `Idempotency-Key`，同一用户、任务类型、键和请求指纹只返回同一任务；键相同而指纹不同稳定返回冲突，客户端在响应丢失时可安全重放。若产品需要“同样输入重新生成新候选”，应由客户端显式生成新的逻辑键，而不是让 HTTP 重试隐式产生新任务。用串行重放、两事务并发、超时后重试、同键不同正文和双标签页 fixture 验证只消耗一次逻辑配额，并覆盖任务完成后刷新恢复。

### 78. 规则评分结果缺少重放幂等与历史版本语义，重复请求会无限追加等价结果

- **静态发现**：`MatchRequest` 只有 `resumeVersionId` 和 `jobDescriptionId`，`ScoringController` 没有 `Idempotency-Key`；`ScoringService.score` 每次计算后都无条件 `matchResultRepository.save(result)`。`match_result` 仅有按简历版本和 JD 的普通索引，没有请求指纹、规则版本参与的唯一/复用约束；Web `scoreMatch` 也只发送 JSON 正文。设计允许一个简历版本拥有多个评分结果，但没有区分“同一次 HTTP 重放”与“用户明确要求按新规则重新计算”。
- **影响**：网络重试、刷新或多标签页会为同一输入持续创建等价行，数据库、备份和未来历史查询会随重试次数增长；另一方面，当 JD 原文或规则版本变化时，简单按 `(resumeVersionId, jobDescriptionId)` 复用又可能返回过期结果。当前页面单次按钮有 `runningAction` 防抖，但不能约束直接 API、跨标签页或响应丢失重试。这是 P2 派生结果留存、幂等和规则演进风险，不与 #32 的归档版本消费不一致或 #69 的 JD 解析新鲜度问题重复。
- **代码证据**：`server/src/main/java/com/intelligentresume/scoring/dto/MatchRequest.java:8-11`；`server/src/main/java/com/intelligentresume/scoring/controller/ScoringController.java:34-44`；`server/src/main/java/com/intelligentresume/scoring/service/ScoringService.java:126-143`；`server/src/main/java/com/intelligentresume/scoring/domain/MatchResult.java:18-52`；`server/src/main/resources/db/migration/V1__m1_m2_init.sql:136-153`；`web/src/api/scoring.ts:21-25`；现有评分测试只验证单次写入和读取，没有重复提交、规则版本变化或结果留存上限测试。
- **建议验收**：先定义评分读模型：精确重放应按请求键/输入指纹返回原结果，明确重算才创建新的 revision；指纹至少绑定简历版本内容身份、JD 内容版本/哈希和 `ruleVersion`，不能只依赖两个外键。为历史结果提供稳定排序、分页或保留策略，并在数据库层为确定性复用建立可验证约束。用同键重放、同输入不同键、JD 编辑、规则版本升级、并发提交和清理/分页 fixture 断言既不误复用旧结果，也不因网络重试无界增长。本轮未写入评分数据。

### 本轮范围与验证边界

- #76 不重复 #42 的任务收件箱缺口、#59 的内联润色轮询窗口或 #10 的 AI 数据类别映射；它首先是 `202 + taskId` 与 Web 同步结果类型之间的已确认功能契约错误。
- #77 不重复 #48 的相同幂等键数据库竞态或 #49 的幂等键长度/规范化分散；它关注两个入口用随机 UUID 让每次 HTTP 重试天然变成新任务。
- #78 不重复 #14 的 PDF 导出幂等、#32 的归档版本策略或 #69 的 JD 派生解析新鲜度；它关注规则评分派生行的重放、历史和规则版本语义。
- 本轮仍为静态审查，未发送 AI/provider 请求、未写入评分/任务数据、未调用 PDF renderer、未改变账号或浏览器状态，也未修改业务代码。现有正常路径测试和 Web 构建不能关闭异步前端读取、真实重放/并发、规则版本演进和大数据留存验收。

## 第十九轮可扩展能力注册审查（2026-09-04）

### 79. 简历模板代码在 Web、Java 和 PDF runtime 重复登记，新增模板可能只接通半条链路

- **静态发现**：当前七个模板代码分别登记在 `ResumeEditorView.vue` 的 `templateOptions`、Java `ResumeTemplateCodes.SUPPORTED`、`CreateExportRequest.@Pattern`、PDF `TEMPLATE_STYLES`/`TEMPLATE_CODES` 和 PDF 服务的能力说明中。它们现在内容一致，但不存在一个跨 runtime 的 capability manifest 或机器校验，新增模板必须手动同步 Web 选项、简历 JSON fallback、导出校验、PDF renderer 和测试。
- **影响**：漏改任一 implementation 会产生“编辑器可选但导出接口拒绝”“Java 接受但 PDF renderer 拒绝”或未知模板静默回退等不一致。模板数量、版本、灰度发布或未来第三方模板增加后，发布检查会从一个小 interface 变成多处人工记忆，属于 P2/P3 的展示能力扩展与发布一致性风险；当前七模板正常不等于注册接缝具备扩展性。
- **代码证据**：`web/src/views/ResumeEditorView.vue:190-198`；`server/src/main/java/com/intelligentresume/resume/service/ResumeTemplateCodes.java:5-15`；`server/src/main/java/com/intelligentresume/export/dto/CreateExportRequest.java:9-12`；`pdf-service/src/templates/classic.js:18-63`；`pdf-service/src/server.js:30-36,54-58`；`pdf-service/test/templates.test.js:12-34`。既有 #37 关注 Web/PDF 的语言与栏目展示漂移，本项只关注模板代码的可用性登记和发布接缝。
- **建议验收**：建立包含 code、label key、版本和 renderer capability 的 manifest 或生成物；每个 runtime 只实现 adapter，并用跨 runtime contract test 断言“可选、可保存、可导出、可渲染”一致。测试新增模板、未知模板、旧版本 fallback 和 renderer 不可用时的稳定错误语义。

### 80. 沟通载体、场景和占位符是封闭且分散的能力集合，新增沟通形式需要修改多个实现

- **静态发现**：`CommunicationType`、`TemplateScene`、`TemplatePlaceholderService.PLACEHOLDER_WHITELIST`、`CommunicationService` 的中英文 deterministic draft switch、`CommunicationAiPromptBuilder` 的 EMAIL subject 规则、`CommunicationAiResultValidator`、V23/V24 模板种子、Web `communication.ts` union 和 `CommunicationView` label map 分别维护沟通能力。当前不存在一个声明 output shape、可用占位符、模板 fallback、AI 校验规则和展示元数据的 capability interface。
- **影响**：增加 LinkedIn 消息、短信、跟进专用主题或新的事实占位符时，容易只更新枚举而漏掉 prompt、validator、模板种子、Web 类型或投递映射；届时可能出现能保存却不能预览、AI 主题规则错误或 Web 无法选择的半通路。#39 的空白占位符执行 bug、#73 的模板并发更新和 #74 的正文长度边界分别关注运行时行为，本项关注能力扩展的 locality，属于 P2/P3 的沟通产品扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/communication/domain/CommunicationType.java:1-3`；`server/src/main/java/com/intelligentresume/communication/domain/TemplateScene.java:7-13`；`server/src/main/java/com/intelligentresume/communication/service/TemplatePlaceholderService.java:20-30,77-91`；`server/src/main/java/com/intelligentresume/communication/service/CommunicationService.java:134-165`；`server/src/main/java/com/intelligentresume/communication/service/CommunicationAiPromptBuilder.java:44-55`；`server/src/main/java/com/intelligentresume/communication/service/CommunicationAiResultValidator.java:16-29`；`web/src/api/communication.ts:3-6`；`web/src/views/CommunicationView.vue:69-81`；`server/src/main/resources/db/migration/V23__career_loop_enhancements.sql:42-88`。
- **建议验收**：建立沟通 capability module，按载体声明主题/正文规则、语言、placeholder resolver、模板 fallback 和投递映射；AI、模板和 Web 只消费该 interface。用新增载体的 contract fixture 验证枚举解析、模板预览、纯模板生成、AI schema 校验、草稿保存和投递导入全部闭环。

### 本轮报告与范围

- 本轮架构候选报告已生成并打开：`<architecture-review-temp.html>`。报告保留既有首要候选“AI task capability module”，并新增“简历模板 capability registry”和“沟通 capability registry”。
- #79 不重复 #37 的多语言 PDF presentation module，也不重复 #53–#55 的简历章节 projection 白名单；它关注模板代码在多个 runtime 的注册与 renderer 可用性契约。
- #80 不重复 #39 的占位符空白语法、#73 的模板编辑并发或 #74 的正文长度；它关注新增沟通能力需要跨枚举、prompt、validator、seed 和 Web 类型手工同步。
- 本轮仍为静态审查，未修改业务代码、数据库迁移或测试数据，未发送 AI/provider 请求，未调用 PDF renderer，未改变账号或浏览器状态。架构报告中的前后图是候选方向，不代表已实施。

## 第二十轮导入扩展接缝审查（2026-09-04）

### 81. TXT、PDF、DOCX 解析全部耦合在 `ResumeImportService`，新增文件格式会扩大入口 module 的浅接口

- **静态发现**：`ResumeImportService` 同时维护扩展名/媒体类型白名单、文件大小校验、`switch (extension)` 分派、TXT 字节读取、PDFBox 解析、POI DOCX 解析、文本清理和姓名/邮箱/电话归一化。当前已有三个真实文件格式，且 `ResumeImportServiceTest` 已分别覆盖三种 parser，但没有 `DocumentParser` adapter 或按格式声明 capability、资源预算和错误语义的注册接缝。
- **影响**：新增 ODT、HTML、图片 OCR 或其他格式时，维护者必须修改同一入口的白名单、switch、依赖解析实现和测试；若某格式的展开量/页数/超时规则不同，也容易与现有通用大小校验混在一起。解析器异常、媒体类型、归一化结果都经过同一个实现，导致格式扩展的 locality 很差，属于 P2/P3 的输入能力扩展风险；当前 TXT/PDF/DOCX 正常不代表新增格式能安全接入。
- **代码证据**：`server/src/main/java/com/intelligentresume/imports/service/ResumeImportService.java:19-58`；`server/src/main/java/com/intelligentresume/imports/service/ResumeImportService.java:61-75`；`server/src/test/java/com/intelligentresume/imports/service/ResumeImportServiceTest.java:43-84`；`web/src/api/resumeImport.ts:1-2`；`web/src/views/ResumeImportView.vue:26-56`。既有 #17 聚焦解析资源预算、ZIP 膨胀和超时防护，本项只聚焦已有多个 parser adapter 未形成可替换 seam。
- **建议验收**：建立导入 parser interface/registry（格式、媒体类型、资源预算、解析错误分类和文本结果），TXT/PDF/DOCX 各自作为 adapter；入口只负责选择 adapter、统一大小/超时/审计语义，归一化作为独立且可测试的内部 seam。用新增 synthetic adapter、错误媒体类型、损坏输入、超预算和 parser 超时 fixture 验证未知格式稳定拒绝，现有格式行为不变。

### 本轮报告与范围

- 本轮架构候选报告已生成并打开：`<architecture-review-temp.html>`。报告比较 parser adapter registry、AI task capability module 和跨 runtime contract module 三个方向。
- #81 不重复 #17 的导入资源边界、#8 的来源追溯或 #44 的通用 AI 输入大小；它关注三个已存在的格式实现没有通过一个深 module 接收和隔离。
- 本轮仍为静态审查，未修改导入源码、解析依赖、迁移或测试数据，未上传文件、未调用 AI/provider、未调用 PDF renderer、未改变账号或浏览器状态。报告中的 registry 是候选方向，不代表已经实施。

## 第二十一轮面试模式策略审查（2026-09-04）

### 82. `InterviewMode` 只作为元数据传递，规则降级没有 mode strategy 接缝

- **静态发现**：服务端 `InterviewMode` 已声明 `JD_TARGETED`、`TECHNICAL`、`BEHAVIORAL`、`COMPREHENSIVE` 四种值，启动时写入 `InterviewSession`，AI 上下文也会把它拼入 prompt；但 `InterviewRuleService` 调用 `InterviewRuleEngine.RULE_FIRST_QUESTION`、`ruleScore(answer)` 和 `nextRuleQuestion(completedCount)` 时没有传入 `InterviewMode`。`InterviewRuleEngine` 只有一套 18 题 `RULE_TOPICS`、首题和评分 rubric，现有测试也只验证这套无 mode 参数的行为。
- **影响**：用户选择技术、行为、JD 针对性或综合面试后，如果 AI 失败切换规则模式，所有模式都会得到同一题库和同一评分口径；未来新增模式也只能继续修改共享常量或在入口堆叠条件。这样 mode 在 AI 路径有语义、在规则路径变成标签，属于 P2 面试策略一致性与扩展风险。它不同于 #41 的规则降级语言固定中文，也不同于 #42 的刷新后模式/语言状态丢失。
- **代码证据**：`server/src/main/java/com/intelligentresume/interview/domain/InterviewMode.java:1-3`；`server/src/main/java/com/intelligentresume/interview/service/InterviewRuleEngine.java:16-45`；`server/src/main/java/com/intelligentresume/interview/service/InterviewRuleService.java:79,139,174`；`server/src/main/java/com/intelligentresume/interview/service/InterviewPromptContextAssembler.java:62`；`web/src/api/interview.ts:6-13`；`web/src/views/InterviewView.vue:28,475`；`server/src/test/java/com/intelligentresume/interview/service/InterviewRuleEngineTest.java:54-61`。
- **建议验收**：建立 interview-mode strategy module，让每个 mode 声明首题、下一题 topic/rule、评分维度、问题数限制和展示元数据；AI prompt 与规则 adapter 共同消费该 interface。至少用四种 mode 的规则 fallback fixture 断言题目、评分维度和报告来源保持模式一致，并验证新增 synthetic mode 不需修改会话编排。

### 本轮报告与范围

- 本轮架构候选报告已生成并打开：`<architecture-review-temp.html>`。报告比较 interview-mode strategy registry、parser adapter registry 和 AI task capability module 三个方向。
- #82 不重复 #41 的输出语言契约、#42 的恢复状态契约或 #75 的记录排序；它聚焦规则执行 module 没有接收已有 `InterviewMode`，导致 mode 不能作为可替换策略扩展。
- 本轮仍为静态审查，未启动面试、未写入面试记录或 AI attempt，未发送 provider 请求，未修改面试源码、数据库迁移或测试数据。报告中的 strategy registry 是候选方向，不代表已经实施。

## 第二十二轮职业资料类型扩展审查（2026-09-04）

### 83. `MaterialType` 的 schema、表单、摘要、AI 投影和生成归类分散，新增职业资料类型容易只在单一路径生效

- **静态发现**：后端 `MaterialType` 已有 13 种类型，但 `CareerMaterialService.validateTypeSpecificContent` 只有 `ACHIEVEMENT`、`LEADERSHIP_EXPERIENCE`、`SKILL_EVIDENCE` 三个专用校验分支，`excerpt` 也只有这三类专用投影；Web `CareerMaterialForm` 只有这三类专用表单，其余类型落入自由 JSON/sourceText 表单。`CareerMaterialAiSnapshotSanitizer` 目前只对 `ACHIEVEMENT` 生成安全快照，`DraftCommitService.inferTypeFromPath` 又单独维护章节到资料类型的 fallback 映射，并将 `customSections` 映射为 `LEADERSHIP_EXPERIENCE`。
- **影响**：新增如语言证据、证书证据或其他事实类型时，若只更新枚举和 Web 选项，服务端可能当作通用 JSON，摘要没有有意义内容，AI 投影没有类型专属处理，生成确认又可能落到错误 fallback 类型；用户会看到资料可创建但选材、生成、检索或追溯语义不完整。这是 P2/P3 的核心事实库扩展与跨能力一致性风险，不与 #35 的 AI PII 投影、#36 的 prompt 预算或 #53–#55 的简历章节 projection 重复。
- **代码证据**：`server/src/main/java/com/intelligentresume/careermaterial/domain/MaterialType.java:8-21`；`server/src/main/java/com/intelligentresume/careermaterial/service/CareerMaterialService.java:169-177,326-335`；`server/src/main/java/com/intelligentresume/ai/generation/service/CareerMaterialAiSnapshotSanitizer.java:14-43`；`server/src/main/java/com/intelligentresume/ai/confirmation/service/DraftCommitService.java:277-293`；`web/src/components/career-material/options.ts:3-17`；`web/src/components/career-material/CareerMaterialForm.vue:60-63,112-119`；`web/src/api/careerMaterial.ts:3-16`。现有资料服务、选材和生成测试覆盖已支持类型的正常路径，没有用 synthetic 新类型验证各消费路径是否完整。
- **建议验收**：建立 career-material capability module，让每种类型声明 schema/表单元数据、关系约束、摘要投影、AI-safe projection 和生成归类；CRUD、搜索、选材、岗位生成、确认和 Web 表单只消费该 interface。用一个 synthetic 类型 fixture 验证创建/编辑、摘要、typeCounts、AI 输入、生成 `_sources`、确认快照和旧数据兼容，未注册类型必须稳定拒绝而不是静默降级。

### 本轮报告与范围

- 本轮架构候选报告已生成并打开：`<architecture-review-temp.html>`。报告比较 career-material capability registry、interview-mode strategy registry 和 parser adapter registry 三个方向。
- #83 不重复 #67 的职业资料并发更新、#47 的宽行列表读取或 #72 的搜索资源边界；它聚焦资料类型本身的能力声明没有通过一个深 module 统一消费。
- 本轮仍为静态审查，未创建/修改职业资料、未发送 AI/provider 请求，未调用 PDF renderer，未修改资料实体、迁移、前端表单或测试数据。报告中的 capability registry 是候选方向，不代表已经实施。

## 第二十三轮 JD / ATS 运行时契约审查（2026-09-04）

### 84. JD 解析结果的 envelope 与评分读取结构不兼容，成功解析结果会被忽略

- **静态发现**：`JobDescriptionService.parse` 将结果保存为 `{"version": "v1.0.0", "data": {"role": ..., "keywords": ..., "requirements": ...}}`；但 `ScoringService.score` 只在 `parsedKeywordsJson` 的顶层存在 `keywords` 时复用，否则直接重新调用 `JdKeywordParser.parse(jdText)`。因此通过正式解析接口得到的嵌套结果不会进入评分复用分支，ATS/评分每次都可能重新解析原文。
- **影响**：用户看到岗位已经“解析完成”，详情接口也能展示 `data.keywords`，但评分不会消费这份派生结果，解析按钮的结果与评分实际使用的输入不是同一个读模型。当前确定性 parser 下分数可能暂时相同，但会重复 CPU/数据库调用；解析规则或版本升级后，详情、已保存解析结果和评分结果还可能出现口径漂移，已有的解析版本字段也无法成为评分的实际 provenance。属于 P2 功能有效性、资源开销与派生数据扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/jobdescription/service/JobDescriptionService.java:105-123`；`server/src/main/java/com/intelligentresume/scoring/service/ScoringService.java:81-93`；`server/src/test/java/com/intelligentresume/jobdescription/controller/JobDescriptionControllerIT.java:110-115` 明确断言 `data.keywords`；`server/src/test/java/com/intelligentresume/scoring/service/ScoringServiceTest.java:159-173` 只覆盖 `parsed_keywords_json=null` 时的自动解析，没有覆盖“正式 parse 后再评分”。
- **建议验收**：建立一个共享的 `ParsedJdSnapshot` interface/adapter，统一 envelope、内容身份、parser 版本和关键词读取投影；评分只消费该 interface，并兼容迁移前的旧顶层结构。新增“parse → score”集成测试：当前解析结果有效时不再调用 parser，内容变更或版本失效时才重新解析；详情、ATS、评分和异步解析均断言使用同一份派生语义。

### 85. ATS AI prompt/schema 版本没有绑定任务快照，排队任务可能产生错误 provenance

- **静态发现**：`AtsService.createTask` 将创建时的 `promptVersion`、`schemaVersion` 写入 AI 任务输入；但 `AtsAiAnalysisService` 调用的 `AtsAiPromptBuilder` 使用 worker 当前注入的配置字段来构建 prompt 和 repair prompt，完成结果又把任务输入中的旧版本写回 `taskResult`。滚动发布、配置切换或长时间排队后，同一个任务可能实际使用 v2 prompt，却记录为创建时的 v1。
- **影响**：ATS 报告和任务结果的版本字段不能可靠回答“这次 provider 调用实际使用了哪套 prompt/schema”，重试、repair、回放和跨版本故障排查会失去 locality。若 schema 变更不向后兼容，任务还可能在新 validator 下失败后回退规则，用户只能看到统一的失败提示而无法区分部署漂移。属于 P2 异步任务审计、回放和发布一致性风险；当前单版本串行测试无法关闭它。
- **代码证据**：`server/src/main/java/com/intelligentresume/ats/service/AtsService.java:204-218`；`server/src/main/java/com/intelligentresume/ats/service/AtsAiPromptBuilder.java:19-24,87-95`；`server/src/main/java/com/intelligentresume/ats/service/AtsAiAnalysisService.java:32-50`；`server/src/main/java/com/intelligentresume/ai/worker/TaskExecutionService.java:242-249`。现有 `AtsAiAnalysisServiceTest` 使用同一进程、同一 prompt builder 版本，没有模拟旧任务由新 worker 执行。
- **建议验收**：把 prompt/schema policy 固化进 task snapshot，builder、validator、repair 和结果审计只消费该快照，或明确任务必须在同版本 worker 上执行；结果同时保存实际生效版本和策略 hash。用“v1 创建、v2 worker 执行”、repair、重试、租约接管和回放 fixture 验证版本字段与实际 prompt 一致，并明确不可兼容 schema 的稳定失败/重新入队策略。

### 86. ATS AI insight schema 在 Prompt、Java validator/DTO、Web 类型和 E2E fixture 中重复维护

- **静态发现**：顶层 key、嵌套字段、`MATCHED/PARTIAL/MISSING`、`P0/P1/P2`、长度/数量上限分别写在 `AtsAiPromptBuilder` 的自然语言 prompt、`AtsAiResultValidator` 的 key 集合与枚举集合、`AtsAiInsights` record、`web/src/api/ats.ts` 的 TypeScript union 以及 `web/e2e/ats-ai.spec.ts` fixture 中，没有版本化的机器可校验 response contract。
- **影响**：新增 insight 字段、枚举或约束时，维护者需要手动同步至少五个 interface；漏改会出现 provider 已返回但 validator 拒绝、Java 已持久化但 Web 类型/展示丢失，或浏览器 fixture 继续掩盖真实响应差异。严格顶层 key 校验使这种漏改直接变成规则降级，宽松的 Java Map/前端 `Record<string, unknown>` 又会让 `checks` 类扩展静默不显示，属于 P2/P3 AI 能力扩展与跨运行时契约风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/ats/service/AtsAiPromptBuilder.java:42-52`；`server/src/main/java/com/intelligentresume/ats/service/AtsAiResultValidator.java:15-18,27-64`；`server/src/main/java/com/intelligentresume/ats/dto/AtsAiInsights.java:5-25`；`web/src/api/ats.ts:3-35`；`web/e2e/ats-ai.spec.ts:4-33`。现有 validator 和 ATS E2E 用例验证当前 schema，但没有检查 Prompt、DTO、TypeScript 和 fixture 的集合一致性。
- **建议验收**：建立版本化 ATS insight contract/fixture，至少由它驱动或校验 prompt 字段、validator、Java 映射和 Web 类型；保留 provider 输出的严格未知字段拒绝和敏感证据校验。用新增可选字段、枚举升级、旧 schema 回放、缺字段/多字段和 Web 未识别字段 fixture 验证兼容策略，确保 schema 变更只需一个明确的 interface 变更点。

### 本轮报告与范围

- 本轮架构候选报告已生成并打开：`<architecture-review-temp.html>`。报告比较 JD 派生数据读取契约、ATS task-pinned prompt/schema policy 和 ATS insight contract 三个方向；首要推荐是先收敛 JD 解析结果读取契约，因为它已有可直接验证的功能失效。
- #84 不重复 #69 的“修改 JD 后旧解析结果仍标记有效”：#69 关注原文变更与派生结果的新鲜度，#84 关注即使没有原文变更，正式解析写入的 envelope 也无法被评分读取。它也不重复 #78 的评分重放/历史语义。
- #85 不重复 #78 的评分规则版本历史；它关注 ATS 异步任务创建版本与 worker 实际 prompt/schema 版本的漂移。
- #86 不重复 #80 的沟通能力注册分散或 #66 的错误本地化；它只关注 ATS AI 输出 schema 在 provider prompt、后端验证/DTO、Web 类型和测试 fixture 之间的跨运行时契约。
- 本轮仍为静态审查，未修改业务代码、数据库迁移、测试数据或浏览器状态，未发送 AI/provider 请求，未调用 PDF renderer，也未创建账号。现有单元测试、ATS E2E 和此前浏览器 smoke 只能证明当前串行 happy path，不能关闭 parse→score envelope、跨版本 worker、schema 漂移和契约一致性验收。

## 第二十四轮面试反馈契约扩展审查（2026-09-04）

### 87. 面试反馈 schema 在 AI、规则降级、报告聚合和 Web 之间重复维护，新增维度容易只接通部分路径

- **静态发现**：五个评分维度及其分值范围、反馈字段和数组数量/长度分别写在 `InterviewAiService` 的 prompt、`InterviewCoachResponse` 的 Bean Validation/DTO、`InterviewOperationSupport.buildAiFeedback` 的 Map 转换、`InterviewRuleService` 的规则降级 Map、`InterviewReportService` 的固定五维聚合、`InterviewStateAssembler` 的 Map 读取以及 Web `interview.ts`/视图的类型和展示映射中。`InterviewRecord.feedbackJson` 与 `InterviewAnswerAsset.feedbackJson` 仍是无 schema 的 JSON Map，E2E fixture 又复制一份字段集合。
- **影响**：新增评分维度、反馈字段或调整约束时，维护者需要手动同步多个 interface；漏改 AI prompt/validator 会让 provider 输出被拒绝，漏改降级或报告聚合会让规则路径与 AI 路径口径不同，漏改 Web/资产读取则会让数据持久化但不显示。当前五维 happy path 可用，但这个 cluster 的 locality 很弱，属于 P2/P3 面试能力扩展与跨运行时契约风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/interview/service/InterviewAiService.java:34-149`；`server/src/main/java/com/intelligentresume/interview/dto/InterviewCoachResponse.java:57-148`；`server/src/main/java/com/intelligentresume/interview/service/InterviewOperationSupport.java:147-167`；`server/src/main/java/com/intelligentresume/interview/service/InterviewRuleService.java:147-158`；`server/src/main/java/com/intelligentresume/interview/service/InterviewReportService.java:84-148`；`server/src/main/java/com/intelligentresume/interview/service/InterviewStateAssembler.java:70-105`；`web/src/api/interview.ts:19-94`；`web/src/views/InterviewView.vue:411-418`；`web/e2e/interview-report.spec.ts:39-58`。现有测试验证当前字段和五维聚合，但没有 synthetic 新维度/字段 fixture，也没有跨 AI、RULE、报告、资产和 Web 的 schema 一致性 gate。
- **建议验收**：建立版本化的 `InterviewFeedback` interface/adapter，集中声明维度元数据、分值范围、反馈字段和兼容策略；AI validator、规则降级、持久化映射、报告聚合和 Web contract test 只消费该 interface。保留历史 JSON 的向后兼容读取，并用 synthetic 新维度、旧记录回放、AI/RULE 混合报告、资产读写和 Web fixture 验证：新增字段要么显式进入所有消费者，要么按版本策略稳定忽略/降级，不能静默丢失。

### 本轮范围与验证边界

- #87 不重复 #82 的面试模式没有规则策略接缝、#54/#55 的简历章节上下文 projection，也不重复 #86 的 ATS 专属 insight schema；#87 关注面试反馈本身在 AI prompt、Java DTO/validator、规则降级、报告/状态 Map、资产 JSON 和 Web/E2E 之间重复维护。
- 本轮仍为文档型静态审查，未改动面试业务代码、DTO、数据库 schema、测试数据或浏览器状态，未发送 AI/provider 请求，未调用 PDF renderer，也未创建测试账号。当前面试测试、Web 构建和此前浏览器 smoke 只证明现有五维 happy path，不能关闭新增字段的跨路径一致性验收。

## 第二十五轮面试问题候选契约审查（2026-09-04）

### 88. 首题、下一题和薄弱项练习重复维护问题候选 schema，新增题型字段容易只接通一条路径

- **静态发现**：`InterviewCoachResponse.InitialQuestion` 与 `NextQuestion` 各自声明 `question`、`focus`、`expectedSignals`、`coverageTags` 及相同的 Bean Validation 约束；`InterviewFollowUpAiService.Candidate` 再次声明同一组字段和约束，follow-up Prompt 也复制一份 JSON schema。Web 端 `FollowUpCandidate` 另外维护同名 TypeScript 类型，`InterviewAiService` 的首题/评估 Prompt 又分别重复这些字段。首题和下一题走同步面试 attempt，follow-up 走异步 `INTERVIEW_COACH` task，只有 follow-up 结果有单独的 Web 读取路径。
- **影响**：以后增加难度、题型、来源证据、面试模式标签或题目有效期时，维护者需要同步多个 Java DTO、Prompt、Map 转换和 Web 结果类型；漏改会造成首题可返回、下一题被 validator 拒绝，或 follow-up provider 已返回但前端丢字段。当前四字段 happy path 没有声称失效，但问题候选的 interface 深度很浅，属于 P2/P3 面试题型与练习能力扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/interview/dto/InterviewCoachResponse.java:28-53,153-178`；`server/src/main/java/com/intelligentresume/interview/service/InterviewAiService.java:61-69,99-104`；`server/src/main/java/com/intelligentresume/interview/service/InterviewFollowUpAiService.java:67-88,273-305`；`web/src/api/interview.ts:111-123,167-171`；`web/src/views/InterviewView.vue:314-329`。现有 `InterviewAiServiceTest` 覆盖首题/评估的当前字段，`InterviewFollowUpAiServiceTest` 主要覆盖任务创建和授权，未覆盖 follow-up provider 输出到 Web 的完整 schema，也没有集合一致性 gate。
- **建议验收**：建立版本化 `InterviewQuestion` interface/adapter，集中声明题目字段、约束、语言和兼容策略；首题、下一题、follow-up Prompt/validator、任务结果和 Web contract 只消费该 interface。保留旧任务结果兼容读取，并用新增可选字段、旧 schema 回放、三种生成入口、异步轮询和 Web 编辑 fixture 验证：同一题目字段在首题/下一题/follow-up 中口径一致，未知必需字段能稳定拒绝，未知可选字段按版本策略处理，不能静默丢失。

### 本轮范围与验证边界

- #88 不重复 #87 的反馈/评分 schema，也不重复 #82 的面试模式策略、#42 的恢复后模式/语言漂移或 #76 的成就引导异步响应错误；#88 只关注“问题候选”这一可复用对象在首题、下一题和 follow-up 三条路径中的 interface 重复。
- 本轮仍为文档型静态审查，未修改面试业务代码、DTO、数据库 schema、测试数据或浏览器状态，未发送 AI/provider 请求，未调用 PDF renderer，也未创建测试账号。现有首题/评估测试、follow-up 创建测试、Web 构建和浏览器 smoke 不能关闭 follow-up 完整结果契约及新增题目字段的一致性验收。

## 第二十六轮面试任务 operation 接缝审查（2026-09-04）

### 89. `INTERVIEW_COACH` 用字符串 operation 分流异步任务，未知 operation 会静默落入通用 worker 路径

- **静态发现**：`AiTaskType.INTERVIEW_COACH` 同时被用作面试领域任务和薄弱项 follow-up 任务；`InterviewFollowUpAiService` 通过 `input.operation = "FOLLOW_UP_PRACTICE"` 写入任务，`TaskExecutionService` 再用 `isFollowUpPractice` 手工检查这个字符串后选择 follow-up adapter，否则统一进入 `executeDefault`。`executeDefault` 直接调用 provider 并保存原始结果，没有针对 operation 的 schema、结果 formatter 或未知 operation 拒绝策略。`AiTaskService.requiredCategories` 也按 task type 而不是 operation 维护策略。
- **影响**：新增面试摘要、模拟复盘或其他异步 operation 时，维护者必须同时修改创建输入、worker 字符串判断、同意/配额策略、结果 contract 和 Web 轮询读取；漏改时新 operation 可能被当作通用 `INTERVIEW_COACH` 任务执行，跳过专用校验/结果映射后仍标记成功，形成“任务成功但页面无法解释结果”的运行时风险。当前 follow-up happy path 可工作，但 operation seam 很浅，属于 P2/P3 面试异步能力扩展与错误隔离风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/task/domain/AiTaskType.java:6-16`；`server/src/main/java/com/intelligentresume/interview/service/InterviewFollowUpAiService.java:39-45,138-145,185-190`；`server/src/main/java/com/intelligentresume/ai/worker/TaskExecutionService.java:113-125,164-186,204-240`；`server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java:176-205`；`web/src/api/interview.ts:118-123,167-171`。现有 worker 测试覆盖已登记路径，没有 synthetic 未知 operation、第二种 interview operation 或 operation-specific result contract 的拒绝/路由测试。
- **建议验收**：建立 interview task operation registry/adapter；每个 operation 声明创建输入、同意/配额类别、Prompt/版本、结果 validator/formatter 和 Web result contract，worker 对未注册 operation 在 provider 调用前稳定拒绝。保留 `INTERVIEW_COACH` 的数据库兼容值，但把 operation 变成受版本约束的内部 interface。用 follow-up、synthetic 新 operation、未知 operation、重试、租约接管和旧任务回放 fixture 验证：每种任务只进入自己的 adapter，错误不会被通用路径标记成功。

### 本轮范围与验证边界

- #89 不重复 #10 的 AI 数据类别声明缺失、#76/#77 的异步响应与幂等问题、#82 的面试模式策略或 #88 的问题候选字段 schema；#89 只关注同一 `AiTaskType` 内部 operation 分流和 worker adapter 选择。
- 本轮仍为文档型静态审查，未修改任务枚举、worker、数据库 schema、测试数据或浏览器状态，未发送 AI/provider 请求，未调用 PDF renderer，也未创建测试账号。现有 follow-up 创建测试和正常 worker 路径不能证明未知 operation 会被拒绝或新 operation 能独立接入。

## 第二十七轮个人档案导入契约审查（2026-09-04）

### 90. 个人档案导入建议只读取 `basics`，简历 `objective` 与职业目标会被静默丢失

- **静态发现**：`PersonalProfileService.importSuggestion` 只读取当前简历版本 `resumeJson.basics`，返回个人档案时把 `targetRoleTitles`、`targetSeniority`、`targetIndustries`、`targetWorkPreferences` 和 `careerPositioningSummary` 固定设为 `null`。但简历 JSON 校验白名单允许 `objective`，简历编辑器也支持目标岗位、目标行业、地点偏好和求职说明；个人档案 API、资料库 UI 和 AI 选材/生成上下文同样把这些职业目标作为正式字段。Web 导入成功后还会用完整 suggestion 替换当前 profile，因而未保存的职业目标输入也会在导入动作中被空值覆盖。
- **影响**：用户选择一份已经包含 `objective` 的简历并点击“从已有简历提取建议”时，姓名、联系方式和概要可以出现，但已有的目标岗位/行业/工作偏好/定位摘要不会进入建议，用户只能重新填写；如果用户在页面上先编辑了目标字段再触发导入，当前会话中的未保存内容也会被替换。若产品有意只导入基础身份信息，这不是后端故障，但当前 API 类型和按钮文案没有声明该范围，属于 P2/P3 导入契约、数据保留和能力扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/personalprofile/service/PersonalProfileService.java:64-87`；`server/src/main/java/com/intelligentresume/resume/service/JsonResumeValidator.java:36-37`；`web/src/views/ResumeEditorView.vue:675,971-972`；`web/src/components/career-material/CareerProfileEditor.vue:45-55`；`web/src/views/CareerMaterialView.vue:369-380`；`server/src/main/java/com/intelligentresume/ai/selection/service/JobMaterialSelectionService.java:254-258`；`server/src/main/java/com/intelligentresume/ai/generation/service/JobGenerationService.java:509-510`。现有个人档案导入测试只断言 `basics` 字段，没有包含 `objective` 的版本 fixture，也没有断言导入动作对当前未保存职业目标的合并/覆盖语义。
- **建议验收**：先明确导入契约：若目标是完整建议，建立 `PersonalProfileImportMapper`/版本化 snapshot，把 `objective.targetRole`、`objective.targetIndustry`、`objective.location` 和 `objective.summary` 映射到对应职业目标字段，并定义单值到列表、空值和已有 profile 的合并规则；若目标只限基础身份，则将接口/UI 命名为基础信息导入，并保留当前 profile 的职业目标而不是用空值覆盖。增加包含 `basics + objective`、缺失 objective、旧 JSON 和并发/重复点击 fixture，断言建议预览、保存、AI 选材和生成上下文的语义一致。

### 本轮范围与验证边界

- #90 不重复 #70 的个人档案并发写入、#53–#55 的简历章节 projection 或 #83 的职业资料类型 capability；它只关注简历导入建议到个人档案字段之间的映射范围、保留策略和扩展接缝。
- 本轮仍为文档型静态审查，未修改个人档案、简历导入、简历 JSON schema、AI 选材/生成代码或测试数据，未发送 AI/provider 请求，未上传文件，未调用 PDF renderer，未创建账号，也未改变浏览器状态。当前单元/集成测试只证明 basics 的串行导入路径，不能关闭 objective 映射、未保存字段覆盖、旧 JSON 兼容和重复导入语义。

## 第二十八轮评分规则能力接缝审查（2026-09-04）

### 91. `RuleRegistry` 只注册三条固定规则，评分聚合与结果契约仍逐项硬编码

- **静态发现**：`RuleRegistry` 固定持有 `KeywordRule`、`SkillRule`、`ExperienceRule` 及三个独立权重；`ScoringService` 逐项调用这三条规则、逐项计算加权总分，并由 `buildSuggestions` 只接收关键词和技能结果。`MatchResult`/V1 表结构固定保存 `keyword_score`、`skill_score`、`experience_score`，`MatchResponse`、`Explanation`、Web `scoring.ts` 和 `MatchResultView` 又复制同一组三项字段与展示分支。
- **影响**：新增教育、语言、岗位级别或地点匹配规则时，维护者必须同时修改注册表、评分聚合、权重校验、数据库列或 JSON 结构、DTO、前端类型、展示和建议生成；只接通其中一处会出现规则已计算但未持久化/展示，或接口已增加字段但实际总分仍漏算的半通路。现有三条规则的串行 happy path 没有声称失效，但当前 `RuleRegistry` 的接口深度不足以隔离后续评分能力，属于 P2/P3 评分能力扩展与跨运行时契约风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/scoring/rule/RuleRegistry.java:12-41`；`server/src/main/java/com/intelligentresume/scoring/service/ScoringService.java:30,101-143,178-188`；`server/src/main/java/com/intelligentresume/scoring/domain/MatchResult.java:35-48`；`server/src/main/resources/db/migration/V1__m1_m2_init.sql:137-146`；`server/src/main/java/com/intelligentresume/scoring/dto/MatchResponse.java:8-18`；`server/src/main/java/com/intelligentresume/scoring/dto/Explanation.java:7-21`；`web/src/api/scoring.ts:3-18`；`web/src/views/MatchResultView.vue:23-39`。现有 `ScoringServiceTest` 只构造三条规则并断言三项分数/稳定结果，没有 synthetic 新规则、禁用规则、权重总和校验或跨 DTO/Web 结果契约测试。
- **建议验收**：建立 `ScoringRule` capability adapter，声明规则 ID、权重/启用策略、所需输入、分数和证据解释；聚合器按注册规则集合计算并输出版本化 `ruleScores`/evidence map，同时通过兼容投影保留现有三项字段。用 synthetic 规则、零权重/禁用规则、权重和不合法、旧结果回放和 Web contract fixture 验证：新增规则只需注册一个 adapter，既进入总分又可追溯展示，缺失或未知规则按稳定版本策略处理。

### 本轮范围与验证边界

- #91 不重复 #78 的评分请求重放/历史留存语义、#84 的 JD 解析 envelope 读取问题、#69 的 JD 派生结果新鲜度或 #83 的职业资料类型 capability；它只关注评分规则本身从注册、聚合到跨运行时结果展示的扩展 locality。
- 本轮仍为文档型静态审查，未修改评分规则、数据库迁移、DTO、Web 类型或测试数据，未创建评分结果，未发送 AI/provider 请求，未调用 PDF renderer，也未创建账号/改变浏览器状态。当前评分测试和此前浏览器 smoke 只证明三条固定规则的正常路径，不能关闭新增规则的接入、权重和结果契约验收。

## 第二十九轮跨服务能力健康契约审查（2026-09-04）

### 92. API 与 PDF 的健康接口硬编码 capability 并无条件返回 `UP`，新增能力和依赖状态无法被发现

- **静态发现**：`SystemController.health` 固定返回版本 `0.1.0`、八个字符串 capability 和 `status=UP`，没有读取 AI provider、数据库或 PDF 连接状态；PDF service 的 `/health` 又独立固定返回 `UP`、版本和三项 capability。Web `SystemHealth.status` 只允许字面量 `'UP'`，`HomeView` 仅以响应是否存在来显示“服务在线”，完全不消费 capability 或降级状态。
- **影响**：新增模板、导出格式、AI provider 或业务能力时，维护者必须手工同步多个健康响应；漏改会让实际支持能力与探针宣称不一致。更重要的是，API 进程可用但 AI/PDF/数据库依赖不可用时仍会被报告为 `UP`，首页无法区分“服务可访问”和“功能可用”。多实例、灰度发布或按配置关闭能力后，静态 capability 列表还会误导客户端和部署检查，属于 P2/P3 可观测性、部署和能力扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/system/controller/SystemController.java:14-19`；`server/src/main/java/com/intelligentresume/system/dto/SystemHealthResponse.java:5`；`pdf-service/src/server.js:30-37`；`web/src/api/system.ts:3-8`；`web/src/views/HomeView.vue:165-168,319-320`。现有 E2E 只 mock `/api/system/health` 返回 `status=UP`，没有验证 capability 与实际注册/配置一致，也没有 DOWN/DEGRADED、依赖失败或 PDF 浏览器未就绪 fixture。
- **建议验收**：建立共享/版本化 `CapabilityDescriptor` 与健康语义（liveness、readiness、`UP/DOWN/DEGRADED`），由 API、PDF 和 provider/renderer registry 生成能力清单；首页和部署探针分别消费整体状态与单项依赖状态。用能力新增/关闭、AI key 缺失、PDF renderer 未启动、数据库不可用、灰度版本和旧客户端 fixture 验证：健康响应既不虚报已注册能力，也不把进程存活误当成全部业务能力可用。

### 本轮范围与验证边界

- #92 不重复 #15 的生产配置启动校验、#18 的 AI provider 路由、#20 的 PDF renderer readiness/容量或 #79 的模板代码注册；它只关注跨服务健康响应如何表达能力清单、依赖状态和客户端兼容语义。
- 本轮仍为文档型静态审查，未停止 AI/PDF/数据库依赖、未调用 provider 或 renderer，未修改健康接口、部署配置、Web 类型或测试数据，也未创建账号/改变浏览器状态。现有健康 smoke 只能证明端点能返回 JSON，不能关闭真实依赖状态和能力清单一致性验收。

## 第三十轮 AI 任务类型注册接缝审查（2026-09-04）

### 93. 新增 `AiTaskType` 会通过默认分支进入通用路径，能力注册不完整时不会在入队前失败

- **静态发现**：`AiTaskType` 只是枚举名；同一个新枚举值还必须分别出现在 `AiTaskService.requiredCategories`、`AiQuotaService` 的配置构造与 quota map、`PromptTemplates` 的 system/user prompt 分支、`TaskExecutionService` 的专用执行分支、`AiTaskController` 的通用入口限制和 `AppObservability.registerQuotaLimit`。这些位置没有共享 capability interface：授权策略的 `default` 返回空类别，配额的 `getOrDefault` 回退 30，Prompt 的两个 switch 和 worker 的 `else` 都允许通用默认路径继续执行；而 controller 只显式禁止三种任务类型。
- **影响**：维护者新增一个任务枚举并完成一个领域入口后，若漏改任一注册点，通用 `POST /api/ai/tasks` 仍可能接受它；任务会以无数据类别授权、未声明的默认配额和通用 Prompt 入队，worker 直接把 provider 原始结果标记为成功，且遗漏类型不会有对应 quota limit gauge。这样“枚举可解析”会被误当成“能力已接通”，新增任务可能跳过专用输入/结果校验而在运行时才出现页面无法解释、授权过宽或观测缺失，属于 P2/P3 AI 能力扩展与 fail-open 风险。当前九种既有任务的主要路径不据此判定已坏；问题是新增类型的默认行为没有安全拒绝语义。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/task/domain/AiTaskType.java:6-16`；`server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java:198-205`；`server/src/main/java/com/intelligentresume/ai/ratelimit/AiQuotaService.java:26-50,57-63`；`server/src/main/java/com/intelligentresume/ai/provider/PromptTemplates.java:23-85,89-168`；`server/src/main/java/com/intelligentresume/ai/worker/TaskExecutionService.java:113-125,145-162,219-239`；`server/src/main/java/com/intelligentresume/ai/task/controller/AiTaskController.java:41-46`；`server/src/main/java/com/intelligentresume/common/observability/AppObservability.java:109-120`。现有 provider/worker 测试遍历当前枚举或覆盖已登记路径，但没有 synthetic 新任务类型的“缺注册即拒绝”、quota gauge、授权类别、结果 schema 和通用入口 contract test。
- **建议验收**：建立 `TaskCapabilityRegistry`/adapter seam，让任务能力一次性声明输入白名单、数据类别、配额、Prompt/schema 版本、执行与结果 formatter、重试/fallback 和恢复路由；创建、worker、retry、观测和 Web 恢复都从该 interface 读取。未知或未完整注册的 type/operation 必须在 provider 调用前稳定拒绝，不能落入通用成功路径；用 synthetic 新任务、缺少每个字段、旧任务回放、重试和跨页面恢复 fixture 验证只注册一个 adapter 就能闭环，且旧数据库枚举值仍按版本策略兼容。

### 本轮范围与验证边界

- #93 不重复 #10 的现有任务数据类别映射缺失、#89 的 `INTERVIEW_COACH` 内部 operation 分流、#18 的 provider 路由或 #92 的跨服务健康 capability；它只关注新增 `AiTaskType` 时多处注册点缺少统一 interface 且默认分支 fail-open。
- 本轮同时检查了 `app.job.parser.rule-version` 与 `JdParserRuleVersion.CURRENT`、评分 rule-version 配置与固定常量的来源关系；版本新鲜度和派生结果读取语义已分别由 #69、#84、#91 覆盖，未重复新增编号。
- 本轮仍为文档型静态审查，未修改 AI 任务、Prompt、配额、worker、健康接口或版本配置，未发送 AI/provider 请求，未调用 PDF renderer，未创建账号或改变浏览器/数据库状态。新增类型的 fail-open 行为尚未通过改枚举或真实 provider 复现；现有正常路径测试只证明当前任务集合，不能关闭 synthetic capability contract 验收。

## 第三十一轮简历版本来源契约审查（2026-09-04）

### 94. `ResumeSourceType` 的跨运行时展示映射分散，新增来源或旧客户端回放可能产生原始枚举、空翻译甚至运行时错误

- **静态发现**：后端 `ResumeSourceType` 维护五个来源值，Web `api/resume.ts` 再复制一份字符串联合；`ResumeDetailView` 与 `CompareVersionsView` 各自维护完整的来源到翻译 key 映射，但没有未知值 fallback，`InterviewView` 又维护第三份带 fallback 的映射。ATS、投递、沟通和成就引导的简历版本选择器则直接渲染 `version.sourceType`，没有经过本地化映射；中英文翻译 key 还分散在多个领域树中。
- **影响**：新增 `IMPORTED`、`ATS_REPAIRED` 等版本来源时，维护者必须同步 Java 枚举、Web 类型、至少三套映射、多个页面选择器以及中英文翻译。漏改时选择器会显示原始英文枚举，详情/对比页会把 `undefined` 传给 `t()`；当前 `t()` 会对 key 执行 `split()`，旧前端接收新后端枚举值时可能直接抛出异常。这样来源值不仅是元数据，还成为跨版本客户端的脆弱展示契约，属于 P2/P3 的简历版本能力扩展和向后兼容风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/resume/domain/ResumeSourceType.java:6-12`；`web/src/api/resume.ts:13-21`；`web/src/views/ResumeDetailView.vue:70-75`；`web/src/views/CompareVersionsView.vue:66-70`；`web/src/views/InterviewView.vue:400-407`；`web/src/views/AtsCheckView.vue:167-169`；`web/src/views/ApplicationsView.vue:430-432`；`web/src/views/CommunicationView.vue:472-476`；`web/src/views/AchievementGuidanceView.vue:54`；`web/src/i18n/index.ts:148,318,621,791,985-992`。现有浏览器/构建验证覆盖已知五种来源的正常页面，没有旧 Web + 新 enum fixture、未知来源 fallback、所有版本选择器的本地化 contract test。
- **建议验收**：建立 `ResumeSourceDescriptor` module，集中声明来源 code、翻译 key、legacy fallback、来源语义和可编辑/只读提示；详情、对比、ATS、投递、沟通、成就引导与版本 API 类型共同消费它。未知来源必须稳定显示“其他来源”并保留原始值用于诊断，不能把 `undefined` 传给翻译函数；增加五种现有值、一个 synthetic 新值、旧客户端回放、中英文切换和全部版本选择器的 contract/browser fixture。

### 本轮范围与验证边界

- #94 不重复 #56 的页面内语言切换响应性、#37 的 PDF 展示漂移、#57 的 AI/PDF 任务类型模型差异或 #79 的模板代码注册；它只关注简历版本来源值在后端、Web 类型、页面映射和多语言展示之间缺少统一 interface 与未知值策略。
- 本轮仍为文档型静态审查，未新增简历版本来源、未修改 Java/Web 来源映射或翻译、未改变账号/浏览器/数据库状态，也未发送 AI/provider 请求或调用 PDF renderer。当前五种来源的页面路径未据此判定全部失效；旧客户端接收 synthetic 新来源和未本地化选择器的行为仍需按建议 fixture 验收。

## 第三十二轮面试答案资产关联模型审查（2026-09-04）

### 95. 仅关联职业素材时用空字符串伪造简历章节，导致资产返回与筛选展示失真

- **静态发现**：`InterviewAssetService.replaceSections` 在没有选中简历章节但存在职业素材时写入 `section_key = ""`，以满足数据库 `NOT NULL`；`response` 又把这个空字符串原样加入 `sectionKeys`。Web 的 `InterviewAssetsView` 只为 14 个合法章节生成标签，未知/空 key 会渲染为空白标签；`ResumeDetailView` 的相关资产筛选只按合法章节 key 查询，因而无法通过章节筛选定位这类资产。素材 ID 虽然仍在 `materialIds` 中，但列表卡片没有把它们作为可见关联展示。
- **影响**：用户只关联职业素材、不关联简历章节时，保存请求可能成功，但 API 的 `sectionKeys` 会出现不代表任何章节的 `""`；答案资产页会出现空白章节标签，简历详情页按章节筛选无法命中，素材关联也没有对应的可读名称。该 sentinel 还把“无章节”与“未知章节”混成同一表示，后续迁移或新客户端很难定义兼容语义，属于 P2 资产关联数据模型与 UI 读写契约问题。它不同于 #53–#55 的跨运行时简历章节投影，也不同于 #56 的语言切换响应性。
- **代码证据**：`server/src/main/java/com/intelligentresume/interview/asset/service/InterviewAssetService.java:131-151,188-202`；`server/src/main/java/com/intelligentresume/interview/asset/domain/InterviewAssetSection.java:21-22`；`server/src/main/resources/db/migration/V23__career_loop_enhancements.sql:27-36`；`server/src/main/java/com/intelligentresume/interview/asset/repository/InterviewAnswerAssetRepository.java:19-28`；`web/src/views/InterviewAssetsView.vue:56-59,85-86,203-207`；`web/src/views/ResumeDetailView.vue:59-62,285-290`。现有 `InterviewAssetServiceTest` 覆盖幂等、非法章节、正常章节和越权素材，但没有 material-only round-trip、空 key 响应或 UI 筛选 fixture。
- **建议验收**：明确“无章节但有关联素材”的一等数据模型：移除空字符串 sentinel，并让章节关联与素材关联可独立表达（可通过可空章节列、独立关联表或版本化响应投影实现）；响应中的 `sectionKeys` 只返回合法非空 key，`materialIds` 保留完整关联，Web 对素材显示名称或明确的“未关联章节”状态。补充 material-only 创建/更新/列表 round-trip、旧空 key 数据迁移、按章节筛选不误命中、素材名称展示和重复保存幂等测试。

### 本轮范围与验证边界

- #95 不重复 #53–#55 的简历章节在编辑器、后端校验、面试上下文和 PDF 之间的 projection 漂移；它只关注面试答案资产的“章节关联 + 职业素材关联”联合模型在无章节分支中的 sentinel、API response 和 Web 筛选/展示行为。
- 本轮未修改业务源码、数据库迁移或测试数据；已使用明确允许的本地临时账号 `codexqa20260904` 登录内置浏览器，但未创建职业资料、岗位、简历、面试记录、AI 任务或 PDF 导出。未发送真实 AI/provider 请求，也未上传文件。

## 第三十三轮 Web 导航能力注册审查（2026-09-04）

### 96. 路由、导航分组、首页工作流和动态文案 key 分散维护，新增页面可能构建通过但导航显示原始 key

- **静态发现**：路由路径和认证元数据在 `router/index.ts` 中维护；顶部/移动端导航又在 `AppLayout.vue` 的 `groups` 中复制路径、分组和 item key；首页 `HomeView.vue` 再维护一套四步 `workflow` 路径；中英文 `i18n/index.ts` 维护另一套 `navGroups` 文案树。导航渲染使用动态 `t(\`navGroups.${groupKey}.${item.key}\`)`，而 `check-i18n.mjs` 的 `collectStaticTranslationKeys` 只收集静态 `t('...')` 调用，无法验证这些动态 key 在两个 locale 中存在。
- **影响**：新增一个受保护页面时，即使只更新路由和导航数组，漏改某个 locale 的 `navGroups` 仍会通过当前 i18n guard；`t()` 缺失时返回原始 key，因此用户可能看到 `navGroups.resume.newPage` 等内部标识。若只更新路由而遗漏导航或首页工作流，页面又只能通过深链进入。当前已登记页面的 key 能够匹配，不据此判定现有导航已坏；这是 P2/P3 Web 页面能力扩展、导航可发现性和本地化质量风险。
- **代码证据**：`web/src/router/index.ts:58-91`；`web/src/layouts/AppLayout.vue:16-42,73-86,128-134`；`web/src/views/HomeView.vue:123-127,301-309`；`web/src/i18n/index.ts:23-35,496-508,985-987`；`web/scripts/check-i18n.mjs:192-194,218-220`。现有 E2E 检查任务型导航和部分首页工作流，但没有从路由/导航/catalog 自动建立集合一致性断言，也没有 synthetic 新页面缺翻译 fixture。
- **建议验收**：建立 typed `RouteDescriptor`/navigation registry，集中声明 route name/path、认证范围、导航分组、翻译 key、是否出现在首页工作流；由它生成或校验 Router、桌面/移动导航和工作流。保留上下文页面（如详情、导出、确认页）的显式不入导航声明。补充静态 gate：所有导航目标必须解析到路由、所有动态文案 key 在中英文存在、同一路由不重复注册、未知 key 使用稳定 fallback；用 synthetic 新页面、旧客户端深链和中英文切换 fixture 验证。

### 本轮范围与验证边界

- #96 不重复 #56 的页面内 locale map 响应性、#79 的模板代码注册、#94 的简历来源展示映射或 #95 的面试资产关联模型；它只关注页面入口、导航可发现性、首页工作流和动态导航文案之间缺少统一注册契约。
- 本轮为文档型静态审查，未修改路由、导航、i18n 或测试代码，未改变账号/浏览器/数据库状态，未发送 AI/provider 请求，也未调用 PDF renderer。当前已登记导航项仍需通过 synthetic route/catalog contract 测试关闭扩展风险。

## 第三十四轮 PDF 导出状态契约审查（2026-09-04）

### 97. PDF 导出状态在后端作为任意字符串返回，Web 对未知状态会停止轮询并可能触发翻译运行时错误

- **静态发现**：后端 `ExportStatus` 当前有 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED`、`EXPIRED` 五个值，但 `ExportTaskStatusResponse.status` 声明为 `String`；Web `api/export.ts` 又复制一份五值联合，`ExportView.statusLabel` 通过对象索引取翻译 key，没有未知值 fallback。模板的轮询、重试、下载和图标逻辑也逐项判断这五个值。
- **影响**：未来加入 `QUEUED`、`CANCELLED` 或清理中的中间状态时，后端可以正常序列化并返回，但旧 Web 会把 `undefined` 传给 `t()`（当前实现会调用 `key.split()`），或者把未知状态当作普通处理中状态却不再轮询；用户可能看到页面异常、永久停留或无法重试/下载。当前五状态 happy path 未据此判定已坏，属于 P2/P3 导出任务状态、旧客户端回放和跨运行时扩展风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/export/domain/ExportStatus.java:6-12`；`server/src/main/java/com/intelligentresume/export/dto/ExportTaskStatusResponse.java:7-15`；`server/src/main/java/com/intelligentresume/export/service/ExportService.java:159-171`；`web/src/api/export.ts:3-9`；`web/src/views/ExportView.vue:16-18,32-43,96-118`；`web/src/i18n/index.ts:985-987`。现有 PDF service、导出服务和 Web E2E 只覆盖五个已知状态，没有 synthetic 新状态、旧 Web 回放或未知状态 fallback fixture。
- **建议验收**：建立版本化 `ExportStatusDescriptor`/状态机契约，后端响应至少返回稳定状态 code 与可选 terminal/retry/poll 元数据，Web 统一消费 descriptor；未知状态必须安全显示“处理中/需要刷新”等用户可读 fallback，并保守继续轮询或提供刷新入口，不能传 `undefined` 给翻译函数。补充五种现有状态、synthetic 新状态、旧任务回放、未知终态、重试和下载按钮行为的 contract/browser 测试。

### 本轮范围与验证边界

- #97 不重复 #94 的简历来源展示映射、#92 的跨服务健康状态或 #76/#77 的 AI 异步响应/幂等问题；它只关注 PDF export task 的 status code 从后端 DTO 到 Web 状态分支、轮询和翻译的兼容契约。
- 本轮为文档型静态审查，未创建导出任务、未调用 PDF renderer、未修改导出状态、DTO、Web 类型或测试代码，也未改变账号/浏览器/数据库状态。

## 第三十五轮面试输入来源接缝审查（2026-09-04）

### 98. 面试输入来源的校验、持久化、上下文投影和 Web 恢复分支没有统一 source adapter 接缝

- **静态发现**：`InterviewSourceType` 目前只有 `PLATFORM_RESUME`、`EXTERNAL_RESUME` 两个值，但来源语义被分散在多个实现：`InterviewPromptContextAssembler` 负责来源校验、归属查询和平台/外部文本投影；`InterviewStartService` 用两个独立条件决定 `resumeVersionId` 与 `externalResumeText` 的落库；`InterviewOperationSupport` 又把两个原始输入都纳入启动指纹；`InterviewSession`/`InterviewStateResponse` 以两列和两个字段暴露恢复数据；Web `interview.ts`、`InterviewView.vue` 在启动、恢复和薄弱项练习中重复维护同一字符串联合与条件 payload。
- **影响**：新增“导入批次”“上传文件”“LinkedIn 资料”等来源时，维护者必须同时增加输入 DTO/数据库投影、归属校验、脱敏上下文、启动指纹、状态恢复和表单分支；漏改即可出现来源可选但上下文为空、恢复时丢失原始输入或指纹未覆盖真实来源内容。当前实现还存在具体的 fail-open 接缝：`validateSource` 对所有非 `PLATFORM_RESUME` 值按外部简历文本检查，而 `InterviewStartService` 只在值严格等于 `EXTERNAL_RESUME` 时保存外部文本；未来新增枚举值若复用该字段，可能校验通过后创建一个没有简历上下文的会话。
- **代码证据**：`server/src/main/java/com/intelligentresume/interview/domain/InterviewSourceType.java:3`；`server/src/main/java/com/intelligentresume/interview/service/InterviewPromptContextAssembler.java:107-137`；`server/src/main/java/com/intelligentresume/interview/service/InterviewStartService.java:103-105`；`server/src/main/java/com/intelligentresume/interview/service/InterviewOperationSupport.java:227-232`；`server/src/main/java/com/intelligentresume/interview/domain/InterviewSession.java:10-12,28-33`；`server/src/main/java/com/intelligentresume/interview/dto/InterviewStateResponse.java:27-29,81-86`；`web/src/api/interview.ts:6-8,61-63,99-101`；`web/src/views/InterviewView.vue:27-31,137-145,339-355,474-479`；`web/src/views/InterviewHistoryView.vue:35-37`。现有 `InterviewPromptContextAssemblerTest`、`InterviewStartServiceTest` 和 `InterviewControllerIT` 覆盖两种已知来源的正常/归属路径，但没有 synthetic 新来源、来源 round-trip 或“未知来源拒绝而非空上下文”的 contract fixture。
- **建议验收**：建立一个版本化 `InterviewSourceDescriptor`/source adapter module，集中声明来源 code、输入载荷、归属校验、持久化读写、脱敏上下文、指纹参与字段、恢复/练习复用和 Web 展示元数据；启动、AI prompt、retry/follow-up、状态响应和表单只消费该 interface。未知或未注册来源必须在入队前稳定拒绝，不能落入外部文本默认分支；补充两种现有来源、synthetic 新来源、旧会话回放、刷新后练习复用、指纹差异、归属隔离和中英文表单 fixture。

### 本轮范围与验证边界

- #98 不重复 #54/#55 的简历章节 projection 漂移、#82 的面试 mode strategy、#87 的反馈 schema、#88 的题目 schema、#89 的面试 task operation 路由或 #94 的简历版本来源展示映射；它只关注“面试输入从哪里来”这一 source code 到持久化、AI 上下文和恢复 UI 的统一接缝。
- 本轮为文档型静态审查；未新增 `InterviewSourceType`、未创建面试会话或 AI task、未发送 AI/provider 请求、未上传文件、未修改业务源码或数据库。`mvn -q "-Dtest=InterviewPromptContextAssemblerTest,InterviewStartServiceTest" test` 通过，证明当前两种来源的既有测试基线正常，但不关闭 synthetic 新来源的 fail-open 风险。

## 第三十六轮 AI 任务确认生命周期审查（2026-09-04）

### 99. AI 任务的 `NOT_REQUIRED` 确认状态被后端 `null` 和 Web 待确认逻辑分裂，新增无需人工确认的任务可能被错误恢复

- **静态发现**：后端 `ConfirmationStatus` 声明 `NOT_REQUIRED`、`PENDING`、`CONFIRMED`、`REJECTED`，但 `AiTask.confirmationStatus` 允许为 `NULL`；创建任务时不初始化该字段，worker 只在选材/岗位生成路径写入 `PENDING`，通用任务路径继续保留 `null`。`AiTaskStatusResponse` 直接序列化这个 nullable enum，而 Web `AiTask.confirmationStatus` 只声明 `PENDING | CONFIRMED | REJECTED | null`，没有 `NOT_REQUIRED`。
- **影响**：如果未来任务 capability 采用已经存在的 `NOT_REQUIRED` 表示“成功后无需人工确认”，旧 Web 的 `needsRecovery` 会把 `SUCCESS + NOT_REQUIRED` 当作可恢复任务，因为它只排除 `CONFIRMED`/`REJECTED`；`GenerationConfirmView` 也只在 `SUCCESS + PENDING` 时解析草稿。结果可能被留在本地恢复槽位，却没有对应的确认数据/入口；若继续使用 `null`，又无法区分“无需确认”“尚未设置”和“旧任务数据”。这是 P2/P3 AI 结果生命周期与跨运行时扩展风险，不是当前已返回 `NOT_REQUIRED` 的页面故障。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/task/domain/ConfirmationStatus.java:6-11`；`server/src/main/java/com/intelligentresume/ai/task/domain/AiTask.java:65-66`；`server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java:87-95,144-158`；`server/src/main/java/com/intelligentresume/ai/worker/TaskExecutionService.java:191-214,219-239`；`server/src/main/java/com/intelligentresume/ai/task/dto/AiTaskStatusResponse.java:13-25`；`web/src/api/ai.ts:79-89`；`web/src/stores/aiTask.ts:33-50`；`web/src/views/GenerationConfirmView.vue:51-67`。现有测试主要覆盖 `PENDING`/`CONFIRMED`/`REJECTED` 和当前生成流程，没有 `NOT_REQUIRED` round-trip、旧 `null` 任务迁移或无需确认任务的恢复 fixture。
- **建议验收**：把确认生命周期收敛成版本化 `TaskResultDescriptor`/confirmation adapter，明确 `requiresConfirmation`、结果展示/恢复路由和终态；统一 `NOT_REQUIRED` 与历史 `null` 的兼容读取策略，让 Web 类型、恢复 store、首页收件箱和确认页消费同一 descriptor。补充四种确认状态、旧 null 数据、无需确认成功任务、需确认草稿任务、失败重试和刷新/跨页恢复 contract/browser 测试；`NOT_REQUIRED` 必须稳定终止确认恢复，不得传入只支持草稿确认的入口。

### 本轮范围与验证边界

- #99 不重复 #93 的 `AiTaskType` 多处能力注册缺失、#57 的 PDF 与 AI 任务类型集合差异、#76/#77 的异步响应/幂等问题或 #89 的面试 operation 路由；它只关注 AI 任务结果的“是否需要人工确认”状态从数据库枚举到 Web 恢复/确认入口的生命周期契约。
- 本轮为文档型静态审查；未新增 AI task、未发送 provider 请求、未改变任务/账号/浏览器/数据库状态，也未修改业务源码。当前任务路径未实际返回 `NOT_REQUIRED`，因此结论是未来能力扩展风险；需要 synthetic task fixture 才能关闭该验收缺口。

## 第三十七轮授权撤回与浏览器闭环复核（2026-09-04）

- 静态复核了 `AiConsentService`、`AiTaskService`、`TaskExecutionService` 的授权撤回路径：排队任务会在 worker 执行前重新检查授权，撤回后的任务不会继续调用 provider；但“撤回后取消/清理异步任务”和“账号删除后的 worker fencing”仍分别属于已有 #10、#26、#71 的生命周期问题，本轮没有独立新根因，不新增编号。
- 内置浏览器在 `http://localhost:5173` 完成了本地注册与授权页面闭环：临时账号 `codexqa20260904b` 注册成功并进入职业资料页；AI 授权页能够从未授权切换为已授权，显示 provider `bailian` 与 9 个 scope，再撤回并显示已撤回。未创建职业资料、岗位、简历、面试记录、AI 任务或 PDF 导出，未发送真实 AI/provider 请求。
- 本轮未发现新的页面级错误；此前已登记的跨运行时问题（#56、#94、#96、#97、#99）仍需按各自 synthetic contract/browser fixture 关闭，当前浏览器 happy path 不能替代这些扩展性验收。

## 第三十八轮简历版本指针并发状态审查（2026-09-04）

### 100. 当前版本指针、归档和恢复缺少事务性状态接缝，并发操作可能留下当前指向归档版本

- **静态发现**：`ResumeService.setCurrentVersion` 先分别读取简历和版本，检查版本尚未归档后再直接写入 `resume.current_version_id`；`ResumeVersionService.archive` 读取同一简历的当前指针后检查目标不是当前版本，再写入 `resume_version.deleted_at`；`restore` 创建新版本后直接更新当前指针。`Resume`、`ResumeVersion` 和公共实体均没有 `@Version`/版本列，也没有带当前版本与归档状态条件的 update 或显式行锁。数据库只对 `(resume_id, version_no)` 建立唯一约束，没有约束“current_version_id 必须指向未归档版本”。
- **影响**：例如切换当前版本的事务先读到 v2 未归档，归档事务随后读到旧的当前指针并把 v2 归档，切换事务最后仍可提交 `current_version_id = v2`；最终简历当前指针指向已归档版本。反向交错也可能让归档检查基于旧指针而错误拒绝/接受操作。正常单线程下的“当前版本不能归档”和“归档版本不能设为当前版本”仍可通过，但在保存、切换、归档、恢复并发或多实例部署时，版本状态机的关键不变量没有数据库/事务层兜底，属于 P1/P2 简历版本一致性与恢复可靠性风险。
- **代码证据**：`server/src/main/java/com/intelligentresume/resume/service/ResumeService.java:110-122`；`server/src/main/java/com/intelligentresume/resume/service/ResumeVersionService.java:138-186,207-218`；`server/src/main/java/com/intelligentresume/resume/domain/Resume.java:17-29`；`server/src/main/java/com/intelligentresume/resume/domain/ResumeVersion.java:23-53`；`server/src/main/java/com/intelligentresume/common/persistence/BaseEntity.java:18-47`；`server/src/main/resources/db/migration/V1__m1_m2_init.sql:65-100`。现有 `ResumeServiceTest` 和 `ResumeVersionServiceTest` 覆盖串行归档、恢复、首版本和版本号唯一约束，但没有归档/切换交错、恢复与归档并发、归档后指针校验或首版本创建竞态测试。
- **建议验收**：建立 `ResumeVersionPointer`/版本状态机 module，集中处理当前指针、可归档性、恢复继任版本和冲突语义；通过 `@Version` 或按简历行加锁/条件更新，使“检查版本状态 + 更新当前指针/归档状态”成为同一事务性 interface，并在数据库或提交后 invariant check 中拒绝当前指向归档版本。补充真实数据库并发 fixture：切换与归档交错、恢复与归档同时发生、两个首版本创建、版本号冲突重试、软删简历后的操作；断言要么一个操作稳定得到冲突、要么最终状态始终满足当前指针指向未归档版本。

### 本轮范围与验证边界

- #100 不重复 #32 的“归档版本仍被评分/ATS/导出等下游消费”、#67/#70/#73 的下游版本快照/恢复语义，也不重复 #99 的 AI 任务确认状态；它只关注简历版本状态机自身的当前指针、归档、恢复和版本创建在并发下缺少原子接缝。
- 数据库迁移确认 `resume` 没有乐观锁列或 current-pointer 条件约束，只有 `(resume_id, version_no)` 唯一键；`mvn -q "-Dtest=ResumeServiceTest,ResumeVersionServiceTest" test` 通过。此次仍为文档型静态审查，未修改简历业务源码、实体、迁移或测试数据，未改变账号/浏览器/数据库状态，也未发送 AI/provider 请求或调用 PDF renderer。现有 Mockito 测试只能证明串行 happy path，不能关闭上述真实数据库并发验收。

## 第三十九轮草稿确认数组路径审查（2026-09-04）

### 101. 拒绝数组前项后继续编辑/接受后项会造成 outputPath 索引漂移，用户编辑可能被静默丢失

- **静态发现**：`DraftCommitService.commit` 先针对原始 `draft` 校验所有 `outputPath`，随后把完整决策列表交给 `ResumeJsonNormalizer.normalize`。normalizer 先遍历所有 `REJECT` 并通过 `List.remove(index)` 删除数组项，再遍历 `EDIT` 用原路径 `setAtPath`，最后仍用原路径集合判断 `_pending` 是否已决策。数组前项一旦删除，后续路径如 `work[1]` 已指向新的 `work[0]`；`setAtPath` 对越界或不存在路径直接静默返回，不会报告决策失效。
- **影响**：用户在生成确认页对 `work[0]` 选择删除、对 `work[1]` 选择编辑时，提交前路径校验可以通过，但标准化阶段会先删除 `work[0]`，随后 `work[1]` 越界，用户编辑不会进入最终 `resume_json`；`DraftCommitService` 仍会按 EDIT 决策创建职业资料，形成“资料记录了编辑内容、简历版本却没有该内容”的不一致。若 `work[1]` 是带 `_pending` 的项目，后续路径还会被移动到 `work[0]`，原 `ACCEPT` 决策匹配不到，提交会误报残留 `_pending`。这是 P1/P2 确认流程数据完整性与可解释性风险，当前单项或不删除前项的路径不受影响。
- **代码证据**：`server/src/main/java/com/intelligentresume/ai/confirmation/service/DraftCommitService.java:76-120,193-213`；`server/src/main/java/com/intelligentresume/ai/confirmation/service/ResumeJsonNormalizer.java:41-65,73-100,124-184`；`web/src/composables/useDraftReview.ts:79-99,285-288`；`web/src/views/GenerationConfirmView.vue:121-139`。现有 `ResumeJsonNormalizerTest` 覆盖单个嵌套路径、单项 ACCEPT/EDIT 和残留 `_pending`，`DraftCommitServiceTest` mock 了 normalizer，没有覆盖同一数组中“前项 REJECT + 后项 EDIT/ACCEPT”的顺序交错。
- **建议验收**：让草稿决策使用稳定 item identity（例如生成阶段绑定条目 ID/原始路径并在服务端建立快照映射），或在应用决策前按数组索引从高到低执行删除，并让 EDIT/ACCEPT 解析到删除后的稳定节点；所有路径操作必须在不存在/越界时返回明确校验冲突，禁止静默忽略。补充多数组项混合决策、连续删除、删除前项后编辑后项、嵌套 highlights、重复提交和旧任务回放 fixture，断言最终简历 JSON、职业资料和 rejectedPaths 的语义一致。

### 本轮范围与验证边界

- #101 不重复 #53 的素材生成章节白名单丢弃、#83 的职业资料类型注册分散、#90 的个人档案导入覆盖，也不重复 #99 的 AI 任务确认状态；它只关注同一份生成草稿内基于数组索引的决策路径，在拒绝/编辑/接受组合操作后的地址稳定性与提交结果一致性。
- `mvn -q "-Dtest=ResumeJsonNormalizerTest,DraftCommitServiceTest" test` 通过，证明当前单路径标准化与事务提交流程基线正常，但没有关闭数组索引漂移 fixture。此次仍为文档型静态审查，未创建 AI task、未发送 provider 请求、未产生简历/职业资料、未修改业务源码或测试数据，也未改变账号/浏览器/数据库状态。

## 第四十一轮审查问题修复（2026-09-04）

### #77 内联润色与成就引导的幂等键已修复

- `InlineOptimizeController` 不再为 `INLINE_OPTIMIZE`/`ACHIEVEMENT_GUIDANCE` 随机生成幂等键；两个入口现在要求客户端传入非空且不超过 128 个字符的 `Idempotency-Key`，并将其交给 `AiTaskService` 做指纹复用与冲突校验。
- Web `ai.ts` 为两个创建接口增加幂等键请求头。简历编辑器在打开一次 AI 助手时生成稳定键，成就引导按相同请求内容复用键，避免认证刷新或网络重试创建重复任务；异步结果继续校验当前编辑上下文，过期响应不会覆盖新内容。
- 新增 `InlineOptimizeControllerIT` 覆盖缺失幂等键、相同键相同内容返回同一任务，以及带幂等键后的资源/授权校验。
- 验证：`mvn -q "-Dtest=InlineOptimizeControllerIT" test` 通过；`npm run build` 通过（i18n guard、`vue-tsc`、Vite）。未发送真实 AI/provider 请求。

### 本轮仍未处理的相邻问题

- #69 与 #100 已在本补充记录中继续处理；其余静态扩展风险保持开放。

### #69 解析与编辑并发写回已补充保护

- `JobDescriptionService.parse` 在解析前捕获原文，解析完成后通过 `PESSIMISTIC_WRITE` 刷新并锁定当前 JD 行，再校验原文是否仍一致；如果用户已编辑 JD，则返回冲突并丢弃晚到解析结果。锁定后再写回也避免编辑在最后校验与保存之间插入。
- 新增服务层回归测试，模拟解析完成时原文已变化，确认返回 `CONFLICT` 且不会保存旧关键词结果。该改动不改变正常串行解析契约，也不发送 provider 请求。

### #100 简历当前版本指针并发保护已补充

- `ResumeRepository` 新增按用户归属查询并使用 `PESSIMISTIC_WRITE` 锁定简历主行的查询。切换当前版本、保存首版本、恢复、归档和取消归档现在都先锁定该主行，再检查/更新版本状态。
- 因此切换与归档的交错会稳定表现为“先提交者完成、后提交者重新读取并成功冲突/继续”，不会再把 `current_version_id` 留在已归档版本上；两个首版本保存也按同一简历行串行化。
- 既有 Resume/ResumeVersion 服务测试保持通过；后续仍可用真实数据库并发 fixture 补充多实例/锁超时场景。

## 第四十轮审查问题修复（2026-09-04）

### #76 成就引导异步响应已修复

- `web/src/api/ai.ts` 现在把成就引导创建接口声明为 `AiTask`，不再把 `202 Accepted` 当成同步问题列表。
- `AchievementGuidanceView` 保存返回的 task ID，并通过 `waitForAiTaskResult` 轮询到 `SUCCESS.resultJson` 后再读取 `questions`；任务失败/超时沿用统一错误路径。若响应已经携带成功结果则直接消费，避免无意义的首次等待。
- 验证：`web` 的 `npm run build` 通过，包含 i18n guard、`vue-tsc` 和 Vite 构建。未调用真实 AI/provider；仍需可控 mock provider 的浏览器回归覆盖失败、超时、刷新恢复。

### #84 JD 解析 envelope 与评分读取已修复

- `ScoringService` 新增兼容读取投影：既支持迁移前顶层 `keywords/requirements`，也支持正式解析接口写入的 `data.keywords/data.requirements` envelope。
- 对正式 `data` envelope 的评分不会再重新调用 parser；保留缺少关键词结构时的自动解析回退。
- 新增 `ScoringServiceTest.score_reusesNestedParsedEnvelope`，用与 JD 原文不同的已保存解析结果证明评分读取的是持久化 envelope。验证命令：`mvn -q "-Dtest=ResumeJsonNormalizerTest,ScoringServiceTest,DraftCommitServiceTest" test` 通过。

### #101 草稿确认数组路径漂移已修复

- `ResumeJsonNormalizer` 改为递归遍历原始草稿树并按原始路径同时应用 ACCEPT/EDIT/REJECT；删除数组项不会先改变后续决策的索引。
- EDIT 先定位原始条目后写入，ACCEPT 的 `_pending` 豁免也在原始节点上判断，最终再统一剥离标记；不存在的路径仍由 `DraftCommitService` 的原始草稿校验拦截。
- 新增混合决策回归测试：拒绝 `work[0]`、编辑 `work[1]`、接受带 `_pending` 的 `work[2]` 后，最终 JSON 保留正确的编辑/接受条目且不残留标记。
- 验证：`mvn -q "-Dtest=ResumeJsonNormalizerTest,ScoringServiceTest,DraftCommitServiceTest" test` 通过；未创建任务、未发送 provider 请求或修改数据库中的业务数据。

### #69 JD 修改后的旧解析状态已修复（基础生命周期）

- `JobDescriptionService.update` 在 JD 原文确实变化时清除 `parsedKeywordsJson`、`parsedAt` 和 `parsedVersion`，避免旧关键词继续被展示和评分复用。
- 新增 `JobDescriptionServiceTest.update_textInvalidatesParsedSnapshot`，验证原文更新后派生结果全部失效；评分对失效结果仍会走现有自动解析回退。
- 该基础修复之后，#69 的解析与编辑并发晚到写回已在本文件后续补充记录中通过“刷新 + 写锁 + 原文复核”继续收敛。

### 本轮仍未处理的相邻问题

- #77 与 #100 已在本文件后续补充记录中修复；其余静态扩展风险仍保持开放，不能因为局部修复而标记为全量完成。
- 本轮没有新增问题编号；修复的是已登记的 #76、#84、#101。

## 第四十二轮审查问题修复（2026-09-04）

### #10 AI 任务数据类别映射不一致已修复

- `AiTaskService` 原先只对岗位生成、ATS 和沟通任务声明数据类别，其余任务落入空列表；`TaskExecutionService` 又维护了另一套 worker 映射。这会让部分授权的任务先保存包含原始简历/职业资料的快照，或让创建成功的任务到 worker 才因授权失败。
- 新增 `AiTaskConsentPolicy` 作为共享策略：素材导入要求 `CAREER_MATERIAL`；简历优化、内联润色和成就引导要求 `RESUME`；面试任务要求 `RESUME` + `INTERVIEW_ANSWER`；任务快照或嵌套 input 携带 JD ID/文本/上下文时追加 `JOB_DESCRIPTION`。原有岗位生成、ATS、沟通类别保持不变。
- 创建和重试在保存/重新入队前使用该策略，worker 在 provider 调用前复用同一策略；面试启动、回答和 follow-up 的领域校验也改用共享映射。这样撤回/部分授权会在任务持久化前或 provider 调用前得到一致拒绝。
- 新增/更新 `AiTaskServiceTest`、`TaskExecutionServiceTest`、`InterviewOperationSupportTest` 及相关集成授权 fixture，覆盖类别矩阵、嵌套 JD、部分授权不落库和 worker 执行前拦截。验证命令：`mvn -q "-Dtest=AiTaskServiceTest,TaskExecutionServiceTest,InterviewOperationSupportTest,InlineOptimizeControllerIT,AiTaskControllerIT" test`，共 62 个测试通过。

### 本轮范围与验证边界

- 本轮关闭 #10 的“创建/执行数据类别映射不一致”子项，不宣称完成任务收件箱恢复、撤回后的队列清理或账号删除后的 worker fencing；这些仍由已有生命周期审查项覆盖。
- 未发送真实 AI/provider 请求、未调用 PDF renderer、未修改业务数据或浏览器账号状态；验证仅使用 Mockito 和 H2 集成测试。

## 第四十三轮审查问题修复（2026-09-04）

### #34 账号删除后的异步任务与 PDF worker fencing 已补齐

- `AuthService.deleteAccount` 现在锁定用户主行后，原子地停用账号、撤回当前 AI 授权、终止未完成的 `ai_task`，并将未完成的 `export_task` 标记为终态 `FAILED`，最后撤销 refresh sessions。AI 撤回采用幂等追加事件，没有授权记录或已撤回时不会重复写入。
- `ExportTaskWorker` 在读取简历版本和调用 renderer 前检查所属用户仍为 `ACTIVE`；已删除/停用账号的任务直接安全失败，不读取简历内容也不调用 PDF renderer。AI worker 继续通过执行前 scoped consent 校验阻断撤回后的任务，数据库中的取消状态也会让旧 lease 的完成写回失效。
- 新增 `AuthServiceTest`、`AiConsentServiceTest`、`ExportTaskWorkerTest` 回归覆盖，以及现有 `AuthControllerIT` 的访问令牌失效路径；验证命令：`mvn -q "-Dtest=AuthServiceTest,AiConsentServiceTest,ExportTaskWorkerTest,AuthControllerIT" test`，共 28 个测试通过。

### 本轮范围与验证边界

- 本轮关闭 #34 的删除后授权、排队任务终止和 PDF worker 用户状态检查；已经开始渲染的 PDF 的孤儿文件回收和精细 lease 竞态仍属于独立的资源生命周期审查项。
- 未发送真实 AI/provider 请求、未调用 PDF renderer、未执行破坏性账号删除；集成测试使用 H2 和临时测试数据。

## 第四十四轮审查问题修复（2026-09-04）

### #35 AI 职业资料快照 PII 脱敏已补齐

- `CareerMaterialAiSnapshotSanitizer` 现在递归处理 `sourceText`、标题和嵌套 `contentJson`，移除邮箱、电话、地址、联系方式和 URL 等敏感信息后才生成 AI 快照。
- ACHIEVEMENT 的既有指标展示规则保持不变；新增测试覆盖嵌套结构和脱敏结果，避免仅脱敏顶层字段导致 PII 从嵌套内容泄漏。

### #42 面试 follow-up 会话上下文漂移已修复

- `InterviewStateResponse` 返回会话创建时的 `interviewMode` 与 `outputLanguage`。
- `InterviewView` 创建 follow-up 练习会话时使用会话快照，而不是当前页面 locale 或默认模式；语言切换、刷新后继续面试不会改变原会话语义。
- `InterviewStateAssemblerTest` 覆盖状态中原始模式和输出语言的组装。

### #56 页面内切换语言后的静态标签已修复

- `ResumeDetailView`、`InterviewAssetsView`、`CompareVersionsView`、`CommunicationView` 将一次性 label map 改为随 locale 更新的 `computed` 值。
- 这样不刷新页面切换中英文时，章节标签、筛选项、版本比较和沟通页面的静态文案都会重新计算；本项是现有 stale localization finding 的代码修复，不新增问题编号。

### #95 素材-only 面试资产的空章节 sentinel 已移除

- 新增 Flyway `V25__interview_asset_optional_section.sql`，将 `section_key` 改为可空并清理历史 `''` 数据；服务端创建、更新和响应不再写入或暴露空字符串章节。
- Web 的面试资产与简历详情页面保留素材关联，并显示关联素材名称；素材-only 资产不再被误渲染为一个空章节标签。
- 新增 service/controller 回归覆盖素材-only 创建、列表和迁移后的响应契约。

### 本轮验证与边界

- 通过：`mvn -q "-Dtest=CareerMaterialAiSnapshotSanitizerTest,InterviewStateAssemblerTest,InterviewAssetServiceTest,InterviewAssetControllerIT,InterviewControllerIT" test`；H2 成功应用 V25，目标测试通过。
- 通过：`Set-Location web; npm run build`（包含 i18n guard、`vue-tsc` 和 Vite 构建）。
- 通过：`git diff --check`。
- InterviewControllerIT 仍会进入 provider-facing 路径，并因未配置真实 Bailian key 记录既有 4xx 日志，但测试结果通过且没有生成有效 AI 结果；本轮没有 PDF 渲染或业务数据写入。若要求完全禁止外部 provider 调用，应改用 mock/disabled-provider profile 后再运行该集成测试。
- 浏览器复核受环境限制：`agent-browser` 未安装，且尝试重新访问 `http://localhost:5173` 得到 `ERR_CONNECTION_REFUSED`；因此本轮不把浏览器 smoke 作为 #35/#42/#56/#95 的关闭证据，待服务与浏览器工具可用后再补充交互验收。

## 第四十五轮审查问题修复（2026-09-04）

### #99 AI 任务确认生命周期的历史兼容与恢复误判已修复

- `AiTaskService` 在 API 输出层把历史 `confirmation_status = null` 归一化为 `NOT_REQUIRED`，不强制改写历史数据库行，避免旧数据迁移风险。
- Web `AiTask` 类型补齐 `NOT_REQUIRED`；`useAiTaskStore.needsRecovery` 只把 `SUCCESS + PENDING` 视为待人工确认，`GenerationConfirmView` 对已确认、已拒绝或无需确认的成功任务显示安全结束状态，不再渲染空草稿。
- 新增服务层回归测试验证 null → `NOT_REQUIRED` 的公开契约。该修复关闭当前/历史数据的具体误恢复问题，但完整的版本化结果 descriptor、未知未来任务类型路由仍由 #93 的能力注册审查覆盖。

### #97 PDF 导出未知状态的 Web fallback 已修复

- `ExportTaskStatus` 增加 `UNKNOWN` 运行时兜底；`ExportView` 对 API 返回的未识别 status 做归一化，并提供稳定的未知状态文案。
- 未知状态不会进入自动轮询、下载或失败重试路径，页面提供“重试读取状态”入口；已知 `PENDING/RUNNING/SUCCESS/FAILED/EXPIRED` 的既有行为保持不变。
- 这关闭了“未知状态传入 undefined 翻译 key / 被误当处理中”的当前 Web 契约缺口；后端状态机扩展仍应在正式新增状态时同步声明 terminal/retry/poll 语义。

### #90 个人档案导入丢失 objective 字段已修复

- `PersonalProfileService.importSuggestion` 继续读取 `basics` 的姓名、联系方式和概要，并新增读取 `objective.targetRole`、`targetIndustry`、`location`、`summary` 以及可选职级字段。
- 字符串会转换为单元素列表，数组会去重保序；导入仍只返回建议，不覆盖或保存现有个人档案。
- 新增 `basics + objective` 回归 fixture，验证目标岗位、行业、地点偏好和职业定位摘要都能进入导入预览。

### #98 面试来源的具体 fail-open 默认分支已修复

- `InterviewPromptContextAssembler.validateSource` 从“除平台来源外都按外部文本”改为只接受显式注册的 `PLATFORM_RESUME` 与 `EXTERNAL_RESUME` 分支；空值或未来未接入来源在持久化前稳定返回校验错误。
- `appendResumeContext` 只有在会话来源明确为 `EXTERNAL_RESUME` 时才使用外部文本，避免来源语义与持久化字段不一致时把错误数据送入 AI 上下文。
- 新增空来源回归测试；完整 source descriptor、上传/批次等未来来源仍需按 #98 建议继续扩展。

### 本轮验证与范围

- 通过：`mvn -q "-Dtest=AiTaskServiceTest,ExportServiceTest" test`、`mvn -q "-Dtest=InterviewPromptContextAssemblerTest,PersonalProfileServiceTest" test`。
- 通过：`Set-Location web; npm run build`（i18n guard、`vue-tsc`、Vite）。
- 通过：`git diff --check`。
- 本轮聚焦测试没有调用真实 AI provider 或 PDF renderer，也没有写入业务数据库；未完成浏览器复核，因环境仍缺少 `agent-browser` 且本地 Vite 端口不可用。
- #96 Web 导航 registry、#92 健康 capability、#93 AI task capability registry 等扩展性审查项仍开放，不因本轮局部修复而标记为全部关闭。

## 第四十六轮审查问题修复（2026-09-04）

### #94 简历版本来源展示已统一

- 新增 `web/src/utils/resumeSource.ts`，集中维护来源翻译 key，并对未来/旧客户端未知值使用稳定的 `sourceOther` fallback。
- 简历详情、版本对比、面试、ATS、投递、沟通和成就引导页面的来源选择器/标签均改用同一 helper；未知后端枚举不会再把 `undefined` 传给 `t()`，也不会直接暴露原始枚举字符串。

### #93 AI task capability 的默认分支 fail-open 已收紧

- 新增 `AiTaskCapabilityRegistry`，为当前所有 `AiTaskType` 显式声明 consent categories、执行模式和是否允许通用入口，并在静态初始化时检查枚举完整性。
- 创建、重试、worker 分发、配额和 Prompt 模板都会先经过注册校验；配额和 Prompt 不再对未知类型使用 `30` 或通用模板兜底，worker 也不会把未注册类型标记为成功。
- `AiTaskCapabilityRegistryTest` 覆盖当前枚举全量注册、可选 JD 类别和空类型 fail-closed。

### #96 Web 导航/首页工作流注册漂移已收紧

- 新增 `web/src/navigation/registry.ts`，桌面导航、移动导航和首页四步工作流共用同一组 route target 与翻译描述。
- Router 启动时校验注册目标均能解析到实际路由；`check-i18n` 新增动态 registry key 收集与双语目录校验，避免动态 `t(\`navGroups...\`)` 漏检。
- 增加 i18n guard 单测，覆盖 registry 的 label、description 和 workflow 文案 key。

### #92 API/PDF 健康状态不再无条件报告 UP

- API 的健康响应保留旧 `capabilities` 字符串列表，同时新增 `checks`：API、AI provider、PDF renderer 各自拥有 `UP/DOWN` 状态；依赖不可用时总体为 `DEGRADED`。
- `AiProviderRegistry` 只路由到 `isAvailable()` 的 provider；无可用 provider 时不会把不可用实现送入执行路径。
- PDF service 的 `/health` 会检查 Chromium 是否能启动并动态报告 renderer 状态，模板数量从 `TEMPLATE_CODES` 生成；浏览器池新增 readiness 单测。
- 首页现在区分“服务在线”和“部分服务不可用”，旧的仅有 `{ status: 'UP' }` 健康响应仍可兼容。

### 本轮验证与边界

- 通过：`mvn -q "-Dtest=AiTaskCapabilityRegistryTest,AiTaskServiceTest,AiQuotaServiceTest,TaskExecutionServiceTest,SystemControllerTest" test`；`mvn -q "-DskipTests" compile`。
- 通过：`Set-Location web; npm run build`（i18n guard、`vue-tsc`、Vite）。
- 通过：`Set-Location pdf-service; npm test`（18/18）。
- 内置浏览器 smoke 使用新标签 `http://127.0.0.1:5173/`：前端在新 router/navigation registry 下正常启动，英文桌面导航和首页四个 workflow 链接均可见。后端/PDF 未启动，因此页脚显示预期的 offline 状态；这不构成 API/PDF 健康验收。未调用真实 AI provider，未写入业务数据。PDF 健康探针的 Chromium readiness 仍需实际部署启动 PDF service 后再检查。

### #91 评分规则权重与聚合漏算已补上当前边界

- `RuleRegistry` 现在集中持有命名权重，启动时拒绝负数、超过 1 或总和不等于 `1.0` 的配置。
- `ScoringService` 通过 registry 按规则名聚合，并将 `ruleScores` 写入 `explanation_json`；当规则注册集合与实际分数集合不一致时直接失败，避免新增规则只计算/只展示而漏进总分。
- 现有 `MatchResponse` 的三项兼容字段保持不变；新增评分维度的完整 adapter、DTO/Web 展示注册仍需后续扩展，不把本轮标记为全部 capability registry 已完成。
- 通过：`mvn -q "-Dtest=RuleRegistryTest,ScoringServiceTest" test`。
