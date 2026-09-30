# ideation 101 条 finding 闭环核对报告（2026-09-30）

> 性质：**只读核对**（4 个并行 agent 分区间核对 + 5 处关键证据人工抽查验证）。
> 对象：`docs/ideation/2026-09-04-project-optimization-ideation.md`（101 条 finding + 27 条 Ranked Ideas + 38 行「本轮新增核对」表）。
> 基线：`codex/resume-loop-enhancements @ d4239c6`。核对时机：2026-09-30（该文档自 09-04 后首次系统性追踪闭环状态）。
> 结论：**过半已闭环**（多轮修复覆盖）；仍存在项按主题聚为 6 组，推荐下一批见 §4。

---

## 1. 总览

| 区间 | 已闭环 | 部分闭环 | 仍存在 | 架构方向类/不建议 |
| --- | --- | --- | --- | --- |
| finding #1-#38（当前风险清单） | 5（#6/#10/#27/#34/#35） | 3（#15/#17/#20） | 30 | 1（#19） |
| finding #39-#66 | 5（#39/#40/#42/#56/#57） | 3（#49/#59/#66） | 20 | 0 |
| finding #67-#101 | 15（#69/#76/#77/#84/#90/#92~#101） | 1（#91） | 11 | 8（#79~#83/#86~#88） |
| Ranked Ideas 1-27 | 1（#10） | 12 | 14 | 0 |
| 新增核对表（38 行） | 2 | 9 | 25 | 0 |

> 区间间存在重复条目（如 Ranked #10 = finding #10；表行「用户标识入日志」= finding #68）。下文去重后按主题归并。

**抽查验证（人工复核，非 agent 结论）**：
- ✅ #68 `ExportService.java:87` debug 日志记 `userId` —— 属实，且违反 `PROJECT_CONTEXT.md`「用户 ID 不得写入日志」硬约束（ideation 行 543 已登记 P2）
- ✅ #71 `AiConsentRepository.java:19` 仅按 `createdAt` 排序、无 `id` tie-break —— 属实
- ✅ #2 `CareerMaterialRepository.java:22-30` search 只匹配 `title`/`sourceText`、不搜 `contentJson` —— 属实（功能性缺陷）
- ✅ #60 `application.yml:170` 租约 180s < `:167` provider 读超时 300s —— 属实
- ✅ #22 `MySql57MigrationLiveIT` 仍断言 V19→V22，仓库已至 V26 —— 属实（环境门控，默认跳过）

## 2. 仍存在项（去重后，按主题聚类）

### 2.1 隐私与日志（2）

| # | 问题 | 现状证据 | 评级 |
| --- | --- | --- | --- |
| 68 | PDF 创建 debug 日志记 userId（违 PROJECT_CONTEXT 隐私约束） | `ExportService.java:87` | **高**（修复成本≈0） |
| 71 | AI 授权最新事件仅按 createdAt 排序，同毫秒并发撤回/授权顺序无稳定契约 | `AiConsentRepository.java:19` | **高**（修复成本≈0） |

### 2.2 功能性缺陷（2）

| # | 问题 | 现状证据 | 评级 |
| --- | --- | --- | --- |
| 2 | 搜索只匹配 title/sourceText；命中词只存在于 contentJson 结构化字段（skillName/outcome 等）时返回**错误零结果**，而摘要却从这些字段生成 | `CareerMaterialRepository.java:22-30` vs `CareerMaterialService.java:336-358` | **高** |
| 66 | 错误码→文案映射（TC-3）已建基础设施，但仅 2 个视图接入；其余 ~6 处仍直透服务端中文 message，英文界面可能显示中文 | `GenerationWorkbenchView.vue:194,219`、`AccountView.vue:66`、`GenerationConfirmView.vue:72,160,174,188`、`MaterialSelectionConfirmView.vue:97,129,173,188`、`ResumeEditorView.vue:836` | 中 |

### 2.3 AI 调用韧性与并发健壮性（6）

| # | 问题 | 现状证据 | 评级 |
| --- | --- | --- | --- |
| 60 | worker 租约 180s < provider 读超时 300s：失联 worker 可继续调用 provider 且被第二 worker 接管 → 重复外部调用/计费 | `application.yml:170` vs `:167`；`TaskExecutionService.java:286-290` | **高** |
| 45 | refresh token 轮换「先查后存」无行锁/条件更新，并发刷新可能双双成功签发后继 token | `AuthService.java:115-139`；`AuthSessionRepository.java:11` | 中 |
| 46 | 注册/改邮箱唯一键竞态落入通用 500（无 `DataIntegrityViolationException`→409 映射） | `AuthService.java:73-76`；`GlobalExceptionHandler.java:65-77` | 中 |
| 48 | AI 任务幂等检查并发下同 key 重放可能一成功一 500（未捕获唯一键冲突回读赢家） | `AiTaskService.java:77-101` | 中 |
| 49 | 幂等键契约分散：128+trim / 64 / 无长度 / 无 trim 各入口不一致 | 各 Controller（`AiTaskController:47-48`、`InterviewController:37` 等） | 中 |
| 85/#89 | ATS prompt/schema 版本未绑定任务快照；`INTERVIEW_COACH` 字符串 operation 分流，未知值静默落通用路径 | `AtsAiAnalysisService.java:35-49`；`TaskExecutionService.java:123,152-157` | 中 |

### 2.4 并发一致性（乐观锁/唯一约束）（9）

| # | 问题 | 现状证据 | 评级 |
| --- | --- | --- | --- |
| 67 | 职业资料更新无乐观锁（最后写入覆盖） | `CareerMaterialService.java:113-132`；无 `@Version` | 中 |
| 70 | 个人资料首次 upsert 竞态可能 500，无版本边界 | `PersonalProfileService.java:42-61` | 中 |
| 73 | 自定义沟通模板更新无版本条件 | `CommunicationTemplateService.java:87-98` | 中 |
| 24 | 面试资产「幂等创建」仅应用层，DB 无 `(user_id, interview_record_id)` 唯一索引 | `InterviewAssetService.java:71`；V5 迁移 | 中 |
| 25 | 模板使用计数非原子读改写（无 `@Version`） | `CommunicationService.java:84` | 中 |
| 38 | `resume.current_version_id` 无乐观锁/条件更新 | `Resume.java:25-36` | 中 |
| 75 | 面试记录仅按 createdAt 排序，同毫秒轮次顺序不稳定（有 round_no 未用） | `InterviewRecordRepository.java:12,23,28` | 中 |
| 78 | 规则评分无重放幂等，重复请求无限追加等价行 | `ScoringService.java:139`；`ScoringController.java:37-44` | 中 |
| 28 | 配额/跟进查询缺组合索引（`(user_id,task_type,created_at)` 等） | V1/V20/V23 迁移 | 中 |

### 2.5 读模型与性能（6）

| # | 问题 | 现状证据 | 评级 |
| --- | --- | --- | --- |
| 1 | 职业资料列表读完整实体（含 contentJson + MEDIUMTEXT sourceText）后内存过滤 | `CareerMaterialService.java:80-85` | 中 |
| 3 | stats 已列投影但仍读全量行 Java 聚合 | `ApplicationService.java:126-163` | 中 |
| 50 | 岗位/版本/投递/模板/面试列表返回完整长字段（无投影） | `JobDescriptionService.java:66-67`；`api/resume.ts:35` | 中 |
| 52 | 首页任务恢复列表携带完整 AI 结果 JSON（未做 metadata-only） | `AiTaskService.java:114-119,148-163` | 中 |
| 11 | 多列表接口无分页契约（仅职业资料搜索有 Page） | 各 Controller | 中 |
| 33 | 投递编辑页版本定位为并行 N+1（随简历数扇出） | `ApplicationsView.vue:145-155` | 中 |

> 另：09-26 报告 PA-2（JwtAuthFilter 每请求查库）仍成立，且因 finding #6 的安全闭环（每请求校验 ACTIVE）其缓存收益**增大**。

### 2.6 前端竞态与体验（5）

| # | 问题 | 现状证据 | 评级 |
| --- | --- | --- | --- |
| 58 | 简历版本选择器旧响应可覆盖新选择（无 epoch/取消） | `useResumeJobOptions.ts:33-41` | 中 |
| 63 | 版本对比重复请求 + 旧 diff 可覆盖新选择 | `CompareVersionsView.vue:87-120,201-203` | 中 |
| 64 | 沟通模板列表/预览缺最新请求保护 | `CommunicationView.vue:265-303` | 中 |
| 65 | 章节关联资产筛选可能显示旧章节结果 | `ResumeEditorView.vue:74-85`；`ResumeDetailView.vue:116-129` | 中 |
| 61 | 认证初始化断网后页内永久锁定（无重试入口） | `auth.ts:21-37` | 中 |

### 2.7 AI 上下文与输入边界（6）

| # | 问题 | 现状证据 | 评级 |
| --- | --- | --- | --- |
| 53 | 素材生成私有 8 章白名单，编辑器其余章节静默丢弃 | `materialGeneration.ts:19,34-36` | 中 |
| 54 | 面试上下文只投影部分章节（objective/志愿/课程/成果/自定义不进） | `InterviewContextSanitizer.java:37-53` | 中 |
| 55 | 沟通 AI 输入停留旧 8 章白名单 | `CommunicationAiPromptBuilder.java:18-19,106-113` | 中 |
| 36 | 无统一 prompt 字节/token 预算（仅条数限制） | `MaterialSelector.java:107-112` | 中 |
| 44 | 通用 AI 任务 `input` 无服务端语义大小约束（可直接 API 提交超大 JSON） | `CreateAiTaskRequest.java:19-28` | 中 |
| 26 | `ai_task` 无保留/归档/清理接缝（内联 JD/资料/简历留存持续增长） | `AiTask.java` 无 TTL 字段；无清理作业 | **高**（需产品口径） |

### 2.8 资源边界与交付链（8）

| # | 问题 | 现状证据 | 评级 |
| --- | --- | --- | --- |
| 17 | 导入解析无页数/文本长度/耗时上限（仅入口 5MB） | `ResumeImportService.java:62` | 中 |
| 21 | 发布脚本复合命令 `npm run check; npm test` 仅查末位退出码（前步失败被掩盖） | `Test-ReleaseReadiness.ps1:15,23` | 中 |
| 30/#54 | PDF worker 忽略 `releaseSuccess` stale 返回（孤儿文件不被清理）；`get` 无条件 EXPIRED | `ExportTaskWorker.java:93`；`ExportService.java:97-102` | 中 |
| 29 | 沟通 AI 草稿先落库、后 `releaseSuccess`（租约接管可留重复草稿） | `CommunicationAiService.java:46-58` | 中 |
| 43 | `RateLimitFilter.maxBuckets` 非硬上限 | `RateLimitFilter.java:79-80,110-113` | 中 |
| 14 | PDF 导出无幂等键/结果复用（重复排队渲染） | `ExportService.java:61` | 中 |
| 22 | MySQL 5.7 迁移门禁仍断言 V19→V22（仓库 V26）；V23~V26 的 5.7 兼容未证明 | `MySql57MigrationLiveIT`；`Invoke-MySql57MigrationGate.ps1:38` | 高（需 5.7 环境） |
| 72 | 职业资料搜索词无服务端长度边界 | `CareerMaterialController.java:52-63` | 中 |

### 2.9 测试补齐（沿用 09-26 报告剩余项）

| 项 | 问题 | 现状 |
| --- | --- | --- |
| TC-4 | ATS fallback 7 键无前端文案映射（仅类型定义） | `web/src/api/ats.ts:31` |
| TC-5 | generic 白名单拒绝分支无 IT | server test 无断言 |
| TC-6 | confirm 乐观锁 40901 无 IT 级断言（单测已有） | `ConfirmationControllerIT` 无 40901 |
| TC-7 | answer AI 模式无 HTTP 层重放 IT | `InterviewControllerIT` 仅 start/follow-up 有 |
| #4 | 证据边界（evidenceReady 等）无浏览器 e2e | `web/e2e` 无命中 |

### 2.10 架构方向类（不建议现在做，仅登记）

#19（投递状态机跨运行时契约）、#79~#83（模板/沟通/导入/模式/资料类型多 runtime 登记）、#86~#88（ATS/面试 schema 重复维护）、#91 余项、#15 全量类型化配置 —— 均为扩展性接缝，单作者项目当前属过度工程边界。

### 2.11 台账对账补登（38 行「本轮新增核对」表 → §2 漏登项，2026-09-30 复核）

对账方法：把 `docs/ideation/2026-09-04-project-optimization-ideation.md` 的 38 行核对表（行 506-543）逐行映射到本报告 §2 的 finding 号，无法映射且既未闭环也非架构方向的条目在此补登并逐项核实（下表中 ✅ 已完成项见 §4 第十四/十五批）。

| 台账行 | 核实证据 | 结论 |
| --- | --- | --- |
| AI 异步失败消息 | `TaskLeaseService.java`（AI 任务）与 `InterviewOperationSupport.java` 等原样写入 `VARCHAR(1024)` 列，仅 `ExportTaskLeaseService` 截断 | **存在缺陷（超长消息致失败写入失败）→ 第十五批修复** |
| AI 配额观测口径 | `AppObservability.registerQuotaLimit` 旧 gauge 用全站任务行数，限流用每用户尝试数 | **口径不一致 → 第十五批对齐** |
| 简历版本消费策略 | `ScoringService.score` 不校验 `deletedAt`，ATS/导出/投递/沟通均拒绝归档版本 | **契约不一致 → 第十五批对齐** |
| PDF readiness 与容量 | readiness 已闭环（`/health` 动态探测 Chromium + 模板数 + 浏览器池 readiness 单测）；容量/队列/关闭语义已在第十六批落地（许可 + FIFO 队列上限 + drain/waitForIdle + 503 可重试契约） | ✅ 已完成（第十六批） |
| Worker 领取与索引 | **已用本机 MySQL 5.7.24 + 20 万行 ai_task 实跑取证**（2026-09-30）：领取查询 `EXPLAIN` 为 `key=PRIMARY`（按主键序扫描 + `LIMIT 5` 早停，无 filesort），实测 **0.32/0.40ms**；留存清理同样走 PRIMARY（200 行候选约 2.2ms）；`idx_export_task_claim` 已含 `id`（V16） | ✅ 核实：无需新增索引（注释与实现亦一致） |
| 文件导入资源边界 | `ResumeImportService` 的长度上限作用于归一化前文本，归一化只去 NUL + trim（不增长）；解析有 15s 超时（#17）；入口 5MB | ✅ 核实：已覆盖，无需独立上限；DOCX 展开量依赖 POI 防护 + 入口上限，留观 |
| JD 解析（平铺 contains） | `JdKeywordParser.extractKeywords` 用大小写不敏感 `contains`，存在「JavaScript 命中 Java」类误命中 | 已知取舍：有真实误命中案例再评估；当前评分解释页的 matched/missing 机制可缓解，留观 |
| 跨运行时时间契约 | 后端 `LocalDateTime` + 服务器自然日；Web 按浏览器时区展示 | 归架构方向类（同 §2.10），单时区部署无故障 |
| AI 任务恢复收件箱 / 简历导入来源追溯 / PDF 对象存储 / AI 提供者路由 / 投递流水线契约 | 见台账原文 506/507/513/519/520 行 | 归架构方向类（产品范围或扩展性接缝），当前无故障 |
| 观测 gauge 查询成本（本批取证新增） | `resume_ai_quota_daily_attempts` 的查询形状（`task_type = ? AND created_at > ?`）在 20 万行 ai_task 上 `EXPLAIN` 为 `type=ALL rows≈199191`，实测 **62~66ms/次**；Prometheus 每次抓取对每个任务类型各执行一次 | **存在成本缺陷 → 第十七批补 V34 索引（0.22~0.29ms）** |

### 2.12 契约文档完整性对账（docs/05 × 实现，2026-10-01 新增核对）

方法：新增静态门禁 `ApiDocCoverageGateTest`（按控制器注解枚举全部端点，要求 `docs/05-接口设计说明书.md` 全覆盖；路径占位符名称归一，如 `{id}` ≡ `{resumeId}`；fail-closed，不静默跳过），并用同一枚举做反向抽查。

- **正向缺口 32 个端点**（95 个实现端点中约 1/3 未进说明书）：个人资料 3、简历导入 1、简历（按 JD / 切换当前版本）2、版本（归档/恢复/取消归档）3、JD 引用 1、AI（续办/重试/选材/确认选材）4、ATS（查询/AI 重试）2、沟通（模板库 6 + 草稿 1）7、投递统计 1、导出重试 1、认证（改邮箱/改密码/登出全部）3、职业资料搜索 1、系统健康 2 —— 已按控制器与 DTO 事实补入 `docs/05` §2.9 / §3.4 / §4.6-4.7 / §5.4 / §6.5 / §7.9 / §8.3 / §9.5-9.6 / §11.4 / §12-14。
- **反向陈旧契约 6 处**：`POST /api/ai/generate-resume-for-job`、`POST /api/ai/optimize`、`POST /api/ai/rewrite-summary`、`POST /api/ai/rewrite-project`、`POST /api/ai/generate-resume-from-material`、`GET /api/career-materials/{id}/references`（后两者已无对应实现）——前五处按现行流程重写（选材 → 确认 → 生成子任务 / 通用任务白名单 `RESUME_OPTIMIZE` / 统一 `INLINE_OPTIMIZE` 章节重写），最后一处从未实现，已移除并注明。
- 门禁随 server 测试进入 CI：新增端点不补契约即失败（防再次漂移）。

### 2.13 客户端 IP 契约对账（2026-10-01 第二十批扫描，已随本批修复）

方法：对「生产部署链路 × 客户端 IP 消费方」做一次全量核对（部署配置 + nginx 代理链 + 两处 IP 读取点）。

| 项 | 核实证据 | 结论 |
| --- | --- | --- |
| 生产限流按 IP 分桶 | `RATE_LIMIT_TRUST_FORWARDED_HEADERS` 在 `production.env.example`、`production.ip-test.env.example`、直连部署 `.env` 样例中**均未出现** → 默认 `false`；compose 与直连部署都经 nginx 代理，`remoteAddr` 恒为代理地址 | **存在缺陷 → 第二十批修复**：所有客户端共用同一分桶（登录 10/min、注册 5/min、刷新 30/min、简历解析 6/min、JD 解析 15/min 退化为全站共享阈值），与文档「按 IP 限流」契约不符 |
| 开启信任后的伪造面 | 最外层 nginx 用 `$proxy_add_x_forwarded_for` **追加**（客户端伪造值留在最左），应用取最左值分桶 | **存在缺陷 → 第二十批修复**：可绕过按 IP 限流，并用海量唯一值把分桶表顶到 `maxBuckets`（#43 fail-closed 误伤新 key 请求） |
| 会话审计 IP 语义 | `AuthController.clientIp` 无视信任开关**无条件**读 XFF（与限流器的可配置语义不一致） | **存在缺陷 → 第二十批修复**：统一到 `ClientIpResolver`（同一开关、同一解析规则） |
| prod profile 启动校验 | `ProductionConfigurationValidator` 只校验 JWT/PDF/DB/BAILIAN/COOKIE_SECURE | **已随本批补齐**：`RATE_LIMIT_TRUST_FORWARDED_HEADERS` 必须为 true（与 COOKIE_SECURE 同级 fail-closed） |

修复动作：新增 `ClientIpResolver`（`common/api`，限流分桶与会话审计共用）；三份最外层 nginx（`edge.conf` / `host.conf` / `edge-ip-test.conf.template`）改为 `$remote_addr` 覆写、内层 `web` 保持追加；两份 env 示例 + 直连 `.env` 样例显式开启；新增静态门禁 `DeployProxyClientIpContractTest`（最外层必须覆写、内层必须追加，防再次漂移）。

### 2.14 PDF 导出死线链对账（2026-10-01 第二十一批扫描，已随本批修复）

方法：核对「pdf-service 排队/渲染 → API 读超时 → worker 租约」三层时间边界各自预算（第二十批扫描登记候选），逐层取证。

| 项 | 核实证据 | 结论 |
| --- | --- | --- |
| 读超时 vs 服务端上界 | API `app.pdf.render-timeout-seconds` 默认 15s；pdf-service 单请求预算为 `setContent` 15s + `pdf` 15s = 30s（`page.setDefaultTimeout(15_000)` 硬编码），队列等待无时长上限 | **存在缺陷 → 第二十一批修复**：合法慢渲染（16~45s，服务端自身预算内）被客户端先断开——渲染被浪费、任务被误判失败（客户端先断、服务端后成） |
| 排队等待无时长上限 | `browserPool` FIFO 队列只限条数（16），等待时长不受限 | **存在缺陷 → 第二十一批修复**：新增 `PDF_SERVICE_QUEUE_TIMEOUT_MS`（默认 15s），排队超时以可重试 503 拒绝；服务端总耗时上界从此有界（15 + 15 + 15 = 45s） |
| 租约 vs 领取批次 | `claimBatch` 在领取时一次性把 `lease-seconds`（90s）写给整批，worker 循环内串行处理 `batch-size`（此前默认 3） | **联动缺陷 → 本批修复**：读超时提升到 50s 后 3 × 50s 会超出 90s 租约（后续任务处理途中被接管 → 重复渲染）；`batch-size` 默认 3 → 1（串行处理下吞吐不变），保证「租约 ≥ batch × 读超时 + 余量」 |
| 默认值三处各写一份 | `application.yml`、`PdfServiceClient`/`ExportTaskWorker` 的 `@Value` 兜底分别维护默认值 | **已随本批固化**：新增静态门禁 `PdfDeadlineContractTest`（链序 + 跨运行时默认值一致性），防再次漂移 |

修复动作：pdf-service 新增 `PDF_SERVICE_QUEUE_TIMEOUT_MS` / `PDF_SERVICE_RENDER_TIMEOUT_MS`（默认均 15000ms；非法取值启动即失败），队列项带超时计时器（获得名额/drain 时清理）；API 读超时默认 15 → **50s**（`PDF_RENDER_TIMEOUT_S`；CI profile 30 → 50 同步对齐），PDF worker `batch-size` 默认 3 → **1**；`server/.env.example` 与 `pdf-service/README.md` 补死线链说明；新增静态门禁 `PdfDeadlineContractTest`。

### 2.15 进程关闭语义对账（2026-10-01 第二十二批扫描，已随本批修复）

方法：核对 API 与 pdf-service 两侧「收到 SIGTERM → 等待在途工作 → 进程退出」的整条链路，以及容器/systemd 宽限期是否覆盖等待上限。

| 项 | 核实证据 | 结论 |
| --- | --- | --- |
| API 停机语义 | `application.yml` 未声明 `server.shutdown`（Spring Boot 默认 `immediate`）：SIGTERM 到达即断开在途 HTTP 请求 | **存在缺口 → 第二十二批修复**：发布/重启期间连接被重置（导入解析最长 15s、导出/AI 发起请求均可能被打断） |
| 容器宽限期 | `docker-compose.prod.yml` 的 `api` 未声明 `stop_grace_period`（docker 默认 10s） | **联动缺口 → 本批修复**：即使开启优雅停机，10s 宽限期也会中途 SIGKILL（须显式放大） |
| pdf-service 侧 | 已有 drain + `stop_grace_period: 30s`（第十六批） | ✅ 核实：语义完整；本批把「宽限期 ≥ 停机上限 + 余量」的关系固化为静态门禁 |
| worker 关停语义 | AI/PDF worker 线程均为守护线程（`AiTaskWorkerConfig` 工厂 `setDaemon(true)`），在途任务由租约接管（application.yml 注释） | ✅ 刻意不等待：优雅停机上限只作用于 Web 请求，不会拖长发布停机；在途 AI/PDF 任务按既有租约语义恢复 |

修复动作：`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:20s}`；compose `api.stop_grace_period: 25s`（> 20s + 余量；systemd 默认 `TimeoutStopSec=90s` 已足够）；新增静态门禁 `ShutdownContractTest`（API/PDF 两侧：容器宽限期 ≥ 停机上限 + 5s，防再次漂移）。

### 2.16 协议级调用方错误映射对账（2026-10-01 第二十四批扫描，已随本批修复）

方法：对「调用方传错」的 HTTP 协议错误做**真实 HTTP 探针**（本机 H2 实例 + curl）与 MockMvc/单测双取证，检查是否落 catch-all 兜底被报成 500（与既有「缺请求头 → 500」修复同族）。

| 项 | 取证（修复前） | 结论 |
| --- | --- | --- |
| 方法不允许（405） | 真实 HTTP `POST /api/system/health`（GET-only）→ **500**「系统异常」 | **存在缺陷 → 第二十四批修复**：405 + 40001 |
| 不支持的请求媒体类型（415） | 真实 HTTP `POST /api/auth/login` + `Content-Type: text/plain` → **500** | **存在缺陷 → 第二十四批修复**：415 + 40001 |
| 不可接受的响应媒体类型（406） | 修复首版后实测仍坍缩为 **401**（错误体按请求 Accept 无法写出 → ERROR 派发被安全链当匿名请求拒绝） | **联动缺陷 → 修复**：协议级错误响应显式 `Content-Type: application/json`，实测 406 + 统一信封（`Accept: xml` 亦不再坍缩） |
| 超限上传（413） | 真实 HTTP 6MB 上传（`max-file-size=5MB`）→ **500** + ERROR 全栈（`MaxUploadSizeExceededException` 落兜底；生产前置 nginx 时用户看到的是 413，直连 API 语义丢失） | **存在缺陷 → 第二十四批修复**：413 + 40001；非法 multipart 表单 → 400 + 40001 |
| 413 的**送达**依赖 swallow | CI 首跑（ea52f77）用 6MB 上传时客户端在写完前收到连接重置（HTTP 0，413 未送达）；本地 Windows 未复现（时序差异）。根因：Tomcat 默认 `max-swallow-size=2MB`，被拒请求的剩余字节需读掉才能写出响应 | **交付缺陷 → 跟进修复（98db24e）**：`server.tomcat.max-swallow-size: 8MB`（覆盖 6MB 请求上限 + 余量）+ 断言体积收敛为 5.5MB（仅略超上限，需 swallow 的剩余最少）；CI 复跑通过 |

修复动作：`GlobalExceptionHandler` 新增协议级 handler（405/415/406、413、非法 multipart 400），响应显式 JSON 内容类型；`server.tomcat.max-swallow-size: 8MB`（保证 413 送达）；`docs/05` §1.3 错误码表与 §13 导入契约同步；新增 `HttpSemanticsIT`（MockMvc 405/415/406）+ 5 个 handler 单测 + `functional-tests/suite_core.py` 超限上传真实 HTTP 断言（本地整套件 28/28 通过；CI 功能回归复跑通过）。

### 2.17 顺序确定性对账（2026-10-01 第二十五批扫描，已随本批修复）

方法：对「取最近一条 / 按时间截断」这类**顺序敏感**的读路径做全仓扫描，逐条核对是否存在并列时无稳定契约的单键排序（与已修复的 consent #71、面试记录 #75 同族）。

| 项 | 取证（修复前） | 结论 |
| --- | --- | --- |
| 面试「最近失败/处理中尝试」 | `interview_ai_attempt.updated_at` 是全库**唯一**秒级 `DATETIME`（V20 L39-40；其余表均 DATETIME(3)）；`findFirstBySessionIdAndStatusOrderByUpdatedAtDesc` 无 tie-break——同一秒内不同 round/operation 可各写入一条 FAILED，取哪条无契约 → aiFailure 的 stage/messageCode/retryable 可能展示较早那条（误导用户）；`getState` 的 PROCESSING 超时判定同样受影响 | **存在缺陷 → 修复**：`findFirstBySessionIdAndStatusOrderByUpdatedAtDescIdDesc`（2 个调用点同步） |
| 资料选择的「normal 截断」与候选排序 | `career_material.updated_at` 为 DATETIME(3)，批量确认可同毫秒插入多条；`MaterialSelector.select` 按仓库返回顺序 `subList(0, limit)` 截断 → 并列时**被丢弃的素材集合** run-to-run 变化；`JobMaterialSelectionService` 候选排序 `score desc → updatedAt desc` 亦无 id tie-break——进入提示词的 60 条候选集不稳定 | **存在缺陷 → 修复**：仓库方法改 `findByUserIdOrderByUpdatedAtDescIdDesc`（5 个调用点 + 4 个测试文件 11 处引用同步）；候选比较器补 `.thenComparing(id desc)`；`MaterialSelector` Javadoc 注明确定性依赖 |
| 死代码 | `AiConsentRepository.findFirstByUserIdAndEventTypeOrderByCreatedAtDesc` 全仓无调用（#71 改造残留） | **已删除**（含未使用导入） |
| 其余 `OrderBy...Desc` 展示列表（resume/JD/application/communicationTemplate/communicationDraft） | 均为 DATETIME(3)、并列概率低且仅影响展示顺序 | 保持现状（仅报告口径） |

修复动作：两处仓库方法改双键 `(updated_at desc, id desc)`；`JobMaterialSelectionService` 候选排序补 id 兜底；`MaterialSelector` Javadoc 增加确定性约束说明；删除死方法。新增 `CareerMaterialOrderingIT`（同一毫秒三条、插入顺序与期望相反 → 必须按 id 降序返回）与 `InterviewAiAttemptOrderingIT`（同一秒两条 FAILED → 必须取 id 更大者，且错误码为后写那条）。

### 2.18 限流路径归一化对账（2026-10-01 第二十六批扫描，已随本批修复）

方法：对「安全过滤器按路径字面量精确匹配」与「Spring MVC **解码后**路由」的语义差做**真实 HTTP 探针**（本机 H2 实例 + curl，登录限流临时降为 2 次/分钟取数）。

| 项 | 取证（修复前） | 结论 |
| --- | --- | --- |
| 百分号转义路径绕过登录限流 | 同一 IP：明文第 3 次 → **429**；`POST /api/auth/%6Cogin`（%6C=小写 l，MVC 解码后路由到同一 login 控制器）→ **401「账号或密码错误」** 直达控制器且未计数——限流形同虚设 | **存在缺陷 → 修复**：受限端点的路径解析改用与 MVC 同语义的 `UrlPathHelper.getPathWithinApplication`（解码 + 去矩阵参数） |
| 同族端点 | `/api/auth/%72efresh` 同样绕过（401 直达）；register、resume-imports/parse、jobs/{id}/parse 与 login 同一实现（`getRequestURI` 字面量匹配） | 同批修复（单点改动覆盖 5 个受限端点） |
| 矩阵参数（;）等价路径 | `/api/auth/login;x=1` 不计入分桶（字面量匹配未命中） | 修复后计入同一分桶（fail-closed；该形态在真实链路上本就不会到达控制器） |
| 回归确认（修复后真实 HTTP） | 明文 401/401/429 基线不变；`%6C`、`%6c` 均 **429**；非限流路径的编码形式（`/api/system/h%65alth`）仍 200 | 无误伤 |

修复动作：`RateLimitFilter.doFilterInternal` 的路径来源由 `request.getRequestURI()` 改为 `PATH_HELPER.getPathWithinApplication(request)`（`UrlPathHelper` 默认开启解码与 semicolon 清理），分桶键随之归一——转义路径不可能再落到独立键或漏计。测试：`RateLimitFilterTest` 新增 2 例（`%6C/%6c` 与明文共享分桶；`;` 计入分桶），`AuthRateLimitIT` 新增 1 例（以 `RequestPostProcessor` 强制原始 requestURI 还原真实 Tomcat 形态：明文耗尽后转义路径必须 429——修复前实测红为 401、修复后绿）。

### 2.19 凭证变更端点限流覆盖对账（2026-10-01 第二十七批扫描，已随本批修复）

方法：对「验证当前密码」的端点做覆盖审计（端点清单 × 验证链 × 限流映射），并以真实 HTTP 探针取证（本机 H2 + curl，凭证限流降为 2/分钟）。

| 项 | 取证（修复前） | 结论 |
| --- | --- | --- |
| 改密/改邮箱未被限流 | `POST /api/auth/me/password` 与 `POST /api/auth/me/email` 都调用 `AuthService.requireCurrentPassword`（仅 `passwordEncoder.matches`，无尝试节流），且不在 `RateLimitFilter.limitFor` 的受限路径内——配置阈值 2 下连续 4 次改密 + 1 次改邮箱**全部 401、无 429**；持有被盗 access token 者可绕过登录的 10/min 无限试口令（改密成功即完成账号接管） | **存在缺陷 → 修复**：两端点纳入限流（默认 5/min，`app.security.rate-limit.change-credential-per-minute`），且**共享同一分桶**（按路径各自分桶会给攻击者交替双倍预算） |
| 其它无口令验证的认证端点 | `/api/auth/me`（资料更新）、`logout`、`logout-all` 不验证密码，无爆破面 | 保持不限流（用例断言 `/api/auth/me` 不受影响） |
| 修复后真实 HTTP | 第 3 次改密 → **429 + 42901**；随后改邮箱 → **429**（共享分桶生效）；未登录请求也先计数再 401 | **已修复** |

修复动作：`RateLimitFilter` 新增 `change-credential-per-minute`（构造注入，默认 5），`limitFor` 增加两端点映射，`bucketGroup()` 将两端点归为 `/api/auth/me:credential` 一组（其它端点仍按路径独立分桶）；`application.yml` 与 `server/.env.example` 同步 `RATE_LIMIT_CREDENTIAL`；`docs/05` §1.3 受限端点清单更新。测试：`RateLimitFilterTest` +2（共享分桶 / 资料更新不受影响）、`AuthRateLimitIT` +1（真实过滤链：未登录先计数，改密耗尽后改邮箱 429）。

### 2.20 上传路径体积与死线对账（2026-10-01 第二十八批扫描，已随本批修复）

方法：核对「上传限制」与「同步长耗时请求死线」在**全部三层**（外层 nginx → 内层 nginx → 应用 → 前端）的一致性与可达性（与第二十一批 PDF 死线链同族）。本机 Docker 守护进程不可用，故证据来自配置内容 + 构建链路（镜像实际打包哪份配置）+ nginx 文档化默认值。

| 项 | 取证（修复前） | 结论 |
| --- | --- | --- |
| 内层 nginx 把上传上限悄悄收紧为 1m | 容器 prod 拓扑 = `edge` → `web` → `api`（`deploy/docker-compose.prod.yml`）。`edge` 用 `deploy/nginx/edge.conf`（`client_max_body_size 5m`），但 `web` 镜像实际打包的是 `web/nginx.conf`（`web/Dockerfile` L12 COPY，**未声明**该指令）→ 生效 nginx 内置默认 **1m**。`edge` 已放行的 1~5MB 简历会被内层拒绝，且响应是 nginx 自带 HTML 而非统一信封。**该缺陷对现有测试全谱不可见**：功能回归直连 API（不经 nginx）、本地开发走 Vite 代理（无体积限制）、`deploy/nginx/web.conf` 同步副本同样漏声明 | **存在缺陷 → 修复**：`web/nginx.conf` 与 `deploy/nginx/web.conf` 均补 `client_max_body_size 5m`，与 `edge` 及 `spring.servlet.multipart.max-file-size`(5MB) 对齐 |
| 层间关系无门禁 | 只有注释声称「两份内容需保持一致」；无任何检查断言「内层上限 ≥ 外层上限」，故外层承诺可被内层静默收紧 | **补静态门禁**：`UploadPathContractTest` |
| 前端上传超时早于服务端预算 | `web/src/api/resumeImport.ts` 未覆盖超时 → 用全局 10s，而服务端单次解析预算 15s（`resume-import.extract-timeout-ms`）：慢但成功的解析被客户端先判失败（前端显示泛化解析错误），服务端仍在校验；与仓库既有惯例（导出 30s、面试步进 60s 均显式放宽）不一致 | **存在缺陷 → 修复**：上传请求显式 `timeout: 30_000` |
| 「host.conf 与 web.conf 并存」的直连部署 | `deploy/nginx/host.conf`（宿主机直连版）已声明 5m，无此缺陷 | 无需改动（门禁覆盖） |

修复动作：两份 web nginx 配置补 `client_max_body_size 5m`；前端上传请求显式 `timeout: 30_000`；新增跨运行时静态门禁 `UploadPathContractTest`（5 条断言：含 `/api/` 反代的配置必须显式声明体积上限；容器内层 web ≥ 外层 edge 与 ip-test 覆盖层；生产各层 ≥ 应用 multipart 上限；镜像内 `web/nginx.conf` 与 `deploy/nginx/web.conf` 取值一致；前端上传超时 > 服务端解析预算）。`docs/05` §13 与 `docs/08` 补代理层体积契约。

## 3. 已闭环（不再重复提报）

- 旧诊断 O-01~O-14 全部闭环（ideation 自带表格 + 本次复核一致）
- findings 已闭环：#6（JWT 每请求校验 ACTIVE，删号即失效）、#10（同意类别单点注册表）、#27（接管阈值 600s）、#34（删号连带取消 AI/PDF 任务）、#35（快照 PII 统一脱敏）、#39（占位符正则统一）、#40（轮询窗口统一 300×2s）、#42（状态含 mode/language）、#56（标签响应式）、#57（移除 EXPORT_PDF）、#69（JD 变更清空解析）、#76（成就引导异步契约）、#77（入口强制幂等键）、#84（JD envelope 兼容）、#90（profile objective 映射）、#92（health/detail 能力清单）、#93（能力注册 fail-closed）、#94（来源展示映射）、#95（空章节 sentinel 清理）、#96（导航注册表）、#97（导出状态兜底）、#98（面试来源校验）、#99（NOT_REQUIRED 归一）、#100（版本指针行锁）、#101（拒绝数组索引漂移修复）
- 部分闭环：#15（生产密钥校验已加）、#17（max-bytes 已声明）、#20（readiness 已验 Chromium）、#49（部分入口对齐）、#59（超时回显 taskId）、#66（基础设施已建）、#91（权重校验已加）

## 4. 推荐下一批（按 收益/成本 排序）

**第一批 · 隐私与配置对齐（低成本，1 个提交收口）— ✅ 已执行（2026-09-30）**
1. ✅ #68 移除日志 userId（`ExportService.java:87`）+ 新增 `LogPrivacyGateTest` 静态门禁（扫描全部日志调用，禁止 userId/user_id/email/phone，防复发）
2. ✅ #71 consent 最新事件排序加 `id` tie-break（`AiConsentRepository` 方法改 `...CreatedAtDescIdDesc`，service 与测试同步）
3. ✅ #60 worker 租约 180s → **660s**（须 > 单任务最坏执行时长＝链总预算 600s + 余量，否则心跳失联期间旧 worker 仍可能被接管重跑、重复调用 provider）；心跳池由单线程升为 2 线程（防单次 renew 卡顿连锁拖慢其它任务续租）
4. ✅ #49 幂等键契约统一：`AiTaskController` / `JobMaterialSelectionController`（可选键，≤128 + trim + 超长拒绝）、`CommunicationController`（必填 + ≤128 + trim）、`InterviewController`（保持 64＝存储列宽，三端点补 trim）

**第二批 · 并发健壮性（中成本）— ✅ 已执行（2026-09-30）**
5. ✅ #46 唯一键竞态统一映射 409：新增 `DataIntegrityViolationException` 与 `ConcurrencyFailureException` 全局 handler（40901，不记录异常 message 防用户数据入日志）
6. ✅ #45 refresh 轮换原子化：`AuthSessionRepository.revokeIfActive` 原子条件更新（CAS）——并发刷新只有一个赢家，败者走复用检测撤族并 401；**刻意不用行锁**（行锁与 `REQUIRES_NEW` 撤销服务组合会自锁：过期分支需更新被本事务锁定的行）
7. ✅ #67 职业资料乐观锁：`CareerMaterial` 加 `@Version` + V27 迁移（`version BIGINT NOT NULL DEFAULT 0`），并发编辑由「最后写入覆盖」变为显式 40901
8. ✅ #48 AI 任务幂等并发回读：`create` 去外层事务 + `saveAndFlush` 捕获唯一键冲突 → 回读赢家（同指纹返回同一任务/不同指纹 409/非本竞态原样上抛）
9. ✅ 测试：`AuthConcurrencyIT`（并发注册恰好一个 201+一个 409；并发 refresh 恰好一个签发者+一个 401+族内无双活）；`AiTaskServiceTest` 并发回读三分支；`CareerMaterialControllerIT` 陈旧保存乐观锁用例；`AuthServiceTest` CAS 双分支

**第三批 · 功能与性能（中成本）— ✅ 已执行（2026-09-30）**
10. ✅ #2 搜索覆盖 contentJson：`CareerMaterial` 增加只读 `@Formula("content_json")` 文本投影（不参与写入与 schema 校验；避免第二个 `@Column` 映射触发 `ddl-auto=validate` 对 String↔JSON 的类型比对），`CareerMaterialRepository.search` 增加 `lower(contentJsonText) LIKE` 子句。匹配策略实测定案：依赖 JSON→字符串隐式转换（`lower(json_col)`），H2 2.2.224 与 MySQL 5.7.24 双端探针均可用；`CAST(... AS CHARACTER VARYING)` 仅 H2 可用、`CAST(... AS CHAR)` 仅 MySQL 可用，故不采用 CAST。新增 IT 用例：命中词只在 contentJson（标题/原文均不含）→ 精确命中、小写查询大小写不敏感、跨用户不串号
11. ✅ PA-2 JWT 用户状态短 TTL 缓存：新增 `ActiveUserCache`（`app.jwt.active-user-cache-ttl-seconds` 默认 30s；负结果同样缓存；容量兜底惰性清理），`JwtAuthenticationFilter` 改走缓存。删号路径（`AuthService.deleteAccount`）在**事务提交后** evict——提交前清除存在「并发请求回读未提交的 ACTIVE 并重新缓存」竞态，会让 #6 的失效延迟一个 TTL。取舍口径：非删号路径（直改库/多实例）的状态变更最迟 TTL 后生效
12. ✅ #66 错误码映射收尾：6 个视图 13 处服务端 message 直透全部改为 `resolveApiError`（GenerationWorkbench×2、GenerationConfirm×4、MaterialSelectionConfirm×4、MaterialResumeGeneration×1、Account 凭证变更×1、ResumeEditor 保存×1）；`web/src` 已无 `data?.message` 直透残留（grep 复核）

**第四批 · 小项收口（低成本，1 个提交）— ✅ 已执行（2026-09-30）**
13. ✅ #75 面试记录排序确定性：仓储排序由 `created_at` 改为 `round_no ASC, id ASC`（V18 的 `uq_interview_record_session_round` 保证轮次唯一；`created_at` 同毫秒时顺序无契约）。同时删除未被调用的 `findBySessionIdInOrderByCreatedAtAsc`；新增 `InterviewRecordOrderingIT`（故意倒序写入验证按轮次返回）
14. ✅ #72 搜索词服务端长度上限：`CareerMaterialService.normalizeQuery` 超 100 字符拒绝（40001），避免长词进入 title/sourceText/contentJson 的无索引包含扫描
15. ✅ #43 限流分桶硬上限：`RateLimitFilter` 在 maxBuckets 耗尽且无可清理过期桶时，新 key 直接 429（fail-closed），已有分桶不受影响；新增单测
16. ✅ #21 发布就绪脚本退出码：`Test-ReleaseReadiness.ps1` 的 `npm run check; npm test` 拆为两条 `Invoke-CheckedCommand`，前一步失败不再被末位退出码掩盖

**第五批 A · 读模型与性能（部分完成）— ✅ 已执行（2026-09-30）**
17. ✅ #1 职业资料列表读模型：新增 `CareerMaterialListRow` 投影查询（摘要列 + contentJson + SQL 侧「原文非空」标志），列表不再传输 MEDIUMTEXT sourceText，类型过滤下推 SQL；`evidenceReady` 仍由 `CareerMaterialEvidence` 规则计算（SQL 近似只影响历史直写行的空白字符边界，已在 DTO 注释记录）
18. ✅ #52 续办列表 metadata-only：`AiTaskContinuationResponse` + `ContinuationProjection`，不再返回 resultJson/errorMessage/inputSnapshot 派生字段（结果 JSON 单任务可达 64KB）；Web `AiTaskContinuation` 类型同步（HomeView 仅消费 id/类型/状态/时间）
19. ✅ #50 面试历史列表读模型：`InterviewSessionRepository.SessionSummaryProjection`，不再加载 external_resume_text（MEDIUMTEXT）与 current_question；新增 `InterviewSessionSummaryProjectionIT`
20. ✅ #3 投递统计：计数改 SQL group by（`countGroupByStatus`），时长行只取 APPLIED/INTERVIEWING/OFFERED 三态，不再为统计读回全部投递行
21. ⏭ #50 其余三项**暂缓**（JD 预览、简历版本 templateCode、投递列表长文本）：其摘要字段从宽列派生（JD 预览需 `\s+` 归一化、templateCode 取自 resumeJson），SQL 无跨库等价表达；投递列表的长文本被前端编辑面板直接消费。需要「持久化派生列」或前端改为按需拉详情，属下一次设计决策——不做会牺牲语义精确性的近似实现

**第五批 B · 并发一致性与 AI 韧性 — ✅ 已执行（2026-09-30）**
22. ✅ #24 面试资产幂等并发：服务端先对面试记录行加锁（`findOwnedForUpdate`）串行化同一记录的并发创建，V28 加唯一索引 `uq_interview_asset_user_record`（先核对本地库 0 组重复，避免迁移失败）兜底；新增 `InterviewAssetConcurrencyIT`（双线程起跑 → 只落一条、两请求同 id）
23. ✅ #73 沟通模板乐观锁：`CommunicationTemplate` 加 `@Version` + V29 迁移；并发内容更新由「最后写入覆盖」变为 40901（`CommunicationControllerIT` 新增陈旧副本断言）
24. ✅ #25 模板使用计数原子化（与 #73 联动，避免计数自增触发伪冲突）：`incrementUsageCount` 原子 UPDATE，实体加 `@DynamicUpdate` 防止内容更新把计数按旧值写回；IT 断言两次引用后 `usage_count = 2`
25. ✅ #85 ATS prompt/schema 版本单一来源：`AtsAiPromptBuilder` 暴露实际使用版本，`AtsAiAnalysisService` 结果与 `AtsService`（规则回退/任务快照）统一取自构建器——此前两个 bean 的默认值不同（v1.0.0 vs v1.0.1）导致记录版本漂移；新增判别性单测（快照带过期版本仍记录构建器版本）
26. ✅ #89 INTERVIEW_COACH 未知 operation 显式失败：不再静默落 `executeDefault`（避免按任意 input 直调模型）；错误信息不回显 operation 取值；新增 worker 路由单测
27. ✅ #70 个人资料首次 upsert 并发：先锁用户行串行化「查后插」（唯一键冲突仍由全局 handler 兜底 40901）

**第五批 C · 前端竞态保护 — ✅ 已执行（2026-09-30）**
28. ✅ #58 简历/JD 选择器请求世代：`useResumeJobOptions` 的 `load`/`loadVersions` 加 epoch，连续切换时旧响应不再覆盖新选择；版本列表在请求发起时快照 resumeId
29. ✅ #63 版本对比去重 + 陈旧保护：`loadDiffs` 加 epoch；删除 `watch`/`onMounted`/`@change`/`switchSides` 的重复触发路径——一次版本选择只发一次 diff 请求（e2e 断言：初始每版本恰好 1 次、切回后恰好 2 次，且延迟返回的旧 diff 不覆盖新选择）
30. ✅ #64 模板列表/预览世代：场景筛选快速切换与连续点开预览时，慢响应不再覆盖最新列表/最新预览；关闭预览使在途请求失效
31. ✅ #65 章节关联资产世代：`ResumeEditorView` 与 `ResumeDetailView` 中旧章节的响应不再写入新章节的关联资产（含素材标题映射）
32. ✅ #61 会话初始化断网重试：`initialize` 失败后可再次调用（在途 Promise 合并并发调用，避免并发 refresh 触发服务端复用检测撤族），页头新增网络恢复重试横幅（zh/en 文案 + 样式）；新增 e2e「断网 → 横幅出现 → 页内重试恢复会话、不整页刷新」

**第五批 D · 测试补齐与门禁更新 — ✅ 已执行（2026-09-30）**
33. ✅ TC-5 generic 白名单拒绝分支 IT：`AiTaskControllerIT` 新增用例——领域专用类型（`JOB_GENERATION` / `COMMUNICATION_GENERATE`）走通用 `/api/ai/tasks` → 40001，且请求不落库
34. ✅ TC-6 confirm 乐观锁 IT 断言：`ConfirmationControllerIT` 新增用例——过期 30s 的 `taskUpdatedAt` → 409 + 40901；改用最新时间戳重试成功（证明拒绝原因只是乐观锁、不是任务状态）
35. ✅ TC-7 AI 模式回答重放 IT：`InterviewControllerIT` 新增用例——同键同答重放命中既有 attempt（不新增评估尝试、不产生新一轮），同键不同答 → 409 + 40901
36. ✅ TC-4 ATS 降级文案映射：7 个 `AtsFallbackCode` 在 `AtsCheckView` 走 i18n 映射（此前直接渲染服务端中文 `fallback.message`，en-US 界面会显示中文），未识别码回退通用文案；e2e 断言映射文案出现且服务端 message 不透传
37. ✅ #4 证据边界 e2e：资料库 `evidenceReady=false` 行显示「缺少证据」标注；选材步骤禁用无证据资料并给出「无可用资料」提示（新增 2 个 e2e）
38. ✅ #22 MySQL 5.7 迁移门禁更新：`MySql57MigrationLiveIT` 从「V19→V22」更新为「V19→当前」（V20~V29 共 10 条迁移 + V23~V29 结构/种子断言）；用本机 MySQL 5.7.24 实跑通过（`scripts/Invoke-MySql57MigrationGate.ps1`，临时 schema 自动清理）

**第六批 · 资源边界与交付链 — ✅ 已执行（2026-09-30）**
39. ✅ #44 通用 AI 任务 input 大小上限：`/api/ai/tasks` 按序列化字节数拒绝超限 input（默认 262144，与简历 JSON 校验上限一致，可配 `app.ai.max-input-json-bytes`），不截断；新增 IT（300KB input → 40001 且不落库）
40. ✅ #17 导入解析资源边界：PDF 页数上限（默认 60）、抽取文本字符上限（默认 200000）、单次解析耗时上限（默认 15s，超时中断并按业务错误返回）——入口 5MB 之外的第二道闸；新增 3 个单测（页数 / 长度 / 超时）
41. ✅ #29 沟通 AI 草稿与任务结果同事务落库：`CommunicationAiService` 只组装 `PendingDraft`，落库改由 `TaskLeaseService.releaseSuccess` 的租约校验后同事务回调（`persistDraft` 并把 draftId 写回结果）；租约被接管时结果与草稿一起不落库（此前「先落库后释放」会留孤儿草稿）
42. ✅ #30 PDF 导出孤儿文件清理：worker 在 `releaseSuccess` 返回 stale 时立即删除刚写入的渲染文件（此前无人引用，过期清理作业也只看得见 SUCCESS 行的文件）；新增 2 个单测（stale 清理 / 持有租约保留）
43. ✅ #54 导出过期判定条件化：`get`/`download` 只在 `expireIfDue` 真正落地 EXPIRED 时才把视图标为过期；行被并发推进（例如过期前重试排队）时回读最新状态，不再把新状态误报为已过期；新增单测
44. ✅ #14 PDF 导出结果复用：同一（用户, 版本, 模板）已有未过期的在途/成功任务时直接复用（PDF 内容由版本+模板唯一决定），过期成功任务才重新排队；新增 3 个单测 + 1 个 IT（重复提交不新增行）

**第七批 · 并发与幂等续 — ✅ 已执行（2026-09-30）**
45. ✅ #38 简历乐观锁：`Resume` 加 `@Version` + V30 迁移——标题/软删等路径保存陈旧实体时不再把并发推进的 `current_version_id` 静默写回（冲突经全局处理器映射 40901）；新增 IT（陈旧副本保存被拒且指针未回退）
46. ✅ #78 规则评分重放幂等：`POST /api/scoring/match` 在「同一（版本, JD, 规则版本）且 JD 在评分后未被修改」时复用既有结果；JD 修改（含重新解析）后照常重算——不再重复追加等价行；新增 2 个单测 + 1 个 IT
47. ✅ #28 配额/跟进查询组合索引：V31 新增 `idx_ai_task_user_type_created`（AI 每日配额）、`idx_iai_user_created`（面试每日配额）、`idx_application_user_followup`（跟进筛选）；`resume(user_id, job_description_id)` 经核对已由 V12 的 `idx_resume_user_jd` 覆盖。**首版 V31 重复建了 `idx_resume_user_jd`，被更新后的 5.7 门禁当场拦下（Error 1061）并修正——门禁有效性的实证**

**第八批 · AI 输入边界与前端请求收敛 — ✅ 已执行（2026-09-30）**
48. ✅ #36 资料文本统一 prompt 字节预算：新增 `MaterialPromptTextBudget`，单条额度 = min(`app.ai.prompt.max-material-bytes` 65536, `app.ai.prompt.max-materials-total-bytes` 262144 / 条数)——因此 n 条资料的原始文本总量恒不超总量上限；「资料选择（≤60 候选）」与「岗位定制生成」两条链路共用同一预算（此前 sourceText 无准入上限、只有条数限制）。**裁剪而不是丢弃**：materialId 恒保留在提示词中，不新增需前端映射的 unselectedReasons 原因码（`DraftSectionReview.vue` 按原样展示该字段）；sourceText 按 UTF-8 字符边界截断并追加 `[truncated]` 标记；contentJson 超限按顶层条目整条保留/省略并置 `_truncated`，序列化仍是合法 JSON（模型据此走 `_pending` 而不是编造）。新增 9 个用例（预算单测 7 + 生成构建器 1 + 选择构建器 1）
49. ✅ #33 投递编辑页版本定位去扇出：`GET /api/resume-versions/{id}` 详情新增 `resumeId`（服务端本就以该字段做归属校验），`ApplicationsView` 的「版本 → 所属简历」定位由「按简历数并行扇出各简历版本列表」改为单请求 + 仅重载目标简历的版本列表；新增服务端断言 + e2e（断言单次 lookup、编辑期间仅 1 次版本列表请求、最终选中被引用的旧版本）

**第九批 · 版本列表读模型（#50 收尾之一）— ✅ 已执行（2026-09-30）**
50. ✅ #50 版本历史列表改摘要投影 + `template_code` 派生列：此前列表为每一行加载 `resume_json`（单版本上限 256KB）才能派生 `templateCode`，并把 `generationContext` 一并返回（列表消费端不使用，编辑器只从版本详情读）。现改为 `ResumeVersionRepository.VersionSummaryProjection` 元数据投影（不加载 `resume_json` / `generation_context`）；`templateCode` 存 V32 派生列，写入时经 `ResumeTemplateCodes.normalize` 白名单归一化。**历史行不在 SQL 迁移中回填**（归一化白名单属应用层语义，SQL 复刻会与运行期结果漂移，如大小写/未知取值），改由列表读路径惰性回填（幂等，仅首次读到旧行时执行）——`ResumeVersionSummaryProjectionIT` 以「写入即派生 → 列置 NULL 模拟历史行 → 列表返回派生值且列已写回」实证；归纳档列表投影路径同样覆盖
51. ⏭ #50 剩余项的处置口径：**JD 预览**读侧受 `jdText ≤ 5000 字符`准入上限约束（无 MEDIUMTEXT 级长字段问题），持久化派生列收益低且同样需要应用层归一化回填，暂不实现；**投递列表长文本**见第十批（已执行）

**第十批 · 投递列表读模型（#50 收尾之二）— ✅ 已执行（2026-09-30）**
52. ✅ #50 投递列表改摘要投影 + 按需详情：`GET /api/applications` 不再返回草稿长文本（`coverLetterText` / `emailBodyText` / `openingMessageText`），改用 `draftCount` 支撑卡片「n/3」标记；新增 `GET /api/applications/{id}`（归属校验）供**展开卡片与打开编辑面板时按需拉取**（展开命中前端缓存；编辑面板始终拉最新以防陈旧）。`feedbackText` 刻意保留在摘要中——状态迁移接口按请求值覆盖备注，省略会把已有备注清空（拖拽改状态必须携带现值）；口径写入 `docs/05` §9.2。新增 IT 断言（列表无草稿字段 + `draftCount=1`、详情返回全文、跨用户 404）与 e2e（列表 0 次详情请求 → 展开 1 次并渲染 → 收起再展开命中缓存 → 编辑再拉 1 次回填）

**第十一批 · 投递状态更新的备注语义 — ✅ 已执行（2026-09-30）**
53. ✅ 备注「未发送即保留」（本批全量回归前的自查发现）：`PATCH /api/applications/{id}/status` 此前按请求值无条件覆盖 `feedbackText`（缺席/null 即清空），任何不关心备注的调用（拖拽改状态、第三方客户端）都会静默清空备注；现改为**缺席/null = 不改动，显式空串 = 清空**（空白归一为 null），前端不再回传陈旧值、也不预填草稿（仅发送用户实际编辑过的文本）。同时验证「无任何变更的 PATCH 不写库、乐观锁版本不推进」。`docs/05` §9.2 契约同步；IT 覆盖「发送即更新 / 缺席即保留 / 空串清空」三分支；e2e 断言改状态请求不再携带 `feedbackText`
54. ✅ #11（分页契约）**决策：记录不实现**（2026-09-30 与用户确认）：全部列表已在 #50 三批中改为摘要投影，响应不再携带长字段；数据为用户维度（简历/JD/版本/投递/面试会话），量级可控。分页需要为选择器类消费方补轻量 options 端点、迁移 31 项功能测试与多处列表 UI（含按状态分列的投递看板），收益/成本不划算。保留为待观察项：若出现单用户数据量显著增长的真实案例，再按「只给纯列表页加分页 + options 端点」方案实施

**第十二批 · AI 上下文白名单统一（#53/#54/#55，用户确认「三处统一扩展」）— ✅ 已执行（2026-09-30）**
55. ✅ #53/#54/#55 章节白名单统一为全章节：新增共享常量 `ResumeSections.AI_CONTEXT_SECTIONS`（13 章，显式排除 `links` 联系方式容器；有序列表保证提示词确定性），三处改为同一来源——① 素材生成（`PromptTemplates` 的 MATERIAL_IMPORT 提示词由常量 `.formatted(String.join(...))` 生成 + 前端 `materialGeneration.ts` 的白名单改从 `sectionRegistry` 派生 `AI_CONTEXT_SECTION_KEYS`，两端注释互指对齐）；② 面试上下文投影（`InterviewContextSanitizer` 新增 objective（只取 targetRole/targetIndustry/summary，`location` 属联系方式）、volunteering、courses、publications、awards、customSections（两层结构：章节标题 + entries 字段白名单），并抽出 `appendItems` 复用渲染）；③ 沟通 prompt（`CommunicationAiPromptBuilder` 8 章 → 全 13 章，`SENSITIVE_KEYS` 与脱敏/截断策略不变）。新增 `CommunicationAiPromptBuilderTest`（提示词含新章节、`links` 与邮箱/URL/`location` 被剔除、常量契约）+ 面试 sanitizer 扩展用例（7 个新字段断言 + `objective.location` 剔除）

**第十三批 · AI 任务留存清理（#26，用户确认「90 天压缩快照 + 用户入口」）— ✅ 已执行（2026-09-30）**
56. ✅ #26 `ai_task` 留存与清理：新增 `AiTaskRetentionService`（`@Scheduled` 默认每 24h、每轮 200 行）把超期（`app.ai.task.retention-days`，默认 90 天）的**终态**任务压缩为元数据——内联快照替换为 `{"_purged": true}`、结果 JSON 置空，保留 id/类型/状态/时间/幂等键等行数据；待确认（`SUCCESS + PENDING`）与进行中的任务不压缩。V33 新增 `snapshot_purged` 标记列（NOT NULL DEFAULT FALSE）保证压缩一次性、清理作业不再重复命中（占位 JSON 无 SQL 可判定特征），5.7 门禁同步到 V33（14 条迁移）。用户侧新增 `DELETE /api/ai/tasks/history`（只删本人终态且非待确认任务，返回删除条数）+ 账号页「清空 AI 任务历史」按钮（`window.confirm` 二次确认 + 结果提示）。测试：`AiTaskRetentionIT`（超期终态压缩 / 待确认 / 未超期 / 进行中 / 已压缩 5 类行 + 二次执行返回 0 证明一次性）、`AiTaskControllerIT` 新增清空历史用例（终态删除、待确认与进行中保留、跨用户隔离）、e2e 账号页清空流程；`docs/05` §7.5 留存契约同步

**第十四批 · 账号数据导出与删号入口（#7，用户确认口径）— ✅ 已执行（2026-09-30）**
57. ✅ #7 账号数据导出与删号入口：新增 `GET /api/auth/export` 把本人各域数据聚合为可下载 JSON（`formatVersion: 1`、`Content-Disposition` 附件、显式 UTF-8；定向测试暴露并修正「String 消息转换器默认 ISO-8859-1 会把中文写成 `?`」）；范围＝简历+版本（含归档）/职业资料/JD/投递/面试会话+轮次/答案资产+章节/自定义沟通模板+草稿/个人资料/AI 同意记录，显式排除 AI 任务内联快照与结果及其派生 ATS/匹配分析、登录会话凭据（`User` 白名单取值）；账号页新增「数据与隐私」带（导出下载 + 删号对话框：输入用户名二次确认 → 现有 `DELETE /api/auth/me` → 清本地会话跳登录）。顺带：`CommunicationDraft` 补标准 getter（原仅 setter，无法序列化）、`CareerMaterial.contentJsonText` 加 `@JsonIgnore`（搜索用内部投影不外泄）。`docs/05` §2.7/§2.8 契约同步

**第十五批 · 异步失败消息边界与观测/消费口径（#514/#524/#533，台账对账新增项）— ✅ 已执行（2026-09-30）**
58. ✅ #514 异步失败消息持久化边界：AI 任务/面试 AI 尝试的 `error_message`（`VARCHAR(1024)`）此前原样写入 provider/异常 message，超长消息在 MySQL 严格模式下会让「释放失败」事务失败——任务停在 RUNNING（面试尝试停在进行中）直到租约过期被接管，真实失败原因被掩盖、重试计数与告警口径漂移。新增 `AsyncFailureMessages.persisted()`（截断 1000 < 列宽 1024、不切断代理对、null 保持清空语义）并接入 6 处写入路径（`TaskLeaseService`、面试 `markAttemptFailed` 与 3 处配额分支）；PDF 导出任务原有内联截断收敛到同一实现。测试：`AsyncFailureMessagesTest`（null/边界 1000/超长/代理对不切断）+ `TaskLeaseServiceTest` 超长消息用例（断言 5000 → 1000）。**未做**：失败消息「公开文案接缝」（对客户端只暴露稳定文案）仍属架构方向，留观
59. ✅ #524 AI 配额观测口径：gauge `resume_ai_quota_daily_tasks_created` 用全站**任务行数**（`countByTaskTypeAndCreatedAtAfter`），与限流按「每用户当日尝试数（重试计次）」的口径不可比（面板同时并列「每用户限额」gauge，易读成使用率）。现改为 `resume_ai_quota_daily_attempts{scope="all_users"}`（新增全局尝试数查询，与限流同一单位）；Grafana 面板表达式与标题同步；`AppObservabilityTest` 断言新口径、旧指标不再注册、重复注册幂等；删除已无调用方的旧查询
60. ✅ #533 归档版本消费契约（评分对齐）：`ScoringService.score` 直接用 `findById` 加载版本、不校验 `deletedAt`，而 ATS/导出/投递/沟通均拒绝归档版本——同一版本在不同模块「能不能消费」结论不一致。现按 ATS 既有语义返回 40901「该简历版本已归档，请先恢复后再发起评分」（归属校验在前，避免用归档状态区分他人版本是否存在）；`ScoringControllerIT` 新增「归档 40901 → 恢复后 200」用例

**第十六批 · PDF 服务容量/队列/关闭语义（台账行「PDF readiness 与容量」剩余部分）— ✅ 已执行（2026-09-30）**
61. ✅ PDF 渲染容量与关闭语义：`createBrowserPool` 增加并发许可（`maxConcurrentPages` 默认 4）、FIFO 等待队列（`maxQueueSize` 默认 16，0 = 不排队）、`stats()/beginDrain()/waitForIdle()`；队满或 drain 中立即返回**可重试的 503**（此前每次 `newPage()` 无任何上限，渲染请求可无限堆积）。`server.js`：容量/drain 超时/并发数走环境变量并在非法取值时启动失败；`/health` 暴露 `capacity` 快照与 `render-capacity` 检查（饱和不改整体 status，避免瞬态抖动）；`/render` 503 附带 `Retry-After` 与 `code 50301`；关闭流程改为「先 drain 拒新 → 等 in-flight（上限 `PDF_SERVICE_DRAIN_TIMEOUT_MS` 默认 10s）→ 关浏览器 → 关监听」。`deploy/docker-compose.prod.yml` 补 `stop_grace_period: 30s`（必须大于 drain 超时，否则 docker 会在渲染中途 SIGKILL）；`pdf-service/README.md` 契约与配置表同步。测试：`browser-pool.test.js` 新增并发上限 + FIFO 唤醒、队满 503、drain 拒新/等 in-flight/report idle；`production-config.test.js` 新增非法容量配置启动失败
62. ✅ API 侧 503 可重试契约：新增 `PdfFailureCategory.OVERLOADED`（503 + 容量/繁忙文案映射），`PdfServiceClient` 对 503 返回可读可重试文案「PDF 服务繁忙，请稍后重试」（其余非 2xx 仍为「PDF 渲染失败」），导出任务失败原因不再与真实渲染错误混淆；`FailureCategoryClassifierTest` 补 503/中文文案/busy 三例

**第十七批 · 观测查询索引（迁移 V34，EXPLAIN 取证驱动）— ✅ 已执行（2026-09-30）**
63. ✅ 全站配额观测计数索引：用本机 MySQL 5.7.24 + 20 万行 ai_task 一次性探针取证（用完即删）——`resume_ai_quota_daily_attempts` 的查询形状（`task_type = ? AND created_at > ?`）此前为**全表扫描**（`EXPLAIN type=ALL rows≈199191`，实测 62~66ms/次；Prometheus 每次抓取都会对每个任务类型各执行一次），补 `ai_task(task_type, created_at)` 后同一探针变为 `range + Using index condition`（**0.22~0.29ms**，约 250×）。同一探针同时证明：worker 领取查询仍走 `PRIMARY`（主键序 + `LIMIT` 早停，无 filesort、无计划回退），留存清理查询同样走 PRIMARY（200 行候选约 2.2ms），故**不为**这两条加索引（台账行「Worker 领取与索引」由此从「无实测证据」升级为证据化结论）。V34 迁移（V20~V34 共 15 条）经 `Invoke-MySql57MigrationGate.ps1` 在 MySQL 5.7 实跑通过（含新索引的列数断言）

**剩余状态收口（2026-09-30，第十四~十七批完成后）**
- §2 可实现项已全部处理完毕：第一~十七批共 63 条全部执行（含台账对账新增的 #514/#524/#533 与 V34 索引）；**当前无待实现项**。
- 仍为待决策 / 留观（均非当前缺陷，且有取证或产品口径依据）：
  1. **#22 MySQL 5.7 门禁去留**（是否有常驻 5.7 环境 / 是否接入 CI）——待你决策；门禁本身已推进到 V34，本机可通过 `scripts/Invoke-MySql57MigrationGate.ps1` 实跑。
  2. **架构方向类**（§2.10 与 §2.11 归并）：投递状态机 / 模板 / 沟通 / 导入 / 模式 / 资料类型多 runtime 登记、ATS/面试 schema 重复维护、全量类型化配置、跨运行时时间契约、AI 任务恢复收件箱、导入来源追溯、PDF 对象存储、AI 提供者路由、投递流水线契约——当前规模属过度工程边界，留待真实需求。
  3. **两项证据化留观**：a) JD 解析平铺 `contains` 的误命中（有真实案例再评估）；b) DOCX 展开量上限（POI 防护 + 5MB 入口 + 15s 超时已覆盖）。
  4. **失败消息「公开文案接缝」**（对客户端只暴露稳定文案，而非 provider 原文）——需要产品文案层，未做。
- 持续留观：`web/e2e/ats-ai.spec.ts` 在第十三批出现过 1 次偶发失败；`web/e2e/applications-edit.spec.ts`（「编辑投递时只发 1 次版本列表请求」）在第二十四批 CI 首跑出现 1 次偶发失败（期望 1 实际 2）——同 commit 重跑即通过（workflow run 36755781909 重跑 success），判为前端时序偶发，两处均留观（再次复现则按竞态排查）。
- **CI runner 迁移预检（2026-10-01）**：GitHub 公告 `ubuntu-latest` 将于 **10/19–11/19 渐进迁移到 Ubuntu 26.04**（默认 JDK 17→25、Node 22→24、MySQL 8.0→8.4，并移除若干工具；官方建议先在 `ubuntu-26.04` 上显式验证）。已用临时探针分支（`workflow_dispatch` 显式触发，**验证后已删除**）把 5 个 job 全部切到 `ubuntu-26.04` 实跑：**CI 与功能回归双绿**（server 测试 / web 构建 + Playwright Chromium / MySQL 8.0 容器 / CJK 字体 apt / pdf-service Puppeteer 渲染全部通过）——结论：**本次迁移对本仓无破坏，无需 pin 到 24.04**；顺带把 `actions/setup-python` 由 v5 升到 v7（v5 基于已被 GitHub 移除的 Node 20，运行时被强制替换并产生弃用告警）。

**需产品/环境决策后再定（2026-09-30 口径已确认）**
- #26 ai_task 留存与清理：✅ 已按「90 天压缩快照 + 用户入口」落地（见第十三批，第 56 条）
- #7 账号数据导出/删除入口：✅ 已按确认口径落地（见第十四批，第 57 条）
- #53/#54/#55 AI 上下文白名单：✅ 已按「三处统一扩展」落地（见第十二批，第 55 条）
- #22 MySQL 5.7 门禁：门禁本身已更新到当前迁移版本（V34）并用本机 5.7.24 实跑通过（见 §4 第 38/47/63 条）；**长期是否保留该门禁**（是否有常驻 5.7 环境 / 是否接入 CI）仍待决策——当前仅本地手动执行（用户本次未选择推进）

## 5. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-09-30 | 初版：4 个并行 agent 分区间核对 101 条 finding + Ranked Ideas + 新增核对表；人工抽查 5 处关键证据；产出「仍存在」聚类清单与三批推荐 |
| 2026-09-30 | **第一批（隐私与配置对齐）执行完成**：#68 日志 userId 移除 + `LogPrivacyGateTest` 静态门禁（扫描全部日志调用，防复发）；#71 consent 排序加 `id` tie-break；#60 租约 180→660s（> 链总预算 600s）+ 心跳池 2 线程；#49 四处入口幂等键契约统一（AiTask/JobMaterialSelection/Communication/Interview）。回归：全量 **740 测试 0 失败**（含新门禁），新增幂等键契约 IT 用例定向通过 |
| 2026-09-30 | **第二批（并发健壮性）执行完成**：#46 完整性/并发冲突统一 409（两个全局 handler，日志不记异常 message）；#45 refresh 轮换 CAS 原子化（不用行锁——避免与 REQUIRES_NEW 撤销服务自锁），并发刷新单赢家 + 败者撤族 401；#67 职业资料 `@Version` + V27 迁移；#48 AI 任务幂等并发回读（三分支）。新增 `AuthConcurrencyIT`（真并发双场景）与 5 个分支用例，回归：全量 **748 测试 0 失败**；CI + Functional Regression 双绿 |
| 2026-09-30 | **第三批（功能与性能）执行完成**：#2 搜索覆盖 contentJson（`@Formula` 只读文本投影 + `lower(contentJsonText) LIKE`；H2 2.2.224 与 MySQL 5.7.24 双端探针定案匹配策略；新增 IT 用例）；PA-2 `ActiveUserCache` 30s 短 TTL（删号路径事务提交后清除，保住 #6「删号即失效」；新增 4 个可变时钟单测 + AuthServiceTest 断言）；#66 六个视图 13 处错误码映射收尾（web build 通过）。回归：全量 **753 测试 0 失败**（新增 5），web `npm run build` 通过 |
| 2026-09-30 | **第四批（小项收口）执行完成**：#75 面试记录排序改 `round_no ASC, id ASC`（新增 `InterviewRecordOrderingIT` 倒序写入断言 + 评分投影 JPQL 执行验证；删除未调用的旧排序方法）；#72 搜索词 100 字符上限（40001）；#43 限流分桶硬上限（容量耗尽新 key fail-closed 429 + 单测）；#21 发布就绪脚本复合命令拆分（仓库内已无其它复合写法）。回归：全量 **756 测试 0 失败**（新增 3）；CI + Functional Regression 双绿 |
| 2026-09-30 | **第五批 A（读模型与性能）部分执行完成**：#1 职业资料列表投影（新增 `CareerMaterialListRow`，不读 MEDIUMTEXT 原文、类型过滤下推 SQL）；#52 续办列表 metadata-only（`AiTaskContinuationResponse` + 投影，去掉 resultJson/输入快照派生字段）；#50 面试历史列表投影（不读 external_resume_text/current_question，新增 `InterviewSessionSummaryProjectionIT`）；#3 投递统计计数改 SQL group by、时长行只取三态。#50 其余三项暂缓（见 §4 第 21 条）。回归：全量 **758 测试 0 失败**（新增 2），web `npm run build` 通过；CI + Functional Regression 双绿 |
| 2026-09-30 | **第五批 B（并发一致性与 AI 韧性）执行完成**：#24 面试资产并发幂等（记录行锁 + V28 唯一索引 + `InterviewAssetConcurrencyIT` 双线程断言）；#73 沟通模板 `@Version` + V29 迁移（陈旧副本保存被拒）；#25 使用计数原子自增 + `@DynamicUpdate`（与 #73 联动，防计数自增触发伪冲突）；#85 ATS prompt/schema 版本单一来源（统一取自 prompt builder，消除 v1.0.0/v1.0.1 默认值漂移）；#89 未知 INTERVIEW_COACH operation 显式失败（不再静默落通用路径）；#70 个人资料 upsert 加用户行锁。回归：全量 **762 测试 0 失败**（新增 4）；CI + Functional Regression 双绿 |
| 2026-09-30 | **第五批 C（前端竞态保护）执行完成**：#58 选择器请求世代；#63 版本对比去重（一次选择一次请求）+ 陈旧 diff 保护；#64 模板列表/预览世代；#65 章节关联资产世代（编辑器 + 详情页）；#61 会话初始化断网后页内重试（在途 Promise 合并并发 + 页头横幅）。回归：web `npm run build`（i18n/draft-fields 门禁 + vue-tsc）通过；Playwright 全量 **139 passed / 6 skipped / 0 failed**（新增 2 个回归用例）；CI 绿 |
| 2026-09-30 | **第五批 D（测试补齐与门禁更新）执行完成**：TC-5 通用端点白名单拒绝 IT（40001 + 不落库）；TC-6 confirm 乐观锁 IT（过期时间戳 40901、刷新后同一请求成功）；TC-7 AI 模式回答重放 IT（同键不新增 attempt、异答 40901）；TC-4 ATS 降级 7 码前端文案映射（不再透传服务端中文 message，e2e 断言）；#4 证据边界 2 个 e2e（资料库标注 + 选材禁用与「无可用资料」）；#22 MySQL 5.7 门禁更新至 V20~V29 并用本机 5.7.24 实跑通过。回归：server 全量 **765 测试 0 失败**（新增 3，5 skipped 为环境门控）；web `npm run build` 通过；Playwright 全量 **141 passed / 6 skipped / 0 failed**（新增 2） |
| 2026-09-30 | **第六批（资源边界与交付链）执行完成**：#44 通用 AI 端点 input 大小上限（序列化字节数 262144，超限 40001 不落库）；#17 导入解析页数/文本长度/耗时上限（默认 60 页、200000 字符、15s，超时中断并按业务错误返回）；#29 沟通草稿与任务结果同事务落库（租约被接管时不再留孤儿草稿）；#30 PDF 导出 stale 结果立即清理孤儿文件；#54 导出过期判定条件化（并发推进时回读最新状态，不误报 EXPIRED）；#14 PDF 导出结果复用（未过期的在途/成功任务直接复用，过期才重渲染）。回归：server 全量 **779 测试 0 失败**（新增 14，5 skipped 为环境门控）；`docs/05` §7.8/§11.1 契约同步 |
| 2026-09-30 | **第七批（并发与幂等续）执行完成**：#38 `Resume` `@Version` + V30 迁移（陈旧副本不再回写并发推进的版本指针，冲突 40901）；#78 规则评分结果复用（同版本+JD+规则版本且 JD 未改 → 复用既有行；JD 改动后重算）；#28 V31 三个组合索引（AI/面试每日配额、跟进筛选；`resume` 相关已由 V12 覆盖）。回归：server 全量 **783 测试 0 失败**（新增 4，5 skipped 为环境门控）；MySQL 5.7 门禁重跑到 V31 通过（首版 V31 的重复索引由门禁拦下并修正）；CI + Functional Regression 双绿（workflow run 36722127408 / 36722127392，head 3134ecd，复核结论 success） |
| 2026-09-30 | **第八批（AI 输入边界与前端请求收敛）执行完成**：#36 资料文本统一 prompt 字节预算（`MaterialPromptTextBudget`：单条额度 = min(单条上限 64KB, 总量 256KB/条数)，n 条文本总量恒不超上限；选择/生成两条链路共用；UTF-8 字符边界截断 + `[truncated]` 标记；contentJson 超限按顶层条目保留并置 `_truncated`（保持 JSON 合法）；不丢弃资料、不新增前端需映射的原因码）；#33 投递编辑页版本定位去扇出（`ResumeVersionDetail` 暴露 `resumeId`，单请求定位 + 仅重载目标简历的版本列表）。回归：server 全量 **792 测试 0 失败**（新增 9，5 skipped 为环境门控）；web `npm run build` 通过；Playwright 全量 **142 passed / 6 skipped / 0 failed**（新增 1）；CI + Functional Regression 双绿（workflow run 36724894839 / 36724894659，head 0d70423，复核结论 success） |
| 2026-09-30 | **第九批（版本列表读模型，#50 收尾之一）执行完成**：#50 版本历史列表改摘要投影（`VersionSummaryProjection`，不再加载 `resume_json`/`generation_context`，响应不再返回 `generationContext`）；V32 新增 `resume_version.template_code` 派生列（写入时归一化，历史行由读路径惰性回填——白名单属应用层语义，SQL 迁移复刻会漂移）；`docs/05` §5.1 契约同步。回归：server 全量 **794 测试 0 失败**（新增 2：投影 IT + 写入路径单测，5 skipped 为环境门控）；MySQL 5.7 门禁推进到 V32 实跑通过（13 条迁移 + 新增列断言）；web `npm run build` 通过；Playwright 全量 **142 passed / 6 skipped / 0 failed**；CI + Functional Regression 双绿（workflow run 36727305847 / 36727306072，head 892c177，复核结论 success） |
| 2026-09-30 | **第十批（投递列表读模型，#50 收尾之二）执行完成**：#50 `GET /api/applications` 改摘要投影（不返回草稿长文本，改 `draftCount` 支撑「n/3」；`feedbackText` 保留——状态迁移接口按请求值覆盖备注）；新增 `GET /api/applications/{id}` 供展开/编辑按需拉取（展开走前端缓存，编辑拉最新，保存后清缓存）；`docs/05` §9.2 契约同步。顺带修复既有不稳定用例：`ResumeImportServiceTest.rejectsExtractionBeyondTimeout` 由「40 页 PDF 必然超过 5ms」改为覆盖抽取实现的确定性阻塞（快机器可毫秒级解析完 → 偶发失败，本批全量回归首次暴露）。回归：server 全量 **794 测试 0 失败**（5 skipped 为环境门控）；web `npm run build` 通过；Playwright 全量 **143 passed / 6 skipped / 0 failed**（新增 1）；CI + Functional Regression 双绿（workflow run 36729273163 / 36729273210，head 21d52a8，复核结论 success） |
| 2026-09-30 | **第十一批（投递状态更新备注语义）执行完成**：`PATCH /api/applications/{id}/status` 的 `feedbackText` 改为「未发送即保留、空串清空」（此前缺席/null 会静默清空备注，拖拽改状态等调用可误删数据）；前端不再回传陈旧值/不预填草稿，仅发送用户编辑过的文本；`docs/05` §9.2 契约同步。回归：server 全量 **794 测试 0 失败**（5 skipped 为环境门控）；web `npm run build` 通过；Playwright 全量 **143 passed / 6 skipped / 0 failed** |
| 2026-09-30 | **#11（分页契约）决策记录**：与用户确认「记录不实现」——列表已在 #50 三批中改为摘要投影、数据为用户维度量级可控；分页需补选择器 options 端点、迁移 31 项功能测试与列表/看板 UI，收益/成本不划算。保留为待观察项（出现数据量显著增长的真实案例时再按「纯列表页分页 + options 端点」实施）。同时确认三项产品决策项（#26 / #7 / #53~#55）推进、#22 门禁去留暂不推进 |
| 2026-09-30 | **第十二批（AI 上下文白名单统一，#53/#54/#55）执行完成**：新增共享常量 `ResumeSections.AI_CONTEXT_SECTIONS`（13 章，排除 `links`）作为唯一来源——素材生成（服务端提示词由常量生成 + 前端白名单从 `sectionRegistry` 派生）、面试上下文投影（新增 objective/志愿/课程/成果/奖项/自定义模块 + `appendItems` 复用）、沟通 prompt（8 章 → 13 章，脱敏策略不变）。回归：server 全量 **797 测试 0 失败**（新增 3，5 skipped 为环境门控）；web `npm run build` 通过；Playwright 全量 **143 passed / 6 skipped / 0 failed**；CI + Functional Regression 双绿（workflow run 36732641767 / 36732641579，head 278e855，复核结论 success） |
| 2026-09-30 | **第十三批（AI 任务留存清理，#26）执行完成**：`AiTaskRetentionService` 每日压缩超期（默认 90 天）终态任务的内联快照与结果（待确认/进行中不压缩）；V33 `snapshot_purged` 标记保证一次性（5.7 门禁同步到 V33）；`DELETE /api/ai/tasks/history`（只删本人终态且非待确认任务）+ 账号页「清空 AI 任务历史」入口；`docs/05` §7.5 留存契约同步。回归：server 全量 **799 测试 0 失败**（新增 2，5 skipped 为环境门控）；MySQL 5.7 门禁推进到 V33（14 条迁移）实跑通过；web `npm run build` 通过；Playwright 全量 **143 passed / 6 skipped / 0 failed**（首次全量运行出现 1 次 `ats-ai` 偶发失败——隔离运行与随后的全量重跑均通过，疑与并行 worker 冷启动解析链有关，留观 CI）；CI + Functional Regression 双绿（workflow run 36734555890 / 36734556149，head 3d790b8，复核结论 success） |
| 2026-09-30 | **第十四批（账号数据导出与删号入口，#7）执行完成**：新增 `GET /api/auth/export`——按用户聚合为可下载 JSON（`formatVersion: 1` + `Content-Disposition` 附件 + 显式 UTF-8；定向测试暴露「String 消息转换器默认 ISO-8859-1 会把中文写成 `?`」并修正）；范围＝简历+版本（含归档）/职业资料/JD/投递/面试会话+轮次/答案资产+章节/自定义沟通模板+草稿/个人资料/AI 同意记录，显式排除 AI 任务内联快照与结果及其派生 ATS/匹配分析、登录会话凭据（`User` 白名单取值，不导出 `passwordHash`）；账号页新增「数据与隐私」带：导出即下载 `.json` + 删号对话框（输入用户名二次确认 → 现有 `DELETE /api/auth/me` → 清本地会话跳登录，服务端同时作废 refresh cookie）；顺带 `CommunicationDraft` 补标准 getter（原仅 setter，无法序列化）、`CareerMaterial.contentJsonText` 加 `@JsonIgnore`（搜索用内部投影不外泄）。回归：server 全量 **801 测试 0 失败**（新增 2，5 skipped 为环境门控）；web `npm run build` 通过；Playwright 全量 **144 passed / 6 skipped / 0 failed**（新增 1）；`docs/05` §2.7/§2.8 契约同步；CI + Functional Regression 双绿（workflow run 36737379821 / 36737380285，head b68400a，复核结论 success） |
| 2026-09-30 | **第十五批（异步失败消息边界与观测/消费口径，#514/#524/#533；台账对账新增项）执行完成**：#514 新增 `AsyncFailureMessages.persisted()`（截断 1000 < 列宽 1024、不切断代理对、null 保留清空语义）并接入 6 处 `error_message` 写入路径（AI 任务租约、面试 `markAttemptFailed` 与 3 处配额分支），PDF 导出租约的内联截断收敛到同一实现——此前超长 provider/异常 message 会让「释放失败」事务失败、任务卡在 RUNNING 直到租约接管，真实失败原因被掩盖；#524 配额 gauge 由「全站任务行数」改为与限流同单位的 `resume_ai_quota_daily_attempts{scope="all_users"}`（重试计次；新增全局尝试数查询、删除旧查询），Grafana 面板表达式与标题同步；#533 规则评分拒绝归档版本（40901，与 ATS/导出/投递/沟通对齐；归属校验在前避免存在性泄露）；报告新增 §2.11 台账对账（38 行核对表 → §2 漏登项逐项核实，登记 PDF 服务容量/队列/关闭语义为下一批候选）。回归：server 全量 **808 测试 0 失败**（新增 7，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36739172314 / 36739172326，head 717b883，复核结论 success） |
| 2026-09-30 | **第十六批（PDF 服务容量/队列/关闭语义；台账行「PDF readiness 与容量」剩余部分）执行完成**：`createBrowserPool` 增加并发许可（`PDF_SERVICE_MAX_CONCURRENT_PAGES` 默认 4）+ FIFO 等待队列上限（`PDF_SERVICE_MAX_QUEUE_SIZE` 默认 16，0=不排队）+ `stats()/beginDrain()/waitForIdle()`，队满或 drain 中立即返回可重试 503（此前每次 `newPage()` 无任何上限、渲染请求可无限堆积）；`server.js` 容量与 drain 超时走环境变量（非法值启动即失败）、`/health` 暴露 capacity 快照与 `render-capacity` 检查（饱和不改整体 status，避免瞬态抖动）、`/render` 503 附带 `Retry-After` 与 `code 50301`、关闭流程改为 drain 拒新 → 等 in-flight（上限默认 10s）→ 关浏览器 → 关监听；API 侧新增 `PdfFailureCategory.OVERLOADED`（503 映射）与可重试文案「PDF 服务繁忙，请稍后重试」；`deploy/docker-compose.prod.yml` 补 `stop_grace_period: 30s`（必须大于 drain 超时）；`pdf-service/README.md` 契约与配置表同步。回归：pdf-service `npm run check` + `npm test` **28 通过**（新增 5）；server 全量 **808 测试 0 失败**（5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36740096685 / 36740096636，head 49cff3f，复核结论 success） |
| 2026-09-30 | **第十七批（全站配额观测计数索引，迁移 V34；EXPLAIN 取证驱动）执行完成**：用本机 MySQL 5.7.24 + 20 万行 ai_task 一次性探针取证（用完即删）——`resume_ai_quota_daily_attempts` 的查询形状（`task_type = ? AND created_at > ?`）此前为**全表扫描**（`EXPLAIN type=ALL rows≈199191`，实测 62~66ms/次，Prometheus 每次抓取 × 每任务类型各执行一次），补 `ai_task(task_type, created_at)` 后同一探针变为 `range + Using index condition`（**0.22~0.29ms**，约 250×）；同一探针证明 worker 领取查询仍走 PRIMARY（主键序 + `LIMIT` 早停）与留存清理同为 PRIMARY，故**不为**它们加索引（台账行「Worker 领取与索引」由「无实测证据」升级为证据化结论）。回归：server 全量 **808 测试 0 失败**（5 skipped 为环境门控）；MySQL 5.7 门禁推进到 V34（V20~V34 共 15 条迁移 + 新索引列数断言）实跑通过；CI + Functional Regression 双绿（workflow run 36741305458 / 36741305424，head e74ee4b，复核结论 success） |
| 2026-10-01 | **第十八批（CI 交付链预检：Ubuntu 26.04 迁移 + Actions 运行时）执行完成**：GitHub 公告 `ubuntu-latest` 将于 **10/19–11/19 渐进迁移到 Ubuntu 26.04**（默认 JDK 17→25、Node 22→24、MySQL 8.0→8.4，并移除若干工具；官方建议先在 `ubuntu-26.04` 上显式验证）。用临时探针分支（`workflow_dispatch` 显式触发，**验证后已删除**）把 5 个 job 全部切到 `ubuntu-26.04` 实跑：CI（36741853056）与功能回归（36741858472）**双绿**——server 测试（setup-java 17）/ web 构建 + Playwright Chromium（`--with-deps`）/ MySQL 8.0 容器 / CJK 字体 apt（`fonts-noto-cjk`）/ pdf-service Puppeteer 渲染全部通过，结论为**无需 pin 到 24.04**；同批把 `actions/setup-python` v5 → v7（v5 基于已被 GitHub 移除的 Node 20，运行时被强制替换并产生弃用告警）；CI + Functional Regression 双绿（workflow run 36743501885 / 36743501553，head 4a12531，复核结论 success） |
| 2026-10-01 | **第十九批（接口契约完整性：docs/05 × 实现双向对齐 + 静态门禁）执行完成**：新增 `ApiDocCoverageGateTest`（按控制器注解静态枚举端点，要求 docs/05 全覆盖、占位符名称归一、fail-closed）——首次运行暴露 **32 个端点未进说明书**（个人资料/简历导入/版本归档恢复/JD 引用/AI 续办重试选材确认/ATS 查询重试/沟通模板与草稿/投递统计/导出重试/认证凭据与全端登出/职业资料搜索/系统健康），已按控制器与 DTO 事实补入 §2.9/§3.4/§4.6-4.7/§5.4/§6.5/§7.9/§8.3/§9.5-9.6/§11.4/§12-14；反向抽查发现 **6 处陈旧契约**（`generate-resume-for-job`/`optimize`/`rewrite-summary`/`rewrite-project`/`generate-resume-from-material`/职业资料 `references`），前五处按现行流程重写（选材→确认→生成子任务 / 通用白名单 `RESUME_OPTIMIZE` / 统一 `INLINE_OPTIMIZE`），最后一处从未实现、已移除并注明。回归：server 全量 **809 测试 0 失败**（新增 1 门禁，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36744847721 / 36744847646，head caf8122，复核结论 success） |
| 2026-10-01 | **第二十批（客户端 IP 契约：生产限流正确性 + XFF 可信链；§2.13 扫描新增项）执行完成**：核实发现 `RATE_LIMIT_TRUST_FORWARDED_HEADERS` 从未出现在任何部署配置 → 默认 false，而生产流量经 nginx 代理到达 API（`remoteAddr` 恒为代理地址），**所有客户端共用同一限流分桶**——登录 10/min、注册 5/min、刷新 30/min、简历解析 6/min、JD 解析 15/min 全部退化为全站共享阈值；若简单开启信任又有伪造面（最外层 nginx 用 `$proxy_add_x_forwarded_for` 追加、客户端伪造值留在最左 → 绕过按 IP 限流 + 海量唯一值把分桶表顶到 maxBuckets 触发 #43 fail-closed 误伤）；第三处漂移：`AuthController.clientIp` 无视开关无条件读 XFF（会话审计 IP 可伪造）。修复：新增 `ClientIpResolver`（`common/api`，限流分桶与会话审计共用同一语义）；三份最外层 nginx（`edge.conf` / `host.conf` / `edge-ip-test.conf.template`）改为 `$remote_addr` **覆写** XFF、内层 `web` 保持**追加**（两份配置加注释说明契约）；`ProductionConfigurationValidator`（prod profile）新增启动校验要求信任开关为 true（与 COOKIE_SECURE 同级 fail-closed）；`production.env.example`、`production.ip-test.env.example`、`docs/DEPLOYMENT_DIRECT.md` 的 `.env` 样例与 `docs/DEPLOYMENT.md` 补开关与反代契约说明；新增静态门禁 `DeployProxyClientIpContractTest`（最外层必须覆写、内层必须追加）。测试：`ClientIpResolverTest`（4）+ `RateLimitFilterTest` 新增「伪造 XFF 不绕过」「信任后按最左值分桶」（2）+ `ProductionConfigurationValidatorTest` 新增「拒绝未开启」（1）+ 门禁（2）。回归：server 全量 **818 测试 0 失败**（新增 9，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36748547772 / 36748547764，head 339c10f，复核结论 success） |
| 2026-10-01 | **第二十一批（PDF 导出死线链：排队上限 + 服务端预算可配置 + 租约/批次对齐；§2.14 扫描新增项）执行完成**：核对三层时间边界发现——① API 读超时默认 15s 早于 pdf-service 服务端上界（`setContent` 15s + `pdf` 15s，且队列等待无时长上限），合法慢渲染会被客户端先断开（渲染浪费 + 任务误判失败）；② 队列只限条数不限时长；③ `claimBatch` 一次性把 90s 租约写给整批、worker 串行处理（默认 3 条），读超时提升后会超出租约（处理途中被接管 → 重复渲染）。修复：pdf-service 新增 `PDF_SERVICE_QUEUE_TIMEOUT_MS`（默认 15s，排队超时以可重试 503 拒绝；队列项带超时计时器，获名额/drain 时清理）与 `PDF_SERVICE_RENDER_TIMEOUT_MS`（默认 15s，替换硬编码 `setDefaultTimeout`；非法取值启动即失败）；API 读超时默认 15 → **50s**（覆盖 15+15+15+余量；CI profile 30 → 50 对齐）、PDF worker `batch-size` 默认 3 → **1**（串行处理吞吐不变，保证「租约 90s ≥ batch × 读超时 + 余量」）；`.env.example` / `pdf-service/README.md` 补死线链说明；新增跨运行时静态门禁 `PdfDeadlineContractTest`（链序 + `@Value` 兜底与 yml 默认值一致）。回归：pdf-service `npm run check` + `npm test` **31 通过**（新增 3）；server 全量 **820 测试 0 失败**（新增 2 门禁，5 skipped 为环境门控）；CI 首跑（be094e9）在 Linux/Node 20 下暴露测试缺陷——排队计时器 `unref()` 使 node:test 报「Promise resolution is still pending…」并取消 2 个用例，改为保持 ref（d6d808d；获名额/drain 均 `clearTimeout`）后 **CI + Functional Regression 双绿**（workflow run 36752112374 / 36752112314，head d6d808d，复核结论 success） |
| 2026-10-01 | **第二十二批（进程关闭语义：API 优雅停机 + 容器宽限期 + 关闭契约门禁；§2.15 扫描新增项）执行完成**：核对停语义发现——① `application.yml` 未声明 `server.shutdown`（默认 immediate），SIGTERM 立即断开在途 HTTP 请求（发布/重启期间连接被重置，导入解析等长请求首当其冲）；② compose `api` 未声明 `stop_grace_period`（docker 默认 10s），即使开启优雅停机也会中途 SIGKILL；③ pdf-service 侧已有 drain + 30s 宽限期（第十六批），但「宽限期 ≥ 停机上限」的关系仅靠注释约束。修复：`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:20s}`（刻意不等待 worker——AI/PDF worker 均为守护线程、由租约接管在途任务，故只影响 Web 请求）；compose `api.stop_grace_period: 25s`（> 20s + 余量；systemd 默认 TimeoutStopSec=90s 足够）；新增静态门禁 `ShutdownContractTest`（API 与 pdf-service 两侧：容器宽限期必须 ≥ 停机/drain 上限 + 5s）。回归：server 全量 **822 测试 0 失败**（新增 2 门禁，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36753021486 / 36753021510，head 7a46115，复核结论 success） |
| 2026-10-01 | **第二十三批（CI 交付链收敛：Node 运行时对齐 + functional 作业依赖缓存）执行完成**：`ci.yml` 的 web 与 pdf-service 作业 Node 由 20 → **22**（Node 20 已于 2026-04 EOL；pdf-service 依赖 puppeteer ^25 声明 engines ≥22.12，锁 20 会持续产生 EBADENGINE 告警）；`functional.yml` 两个作业此前**依赖 runner 预装 Node**（ubuntu-latest 迁到 26.04 后会从 20 变 24，版本漂移静默改变行为），现显式 `actions/setup-node@v5`（Node 22 + npm 缓存 + pdf-service lock 路径）并给两个 `setup-java` 步骤补 maven 缓存（此前每次运行全量拉取依赖）。两个 workflow 的 YAML 经本地解析校验；CI + Functional Regression 双绿（workflow run 36753598577 / 36753598642，head 3c46b1c，复核结论 success；new 配置实跑：web/pdf-service 作业 Node 22、functional 作业 setup-node + maven/npm 缓存全部生效） |
| 2026-10-01 | **第二十四批（协议级调用方错误映射：405/415/406 + 上传超限 413；§2.16 扫描新增项）执行完成**：用真实 HTTP 探针（本机 H2 实例 + curl）取证发现四类「调用方传错」落 catch-all 被报成 500——① `POST` 打到 GET-only 端点 → 500；② `text/plain` 打到 JSON 端点 → 500；③ 6MB 上传（超 `max-file-size=5MB`）→ 500 + ERROR 全栈（`MaxUploadSizeExceededException`）；④ 修复 406 首版后实测仍坍缩为 401（错误体按请求 `Accept` 无法写出 → ERROR 派发被安全链拒绝）。修复：`GlobalExceptionHandler` 新增协议级 handler（405/415/406、413、非法 multipart 400），响应显式 `Content-Type: application/json`；`docs/05` §1.3/§13 契约同步。验证：真实 HTTP 四类探针全部返回 405/415/406/413 + 统一信封（`Accept: xml` 也不再坍缩）；新增 `HttpSemanticsIT`（3）+ handler 单测（5）+ `functional-tests` 超限上传断言；本机整套件 28/28 通过；server 全量 **830 测试 0 失败**（新增 8，5 skipped 为环境门控）；CI 首跑（ea52f77）功能回归暴露「413 送达依赖 Tomcat swallow」——6MB 上传在 CI 拿到连接重置（本地未复现），跟进修复 `server.tomcat.max-swallow-size: 8MB` + 断言体积收敛为 5.5MB（98db24e）后 **CI + Functional Regression 双绿**（workflow run 36756608517（重跑一次，首次因触发旧 run 重跑被 cancel-in-progress 取消）/ 36756608510，head 98db24e，复核结论 success） |
| 2026-10-01 | **第二十五批（顺序确定性：面试「最近尝试」与资料选择截断/候选排序双键化；§2.17 扫描新增项）执行完成**：全仓扫描「取最近一条 / 按时间截断」读路径发现——① `interview_ai_attempt.updated_at` 为全库唯一秒级 DATETIME（V20），`findFirstBySessionIdAndStatusOrderByUpdatedAtDesc` 无 tie-break，同一秒两条 FAILED（不同 round/operation）时 aiFailure 的 stage/messageCode/retryable 可展示较早那条（误导用户），PROCESSING 超时判定同险；② `MaterialSelector` 按仓库返回顺序对 normal 做 `subList` 截断、`JobMaterialSelectionService` 候选排序 `score→updatedAt` 无 id 兜底，career_material 毫秒并列时被丢弃的素材集合与进入提示词的 60 条候选集 run-to-run 变化；③ `AiConsentRepository.findFirstByUserIdAndEventTypeOrderByCreatedAtDesc` 死代码（#71 残留）。修复：两处仓库方法改 `(updated_at desc, id desc)` 双键（7 个调用点 + 4 个测试文件 11 处引用同步）、候选比较器补 `.thenComparing(id desc)`、`MaterialSelector` Javadoc 注明确定性依赖、删除死方法。验证：新增 `CareerMaterialOrderingIT` / `InterviewAiAttemptOrderingIT`（并列 fixture 断言 id 降序契约）；定向 36 测试通过；server 全量 **832 测试 0 失败**（新增 2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36759868605 / 36759868683，head 2859461，复核结论 success） |
| 2026-10-01 | **第二十六批（限流路径归一化：百分号转义/矩阵参数等价路径不再绕过受限端点；§2.18 扫描新增项）执行完成**：真实 HTTP 探针（本机 H2 + curl，登录限流降至 2/分钟）实证——同一 IP 明文第 3 次 429，而 `POST /api/auth/%6Cogin`（%6C=小写 l，MVC 解码后路由到同一 login 控制器）仍 401「账号或密码错误」直达控制器且未计数；`/api/auth/%72efresh` 同族。根因：`RateLimitFilter` 用 `request.getRequestURI()` 对受限路径做字面量精确匹配，而 Spring MVC 按**解码后**路径路由——5 个受限端点（login/register/refresh/resume-imports parse/jobs parse）均可被等价转义写法绕过按 IP 限流。修复：路径来源改用 `UrlPathHelper.getPathWithinApplication`（与 MVC 同语义：解码 + 去矩阵参数），分桶键随之归一，转义/矩阵参数只计入同一分桶（fail-closed），非受限路径不受影响。验证：修复前定向测试红（RateLimitFilterTest 200≠429、AuthRateLimitIT 401≠429）→ 修复后全绿；修复后真实 HTTP 复测：明文 401/401/429、`%6C`/`%6c` 均 429、`/api/system/h%65alth` 仍 200；server 全量 **835 测试 0 失败**（新增 3，5 skipped 为环境门控）；`docs/05` §1.3 补限流归一化契约；CI + Functional Regression 双绿（workflow run 36762560121 / 36762560059，head 6a79e3a，复核结论 success） |
| 2026-10-01 | **第二十七批（凭证变更端点限流覆盖：改密/改邮箱共享分桶；§2.19 扫描新增项）执行完成**：真实 HTTP 探针（本机 H2 + curl，凭证限流降至 2/分钟）取证的**覆盖缺口**——`POST /api/auth/me/password` 与 `/api/auth/me/email` 都校验「当前密码」（`AuthService.requireCurrentPassword` 仅 `passwordEncoder.matches`，无尝试节流），但不在 `RateLimitFilter.limitFor` 受限路径内：连续 4 次改密 + 1 次改邮箱全 401、无 429；持有被盗 access token 者可绕过登录 10/min 无限试口令（改密成功即账号接管）。修复：两端点纳入限流（默认 5/min，`app.security.rate-limit.change-credential-per-minute`），并由 `bucketGroup()` 归为 `/api/auth/me:credential` 同一分桶（各自分桶会给出交替双倍预算），其它端点维持按路径独立分桶；`application.yml` 与 `server/.env.example` 同步 `RATE_LIMIT_CREDENTIAL`。验证：修复后真实 HTTP——第 3 次改密 **429 + 42901**、随后改邮箱 **429**（共享分桶生效），未登录请求先计数再 401；定向 16 测试通过；server 全量 **838 测试 0 失败**（新增 3，5 skipped 为环境门控）；`docs/05` §1.3 受限端点清单更新；CI + Functional Regression 双绿（workflow run 36764426807 / 36764427024，head 4b95444，复核结论 success） |
| 2026-10-01 | **第二十八批（上传路径体积与死线对齐：内层 nginx 隐式 1m + 前端上传超时；§2.20 扫描新增项）执行完成**：与第二十一批 PDF 死线链同族的三层核对发现——① 生产容器拓扑 `edge → web → api`，`edge`（`deploy/nginx/edge.conf`）声明 `client_max_body_size 5m`，但 `web` 镜像实际打包 `web/nginx.conf`（`web/Dockerfile` L12）**未声明**该指令 → 生效 nginx 内置默认 **1m**：`edge` 已放行的 1~5MB 简历被内层以 413（nginx 自带 HTML，非统一信封）拒绝，与 `docs/05` §13 承诺的 5MB 不一致；**该缺陷对既有测试全谱不可见**（功能回归直连 API 不经 nginx、本地开发走 Vite 代理、`deploy/nginx/web.conf` 同步副本同样漏声明）；② `web/src/api/resumeImport.ts` 未覆盖超时 → 用全局 10s，早于服务端解析预算 15s，慢但成功的解析被客户端先判失败（前端显示泛化解析错误），与导出 30s / 面试步进 60s 的既有放宽惯例不一致。修复：两份 web nginx 配置补 `client_max_body_size 5m`，上传请求显式 `timeout: 30_000`。验证：新增跨运行时静态门禁 `UploadPathContractTest`（5 条断言：含 `/api/` 反代的配置必须显式声明体积上限、内层 ≥ 外层、各层 ≥ 应用 multipart 上限、镜像内与部署侧副本一致、前端上传超时 > 服务端预算）——修复前 **5/5 红**、修复后全绿；web `npm run build` 通过；server 全量 **843 测试 0 失败**（新增 5，5 skipped 为环境门控）；`docs/05` §13 与 `docs/08` 补代理层体积契约；CI + Functional Regression 双绿（workflow run 36766970574 / 36766970651，head 13823d4，复核结论 success） |