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

### 2.21 上传边界语义与下载死线跟进（2026-10-01 第二十九批扫描，已随本批修复）

方法：第二十八批补上体积上限后，继续核对「该上限与哪一侧的应用上限对应」——`client_max_body_size` 限制的是**整个请求体**，而应用有两个不同口径的上限（单文件 `max-file-size` 与整请求 `max-request-size`），二者不可混同。

| 项 | 取证（修复前） | 结论 |
| --- | --- | --- |
| nginx 体积上限按**单文件**口径对齐，导致文档承诺的 5MB 边界不可达 | 应用侧：`max-file-size: 5MB`（文件部分）与 `max-request-size: 6MB`（整个请求，`application.yml` 注释明确写为「预留请求头等余量」）；业务判据 `ResumeImportService.maxBytes = 5242880`（超限 → 40001「文件不能超过 5 MB」）。但 nginx 各层为 `5m`，限制的是含 multipart 边界与头的**整请求体** → 恰好 5MB 的文件（体约 5MB + 数百字节）被 nginx 以 413 拒绝，应用的 5MB 承诺在边界处不可达，且 6MB 余量与「应用 JSON 信封」在 5MB~6MB 区间完全用不上 | **存在缺陷 → 修复**：`client_max_body_size` 由 `5m` 改为 **`6m`**（= 应用整请求上限），4 份配置同步（`edge.conf`、`host.conf`、`web/nginx.conf`、镜像同步副本 `deploy/nginx/web.conf`） |
| 门禁口径错误 | 第二十八批的门禁断言「各层 ≥ `max-file-size`(5MB)」，正是错误口径——它对本缺陷**不报错** | **已修正**：断言改为「各层 ≥ `max-request-size`(6MB)」，并新增「`max-request-size` 必须 > `max-file-size`」以固化「余量存在」这一前提 |
| 导出下载未放宽超时 | `web/src/api/export.ts` 的下载走 axios 全局 10s，而响应体上限 `pdf.max-output-bytes` 默认 **10MB**（移动网络 10MB 常需数十秒）→ 合法下载被判超时；与导出数据（30s）、面试步进（60s）的既有放宽惯例不一致 | **存在缺陷 → 修复**：`timeout: 60_000` |

修复动作：4 份 nginx 配置 `client_max_body_size` 5m → 6m（注释写明「整请求 vs 单文件」的语义差与失败模式）；`UploadPathContractTest` 的门禁口径由单文件改为整请求上限，并新增「导出下载必须显式放宽超时」断言（共 6 条）；`application.yml` 中引用 nginx 上限的注释同步为 6m；`docs/05` §13 与 `docs/08` 更新体积契约口径。验证：门禁修复前 **2/6 红**（`web/nginx.conf` 5242880 < 6291456；导出下载无显式超时）、修复后 6/6 绿；web `npm run build` 通过；server 全量回归见 §5。

### 2.22 投递编辑「版本列表重复请求」竞态（2026-10-01 第二十九批扫描，已随本批修复）

方法：`web/e2e/applications-edit.spec.ts` 的「编辑投递时只发 1 次版本列表请求」用例此前两次偶发失败（第二十四批 CI 首跑、第二十九批纯文档提交 CI run 36768442619），按报告登记口径「再次复现则按竞态排查」进入排查：先做**确定性复现**（在 e2e 中给选项加载的 `/api/resumes` 响应加 400ms 延迟，放大窗口），再定位根因。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 确定性复现 | 仅给 `**/api/resumes` 加 400ms 延迟（不改任何源码）→ 用例**稳定失败**：`expect(versionListsWhileEditing).toBe(1)` → `Received: 2`，与 CI 偶发签名完全一致 | **是真实竞态，不是测试问题** |
| 根因 | `useResumeJobOptions.load()` 在 `Promise.all([listResumes, listJobs])` 返回后**无条件**调用 `loadVersions()`，而 `loadVersions()` 按**当前** `selectedResumeId` 取数。投递页「编辑」会先 `GET /api/resume-versions/{id}` 定位所属简历 → 设 `selectedResumeId` → `loadVersions()`；若编辑流在选项请求返回**之前**完成，`load()` 随后又按该简历发一次列表请求 → 同一简历被加载两次（既有 `versionEpoch` 只防「旧响应覆盖」，不防「重复请求」） | **存在缺陷 → 修复**：`load()` 记录加载开始时的选择；仅当「本次加载确立了选择」（首屏默认选中）或「选择自加载开始未变」时才加载版本列表（保留重试/重载语义，跳过与调用方重复的那次） |
| 影响面 | 该组合式函数被 5 个视图复用（投递、ATS、面试、沟通、成果引导）；慢网络或「页面刚加载即点编辑」的真实用户路径同样会多发一次版本列表请求（#33 的收敛目标被削弱） | 已随本批修复（单点改动覆盖 5 处消费方） |

修复动作：`useResumeJobOptions.load()` 增加「加载期间选择已变更则不重复加载版本列表」的判断（`selectionBeforeLoad` + `assignedInitialSelection`）；e2e 用例保留 400ms 延迟作为**确定性竞态守卫**（并注明修复前稳定失败），从而把此前「靠偶发暴露」的缺陷变为每次都能验证。

### 2.23 配置兜底一致性与 429 退避契约（2026-10-01 第三十批扫描，已随本批修复）

方法：同一配置项会在三处出现（代码 `@Value` 兜底、`application.yml` 默认值、环境变量默认），任一漂移都是「配置缺省时行为与预期不同」的静默陷阱。此前只有 `PdfDeadlineContractTest` 覆盖 PDF 的两个键；本批新增门禁用 YAML 解析器把**全部** `@Value` 兜底与 yml 默认值逐项比对（让门禁自己枚举漂移，而非人工抽查）。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 全量兜底比对 | 新增 `ConfigFallbackContractTest`（SnakeYAML 解析 `application.yml` + 扫描 `server/src/main/java` 全部 `@Value`）：共扫描 **52** 条兜底、其中 **26** 条与 yml 可比对；首跑即报出**唯一**漂移 `app.ai.bailian.read-timeout-seconds` → 代码兜底 **60** vs yml 默认 **300** | **存在缺陷 → 修复**：兜底改为 300（yml 注释已说明推理型模型单轮 40~477s，60s 兜底会让合法慢响应被判超时） |
| 429 退避契约 | `RateLimitFilter` 的 429 只设状态码与信封，**无 `Retry-After`**（RFC 6585 建议携带）；同仓 pdf-service 的 503 已带该头，两处语义不一致；客户端只能盲目重试并继续打满窗口 | **已补齐**：429 携带 `Retry-After` = 固定窗口（自然分钟）剩余秒数 1~60 |

修复动作：`BailianAiProvider` 读超时兜底 60 → 300；`RateLimitFilter.writeTooManyRequests` 增加 `Retry-After`（新增 `secondsUntilNextWindow()`，取值 1~60）；新增静态门禁 `ConfigFallbackContractTest`（含自检：扫描到的兜底数与可比对数不得低于阈值，防止解析失效后门禁静默空转）。`docs/05` §1.3 补 429 退避契约（并注明 AI 日配额超限不带该头，因其按天重置、无等价短窗口值）。验证：门禁首跑 **1 条漂移**（修复后全绿）；单测断言 `Retry-After` 存在且落在 1~60、IT 断言响应头存在；真实 HTTP 探针（本机 H2，登录限流 2/分钟）实测第 3 次请求 `HTTP/1.1 429` + `Retry-After: 25` + 统一信封。

### 2.24 匿名健康探针的放大面（2026-10-01 第三十一批扫描，已随本批修复）

方法：核对「匿名开放端点是否含重活」——把公开白名单端点与其调用链逐层展开，检查是否有出站调用、DB 重读或 CPU 放大（与 #44/#17 的「未认证放大器」同族）。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 匿名健康探针每次请求都外呼 pdf-service | `GET /api/system/health` 在 `SecurityConfig` 中 `permitAll`（匿名），其响应由 `checks()` 计算，而 `checks()` 调用 `PdfServiceClient.checkHealth()` —— 一次真实出站 HTTP GET（连接/读超时各 1s）。故「1 个公开请求 = 1 次出站探测」：外部可把公开探针放大成对 pdf-service 的持续探测；pdf-service 不可达时每个请求阻塞 ~1s，持续打即占满 API 请求线程（Tomcat 默认 200 线程下约百 req/s 即饱和），且该端点不在限流清单内 | **存在缺陷 → 修复**：`checkHealth()` 增加 TTL 缓存（`app.pdf.health-cache-ttl-ms` 默认 5s，正负结果同样缓存，锁内刷新避免惊群），把入站请求速率与出站探测速率解耦 |
| 运维语义是否受损 | 容器健康检查用的是 `/actuator/health/readiness`（20s 间隔），前端与监控调用本端点的频率远低于 5s；TTL 内复用只让「探测新鲜度 ≤ 5s」 | 语义保持（并在 `docs/05` §14 写明 TTL 口径） |

修复动作：`PdfServiceClient.checkHealth()` 改为「TTL 内复用 + 锁内单次刷新」；新增 `app.pdf.health-cache-ttl-ms`（yml + `@Value` 兜底 5000，受 `ConfigFallbackContractTest` 守护）；`docs/05` §14 补缓存口径。验证：新增 `PdfServiceClientHealthCacheTest` —— 用 JDK `HttpServer` 作桩**直接统计出站探测次数**（走完整 RestClient 调用链）：TTL 内 20 次检查只探测 **1** 次、TTL 过后重新探测、5xx 的负结果同样被缓存（10 次检查只探测 1 次）；端到端真实 HTTP 实测（慢桩 pdf-service 固定延迟 1s）：冷启动请求 **1.64s**、随后 4 次匿名请求各约 **4~5ms**（命中缓存，无出站调用）。

### 2.25 限流窗口语义：固定窗口边界可稳定拿到 2 倍阈值（2026-10-01 第三十二批扫描，已随本批修复）

方法：核对「限流器在什么时间尺度上成立」——用可注入时钟的定向测试把窗口边界显式推进（新增包私有 `RateLimitFilter.nowMs()` 便于覆写），并在真实 HTTP 上对齐自然分钟边界复测。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 固定窗口在自然分钟边界给出**整份**额外配额 | 登录桶 10/min：在「距边界 100ms」用满 10 次后把时钟推进 200ms（跨过边界）再发 10 次，旧实现 **10 次全部放行**（定向测试首跑红：`expected <1> but was <10>`）。攻击者只需把请求对齐边界，即可把 10/min **稳定**跑成 20/min —— 阈值实际折半，而该面覆盖 login（口令爆破）/register/refresh/凭证变更（「当前密码」验证面） | **存在缺陷 → 修复**：`Bucket` 由「自然分钟计数」改为真正的滑动窗口计数（当前窗口 + 上一窗口按剩余比例加权） |
| 取舍已被记录但仍成立 | `RateLimitFilter.Bucket` 注释「窗口边界处突发流量可能达到 2 倍阈值」、`docs/05` §1.3 旧文案同样自述该取舍；2026-09-26 模块核对亦登记「注释称滑动窗口、实为固定窗口」 | 已闭环：注释与文案改为滑动窗口口径 |
| `Retry-After` 语义随之失真 | 旧值 = 距下一自然分钟秒数。滑动窗口下窗口滚动后上一窗口仍按 ≈100% 权重计入，**滚动瞬间重试必然再被拒**，该提示会引导客户端立即撞墙 | **已修正**：改为「距下一次配额可用」的秒数（按权重衰减解算，仍 1~60） |

修复动作：`RateLimitFilter.Bucket` 改为「当前/上一窗口双计数」——估计值 = 上一窗口计数 × 当前窗口剩余比例 + 当前窗口计数，`allow` 判 `估计值 ≥ limit`；`retryAfterSeconds()` 解算下一次放行时刻（当前窗口内权重衰减到阈值以下，或滚动后权重开始衰减），保留 1~60 取值；`evictStaleBuckets` 改为回收「两个窗口以前」的桶（此时两个计数都不再参与估计，等价于新建）；无分桶可查的 fail-closed 拒绝（#43 容量上限）固定提示窗口上限。新增的 `nowMs()` 仅作测试时钟接缝，生产仍为 `System.currentTimeMillis()`。

验证：定向测试**修复前红**（边界后 10 次 vs 期望 1 次）→ 修复后绿（14/14）；真实 HTTP 探针（本机 H2、端口 8089、`login-per-minute=10`）对齐自然分钟边界实测——边界前约 1.2s 内 10 次登录全部 `401`（放行），**边界后 250ms 的 10 次只有 1 次放行、其余 9 次 `429 + 42901`**，且 `Retry-After: 6` 与解算值一致（上一窗口 10 次、当前窗口已放行 1 次 → 需 6s 使权重衰减到 9 以下）；server 全量 **849 测试 0 失败**（新增 1，5 skipped 为环境门控）；`docs/05` §1.3 补滑动窗口口径与「Retry-After 不是距下一自然分钟」的说明。

> 残留误差（已写入代码注释与 §1.3 口径）：滑动窗口计数假设上一窗口请求均匀分布，窗口切换瞬间最多多放行 **1 次**（不随阈值放大）；要完全消除需窗口日志/令牌桶，当前内存单实例规模下不划算。

### 2.26 账号导出的放大面（2026-10-01 第三十三批扫描，已随本批修复）

方法：与 §2.24「匿名端点是否含重活」同族的反向核对——**认证**端点里是否存在「单请求成本随账号数据量线性增长、且可无限重复」的放大器（判据同 #17/#44 的放大器族，但这里的放大器只需一个已登录会话/被盗 access token）。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 单请求成本随账号数据量线性增长 | `GET /api/auth/export` 用 13 个仓储查询把本人全账号数据（简历+版本、职业资料、JD、投递、面试会话+轮次、答案资产+章节、模板+草稿、个人资料、AI 同意）一次性读出并**整体序列化**为缩进 JSON——无分页、无体积上限（`sourceText` 单条上限 64KB，条数不限）。真实 HTTP 探针（本机 H2）：播种 **200 条 62KB** 职业资料（库内约 12MB 文本）后，单次响应 **11.92MB**、耗时 **217~288ms** | **存在缺陷 → 修复** |
| 无任何次数上限 | 同一账号连续 10 次导出全部 `200`，累计出站 **119MB**、服务端累计约 2.4s；`limitFor` 覆盖 login/register/refresh、凭证变更、resume-imports/parse、jobs parse，**导出是漏项** | 纳入限流（默认 **3/min**，最严一档） |
| 前端下载超时与该体量不匹配 | `exportAccountData()` 走 30s，而同类的 PDF 导出下载（体上限 10MB）已在第二十九批放宽到 60s；实测中位账号已达 11.92MB 且无上限，30s 只够 ~3.2Mbps 的持续带宽 | **已对齐 60s**，并由门禁固化 |

修复动作：`RateLimitFilter.limitFor` 增加 `/api/auth/export` 分支 + 新配置 `app.security.rate-limit.account-export-per-minute`（yml / `@Value` 兜底 / `.env.example` 三处对齐，受 `ConfigFallbackContractTest` 守护）；`web/src/api/auth.ts` 导出下载超时 30s → 60s；`UploadPathContractTest` 新增「账号导出下载超时 ≥ 60s」断言；`docs/05` §1.3 受限端点清单补录导出。

验证：单测**修复前红**（第 4 次导出 `200` vs 期望 `429`）、门禁**修复前红**（30000 < 60000）→ 修复后定向 **22/22** 绿；真实 HTTP 探针：同一账号导出第 1~3 次 `200`，第 4/5 次 `429 + 42901 + Retry-After: 48`（滑动窗口解算值：当前窗口已满且无上一窗口权重可衰减 → 需等本窗口滚动，与剩余窗口时间一致）；server 全量 **851 测试 0 失败**（新增 2，5 skipped 为环境门控）；web `npm run build` 通过。

> 仍留观（需要产品/架构口径，非当前缺陷）：导出把整份 JSON 先构造为 `String` 再编码（12MB 响应约 2~3 份副本的瞬时堆占用），改流式写出可把单请求堆占用降到常数级；以及导出文档本身无体积上限（是否给账号数据或导出文档设上限/分片）。两者都取决于「导出文档格式与上限」的产品决策。

### 2.27 响应压缩缺失（2026-10-01 第三十四批扫描，已随本批修复）

方法：核对「传输层是否利用了响应体的可压缩性」——全仓扫描 `gzip` / `compression` / `Content-Encoding` 的任何声明，再用真实 HTTP 探针在**同一账号同一数据**上做「带/不带 `Accept-Encoding: gzip`」的对照。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 应用与反代都没有任何响应压缩配置 | 全仓（nginx conf / `application.yml` / 前端）扫描 `gzip|compression|Content-Encoding` **零命中**；真实 HTTP 探针（本机 H2，200 条 62KB 职业资料）：带 `Accept-Encoding: gzip` 请求账号导出，响应**既无 `Content-Encoding` 也无 `Vary: accept-encoding`**，`Content-Length: 12499897` 原样 12.5MB | **存在缺陷 → 修复**：应用层开启响应压缩 |
| 收益量级（避免合成数据虚高） | 首轮探针用「同一句话重复 2300 次」的合成文本，gzip 假高到 175×；改用**散文式词表**（200 条 63KB，条内无重复）复测：**12,773,702 → 2,144,823 字节（≈6×）**，响应头为 `Content-Encoding: gzip` + `Transfer-Encoding: chunked`；列表类 JSON（100 条摘要 16.7KB → 1.1KB）更高 | 收益确定，且与 §2.26 的下载死线互补（同一份数据在慢网络下更快、更不易触达 60s 超时） |

修复动作：`application.yml` 的 `server` 段新增 `compression`（`enabled: true`、文本类 mime 列表含 `application/json`、`min-response-size: 2048`）；新增静态门禁 `ResponseCompressionContractTest`——该契约只由 yml 承载，且 **MockMvc 不经过 Tomcat、任何既有测试都不会因它缺失而变红**，故必须显式固化（配置被关/被删/json 被移出 mime 列表/阈值被设为 0 都会让收益静默消失）；`docs/08` 补「响应压缩契约」。

安全口径（同时写入 yml 注释与 `docs/08`）：响应体只含调用方自己的数据、不放任何机密令牌（令牌在 `Authorization` 头），不构成 BREACH 的自反条件；应用侧压缩先于反代 TLS 终止，也不涉及 CRIME。

验证：门禁**修复前红**（临时把 `enabled` 置 false → 断言失败）→ 恢复后绿；真实 HTTP 探针体积对照如上（唯一变量是 `Accept-Encoding`）；server 全量 **852 测试 0 失败**（新增 1 门禁，5 skipped 为环境门控）。

> 留观：静态资源（SPA 产物）由 web 容器经 Nginx 直接提供、不经应用，其压缩需在 Nginx 侧单独开启——本批不动反代配置（接口响应已在应用层压缩，反代无需重复处理）。

### 2.28 静态资源交付缺失（2026-10-01 第三十五批扫描，已随本批修复）

方法：核对「静态产物的传输与缓存」——把构建产物（`web/dist`，含 `index.html` 引用关系）与三份服务静态资源的 nginx 配置逐项对照（哈希命名 / gzip / 缓存头），压缩比用本机 gzip 对同一份产物实测。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 三份配置都无 gzip | `web/nginx.conf`（`web/Dockerfile:12` COPY 进镜像、容器拓扑真正生效的一份）、`deploy/nginx/web.conf`、`deploy/nginx/host.conf` 均无任何 `gzip` 指令；对构建产物本机实测：64 个 js/css/svg **1001.1KB → gzip 285.5KB（3.51×）**，首屏入口 5 个文件（`index-<hash>.js` 179KB、`vue-vendor` 107KB、`axios` 46KB、`lucide`、CSS 87KB）**457.4KB → 139.8KB** | **存在缺陷 → 修复**：三层开启 gzip |
| 容器版缺「哈希产物长缓存」，与宿主机版漂移 | `deploy/nginx/host.conf:65-70` 早有 `location ~* \.(js\|css\|…)$ { expires 30d; add_header Cache-Control "public, immutable"; }`，而容器版两份（镜像内 + 部署侧同步副本）**都缺该块**：60+ 个哈希产物落进 `location /` 的 `no-cache`，每次导航逐个回源校验。既有门禁 `UploadPathContractTest.mirroredWebConfStaysInSync` 只比对 `client_max_body_size`，**对本漂移不报错** | **存在缺陷 → 修复**：容器版两份补齐该块 |

修复动作：三份配置新增 gzip 块（`gzip on` / `gzip_vary on` / `min_length 1024` / `comp_level 5` / `gzip_types` 含 js、css、json、svg）；`web/nginx.conf` 与 `deploy/nginx/web.conf` 补哈希产物缓存块（与 `host.conf` 同形，`expires 30d` + `public, immutable`），`location /` 保持 `no-cache`；新增静态门禁 `StaticAssetDeliveryContractTest`（对三份配置断言：gzip 开启且覆盖 js/css、`gzip_vary`、哈希产物块含 `immutable` 且有效期 ≥ 30 天、SPA 回退入口仍 `no-cache`）；`docs/08` 补「静态资源交付契约」。

层级边界（与 §2.27 互补、不重叠）：`gzip_proxied` 保持 nginx 默认 `off`，本层不给 `/api/` 反代响应做二次压缩——接口响应在应用层压缩，静态产物在 nginx 层压缩，两层各管一段。

验证：门禁**修复前红**（2/2 失败：三份配置无 gzip；容器版两份无哈希产物块）→ 修复后 **10/10** 绿（含既有体积门禁与压缩门禁）；压缩比为本机对同一份 `web/dist` 实测；server 全量 **854 测试 0 失败**（新增 2，5 skipped 为环境门控）。

> 本批顺带核实的既存差异（未改，登记留观）：镜像内 `web/nginx.conf` 不含 `security-headers.conf` 片段（镜像构建上下文是 `web/`，片段在 `deploy/nginx/`），故镜像自身不注入 4 条安全响应头——容器拓扑下由最外层 `edge` 统一下发，**公网响应不受影响**；但 `deploy/nginx/web.conf` 自称「与镜像内配置保持一致」，实际多出 4 处 `include`（本次 diff 确认差异仅为 include 与注释）。彻底消除需决定片段的单一来源（把片段纳入 `web/` 构建上下文，或把镜像构建上下文改为仓库根），属设计取舍故不在本批动。

### 2.29 保留期清理的收敛性（2026-10-01 第三十六批扫描，已随本批修复）

方法：核对「周期性作业的清理速率是否随积压增长」——把每个 `@Scheduled` 作业的「触发间隔 × 单轮处理量」与其输入增长率对照（本批先看 AI 任务留存 #26）。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 清理速率被硬编码为「200 行/天」 | `AiTaskRetentionService.purgeExpiredSnapshots()` 每轮只取一个 `PageRequest.of(0, batchSize)`（默认 200）且**没有续批**，而 `@Scheduled` 间隔是 24h（`app.ai.task.cleanup-interval-ms` 默认 86400000）⇒ 上限恒为 200 行/天。只要「每天新超期的终态任务数 > 200」，积压就单调增长：90 天保留期对超出部分永不生效，内联快照（含内联 JD/资料/简历，可达数百 KB/行）继续无界累积——**正是 #26 要消除的那个问题** | **存在缺陷 → 修复** |
| 既有测试不可见 | `AiTaskRetentionIT` 只播 6 行（小于一批），「多于一批」的路径无任何覆盖；类注释还把该行为描述为「不追求一次清完」 | 新增两个用例覆盖续批与单轮上限 |
| 同族对照：PDF 导出过期清理无此问题 | `ExportExpiryService.cleanupExpired()` 同样只处理一批，但其间隔是 **60s**、批量 100 ⇒ 上限 14.4 万行/天，远超 PDF 导出任务的产生速率 | 记录对照结论，**不改** |

修复动作：`purgeExpiredSnapshots()` 改为**续批循环**（每批一批一事务，避免单事务内持久化上下文与持锁时间随积压线性增长），新增单轮上限 `app.ai.task.cleanup-max-rows-per-run`（默认 20000，触达时记 WARN、剩余积压下一轮继续）；`docs/05` §7.5 留存契约同步说明「续批 + 单轮上限」口径。

验证：新增 IT 用例**修复前红**（批量 5、播 12 行 → 只有前 5 行被压缩，「同一轮清完」断言失败；未清理的 7 行还污染了相邻用例的全局计数断言，`expected <2> but was <9>`）→ 修复后 **3/3** 绿（含「单轮上限 7 行生效、剩余 5 行下一轮清完」）；新配置键由 `ConfigFallbackContractTest` 守护；server 全量 **856 测试 0 失败**（新增 2，5 skipped 为环境门控）。

### 2.30 数值型运维配置无取值校验（2026-10-01 第三十七批扫描，已随本批修复）

方法：核对「由环境变量注入的数值配置，取 0/负值时会发生什么」——`@Value` 只保证「能解析成整数」，故逐个键追到使用点看语义（本批用一次性探针 + 真实 HTTP 探针实证，探针用完即删）。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 清理批量取 0 → 作业每次抛异常 | `AI_TASK_CLEANUP_BATCH_SIZE=0` 时 `PageRequest.of(0, 0)` 抛 `IllegalArgumentException: Page size must not be less than one`（探针实录）；`ExportExpiryService` 同理。间隔 24h ⇒ 一天一条错误日志、清理从未生效 | **存在缺陷 → 修复** |
| 续批上限取 0 → 静默不清理 | `AI_TASK_CLEANUP_MAX_ROWS_PER_RUN=0` 时续批条件 `total < 0` 不成立 ⇒ 直接返回 0：探针下 3 行合格超期行一轮清理 **0** 行，无任何日志（与 #26 的目标相反） | 同上 |
| 保留期取 0 天 → 语义反转 | 探针：`retention-days=0` 时 cutoff = now，**刚创建**的终态任务立刻被压缩（实测 1 行新建任务结果被清空） | 同上 |
| 限流阈值取 0 → 端点永久 429，但启动正常 | 真实 HTTP 探针（本机 H2，`--app.security.rate-limit.login-per-minute=0`）：应用启动成功、`/api/system/health` 200，而**第 1 次**登录即 `429 + 42901`（`Retry-After: 43`）——登录入口静默砖化，健康检查毫无察觉 | 同上 |
| 既有校验不可见 | `ProductionConfigurationValidator` 只校验密钥与两个布尔开关（prod profile）；`@Value` 对「数值但非法」无任何约束 | 本批补齐 |

修复动作：新增 `NumericConfigurationValidator`（所有 profile 生效，`@PostConstruct`）——把「必须为正」的 14 个键集中成一张表（限流 7 个阈值 + `max-buckets`、AI 留存 4 个、PDF 过期清理 2 个），取 0/负值即抛 `IllegalStateException` 拒绝启动；表内同时自检「键是否都解析到」，未解析即失败（防配置解析路径失效后门禁静默空转）；`docs/08`「配置原则」补该口径。

验证：单测 `NumericConfigurationValidatorTest` **逐键**取「最小值 − 1」断言 fail-closed 且异常点明键名、取最小值本身放行、键缺省时门禁自身失败（3/3 绿）；真实探针对照——修复前：应用启动正常 + 第 1 次登录 429；修复后：**启动即失败**（`login-per-minute=0 < 1` 由 `NumericConfigurationValidator.validate` 抛出、BUILD FAILURE、8089 不可达）；server 全量 **859 测试 0 失败**（新增 3，5 skipped 为环境门控）。

### 2.31 账号导出的堆放大面（§2.26 留观项 ①：流式写出；2026-10-01 第三十八批，已随本批修复）

方法：把 §2.26 登记的「导出整份缓冲」从「推测」做成**实测**——用生产口径之外的受限堆（`-Xmx128m`，本机 MySQL 承载数据、避免数据本身占堆）逐步加量导出，定位失败阈值。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 整份缓冲的峰值堆 ≈ 2× 响应体，且响应体无上限 | 修复前实测（`-Xmx128m` + 本机 MySQL，账号逐轮补 100 条 × 63KB 职业资料）：**6.14MB 响应 → 200（304ms）**；**12.2MB 响应 → HTTP 500**，日志为 `java.lang.OutOfMemoryError: Java heap space`（`GlobalExceptionHandler` 记于 `http-nio-8089-exec-*`）；18.3MB 同样 500。机制：`exportAsJson` 先构造整份 JSON **String**，控制器再用 `StringHttpMessageConverter` 编码出等长 **byte[]**（两条拷贝都在堆上，另加序列化中间段） | **存在缺陷 → 修复**：改流式写出 |
| 序列化还在事务内 | `exportAsJson` 标了 `@Transactional(readOnly = true)` 且**序列化发生在方法体内**：连接在整个 JSON 生成期间被占用（限流按 IP，不同用户各自的配额互不约束，多用户并发导出会同时占连接 + 叠加堆） | **已修正**：事务边界只覆盖 DB 读取 |

修复动作：`AccountExportService` 拆成 `loadExportPayload(userId)`（`@Transactional(readOnly = true)`，只做 DB 读取且返回即提交）与 `writePayloadAsJson(payload, out)`（`ObjectMapper.writeValue(OutputStream)` 直接写流）；`AuthController.exportData` 改为 `void` + `HttpServletResponse`，显式设 `Content-Type: application/json;charset=UTF-8` 与 `Content-Disposition` 后写 `getOutputStream()`（刻意不用 `StreamingResponseBody`：无状态安全链在 ASYNC 派发时会重跑过滤器，存在 401 风险；同步写流既避开该风险，也让 MockMvc 断言保持同步）；`docs/05` §2.7 补「流式写出、无 Content-Length」口径。

验证：同一 `-Xmx128m` 堆、同一账号——修复前 12.2MB 即 OOM → 修复后 **18.41MB 连续 3 次 200**（399~566ms，`Transfer-Encoding: chunked`、无 `Content-Length`）；`curl` 落盘校验**文档内容不变**（合法 JSON、`formatVersion=1`、`careerMaterials=300`、`sourceText` 长度 63953），并实测首字节 **0.113s** / 总耗时 **0.216s**（不再等整份序列化完成才出首字节）；`AuthControllerIT` 导出两用例（401、聚合内容）无需改动即通过（13/13）——该 IT 走的正是「事务关闭后序列化」这条新路径，本身构成行为回归守卫；另新增静态防复发门禁 `ExportStreamingContractTest`（导出链路不得出现 `writeValueAsString`、写流方法不得被 `@Transactional` 覆盖、`loadExportPayload` 保持只读事务；**修复前红**——临时改回整份缓冲即失败——恢复后绿）；server 全量 **861 测试 0 失败**（新增 2 门禁用例，5 skipped 为环境门控）。

### 2.32 容器交付路径缺资源边界（2026-10-01 第三十九批扫描，已随本批修复）

方法：核对「同一宿主上多条交付路径的资源边界是否一致」——把 systemd 单元、生产 compose 与验收叠加 compose 的**内存上限、JVM 堆上限、日志上限**逐项对照（数值用真实命令取证，compose 用 `docker compose config` 解析校验）。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 生产容器路径**无任何内存上限** | `deploy/docker-compose.prod.yml` 的 8 个服务（edge/web/api/pdf-service/mysql/prometheus/grafana/alertmanager）都没有 `mem_limit`、没有 `deploy.resources.limits`；而同宿主的 `docker-compose.ip-test.yml` 给 4 个服务都写了 `mem_limit`（128/800/768/700m） | **存在缺陷 → 修复** |
| API 容器**无堆上限**，默认取 25% 宿主内存 | 生产 compose 的 api 无 `JAVA_TOOL_OPTIONS`（systemd 单元有 `-Xms128m -Xmx768m -XX:MaxMetaspaceSize=224m`；ip-test 叠加层有 `-Xmx512m`）。实测本机 `java -XX:+PrintFlagsFinal` 未指定 `-Xmx` 时 `MaxHeapSize = 4219469824` 字节 = 15.71GiB 的 25% ⇒ 3.6GiB 宿主上等价 **≈922MB**，高于该宿主为省内存刻意压到的 768m | 同上 |
| 容器日志**无上限**，与 systemd 路径不对称 | compose 全仓无 `logging`/`max-size`；而两个 systemd 单元都写明「日志走 journald（自带轮转）」、`docs/DEPLOYMENT_DIRECT.md` 亦记录该口径。docker 默认 json-file 驱动**不轮转**。（本地实测：正常请求不写日志——200 次匿名探针日志增量为 **0 字节**，增长来自异常全栈与 worker 日志） | 同上 |

修复动作：`docker-compose.prod.yml` 顶部新增 `x-logging` 锚点（json-file + `max-size 10m` × `max-file 5` = 每容器 ≤50MB）并由 8 个服务引用；为 8 个服务补 `mem_limit`（edge/web 128m、api **1200m**、pdf-service 768m（Chromium）、mysql 700m、prometheus 512m、grafana 384m、alertmanager 128m；口径与同宿主已实测的 ip-test 拓扑对齐）；api 服务补 `JAVA_TOOL_OPTIONS: -Xms128m -Xmx768m -XX:MaxMetaspaceSize=224m`（与 systemd 单元**同值**，`mem_limit` 比堆多 208m 余量）；新增静态门禁 `ComposeResourceBoundsContractTest`（生产 compose 每个服务必须有内存上限 + 有界日志；叠加层不得造出无界覆盖；api 堆上限必须等于 systemd 单元且 `mem_limit ≥ 堆 + 256m`）；`docs/08` 补「容器资源边界契约」。

验证：门禁对**修复前**的 compose **2/3 红**（生产 compose 无界 + api 无 `-Xmx`）→ 修复后 **3/3** 绿；`docker compose --env-file production.env.example -f docker-compose.prod.yml config --quiet` **exit 0**（锚点/合并解析通过，CI 的「Validate production Compose manifests」作业同样校验），叠加层 `-f prod -f ip-test` 合并后解析为 api **-Xmx512m / mem_limit 838860800 字节（800m）** 且继承基础文件的 `max-size 10m`——两条拓扑的资源边界自洽；server 全量 **864 测试 0 失败**（新增 3 门禁用例，5 skipped 为环境门控）。

### 2.33 PDF 服务监听范围未收敛（2026-10-01 第四十批扫描，已随本批修复）

方法：核对「文档写下的暴露面契约」与「运行时实际监听地址」是否一致——直连部署路径逐个服务对照 `docs/08` §3.3「PDF 服务只监听私有网络接口（127.0.0.1 或内网 IP），不直接暴露给浏览器」/ §10.1「MySQL、PDF 服务和管理端口不得暴露公网」，用真实 socket 事实（`Get-NetTCPConnection`）与非回环地址请求交叉取证。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| PDF 服务在直连路径**绑定所有接口** | `pdf-service/src/server.js` 用 `app.listen(port)` **不带 host** ⇒ Node 绑定所有接口。本机实测（`PDF_SERVICE_PORT=3011`）：`Get-NetTCPConnection -LocalPort 3011` → `LocalAddress = ::`；`curl --noproxy '*' http://192.168.5.4:3011/render` → **HTTP 401**（换句话说是真的在对外服务，而不是只监听回环） | **存在缺陷 → 修复** |
| 该暴露面**只由云安全组兜底** | `docs/DEPLOYMENT_DIRECT.md` §1 表格写「`0.0.0.0`（应用未提供绑定参数）」、§8 残留风险表写「目前依赖安全组封闭该端口；如需彻底收敛需改代码加 `server.address` 类参数」；`docs/reviews/2026-09-25-project-completeness-assessment.md` 亦因该项把「安全边界」评为 B | 同上 |
| 同链路**已有一致口径可复用** | api 单元早已因同类问题（`.env` 的 `SERVER_ADDRESS` 无占位符被静默忽略 → 监听 `*:8080`，实测内网与公网均可访问）改为单元内注入 `Environment=SERVER_ADDRESS=127.0.0.1`；pdf 单元却仍无对应项，两条交付路径的处理不对称 | 同上 |
| 收敛方向**不能一刀切** | 容器路径下 API 容器是在私有网络内经服务名访问本服务（`deploy/production.env.example` 的 `PDF_SERVICE_BASE_URL=http://pdf-service:3001`），把所有路径都收敛到回环会让导出整体不可用 | 需按交付路径分别取值 |

修复动作：`server.js` 支持 `PDF_SERVICE_HOST`（缺省仍**不指定 host**＝绑定所有接口，容器路径依赖该缺省；含空白的取值 fail-closed 启动失败），并把解析出的 host 真正传给 `app.listen`、启动日志改报**实际绑定地址**（原来固定打印 `localhost`，无法用于核对「改了没生效」）；`deploy/systemd/intelligent-resume-pdf.service` 注入 `Environment=PDF_SERVICE_HOST=127.0.0.1`（与 api 单元的 `SERVER_ADDRESS` 同口径，且必须是真实环境变量而非只写 `.env`）；新增 `pdf-service/test/bind-host.test.js`（2 例）与静态门禁 `PdfServiceBindScopeContractTest`（4 例：`server.js` 必须把 host 传给 `app.listen` 且不得保留无条件形式 / pdf 单元必须注入回环 / **容器 compose 不得收敛回环** / pdf 单元仍须保持 `NODE_ENV=production`）。

验证：**修复前后同一探针**——修复前 `LocalAddress=::`、非回环 `192.168.5.4:3011/render` → **401**；修复后（`PDF_SERVICE_HOST=127.0.0.1`，端口 3012）`LocalAddress=127.0.0.1`、回环 → 401、**非回环 → 连接被拒（HTTP 000 / exit 7）**；启动日志由固定 `localhost` 变为 `http://127.0.0.1:3012`。门禁红判定已做：把单元取值改成 `0.0.0.0` → `systemdPdfUnitBindsLoopback` **失败**（报错信息即门禁文案）→ 改回后 4/4 绿。回归：server 全量 **868 测试 0 失败**（本批 +4，5 skipped 为环境门控）；pdf-service **33 测试 0 失败**（本批 +2）。

### 2.34 告警链路既缺可用性告警、又与文档承诺的阈值不一致（2026-10-01 第四十一批扫描，已随本批修复）

方法：把 `docs/08` §7.1「建议阈值」与 §7.3 的描述**逐条**对照 `monitoring/prometheus/rules/intelligent-resume-alerts.yml` 的实际规则（名称、severity、阈值、`for`），并分析 Prometheus 在「抓取目标不可用」时的序列语义。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 现有 11 条规则**全部**建立在应用自身导出的指标上 | 逐条列举 `expr`：`resume_ai_*` / `resume_pdf_*` / `http_server_requests_*` 全部由 `AppObservability` 或应用导出 | 见下 |
| ⇒ **「整机不可用」零告警** | 应用不可用后抓取失败，这些序列随之消失：`rate()`/`histogram_quantile()` 在空区间返回**空向量**（不是 0）、gauge 走 staleness ⇒ 除法表达式为空、比较为空 ⇒ 无告警；`for: 10m` 也覆盖不到「没有数据」这种情况。唯一不受影响的是 Prometheus 自身产生的 `up`，而规则文件里**没有引用 `up`** | **存在缺陷 → 修复** |
| §7.1 承诺「API 健康检查：连续 3 次失败触发告警」 | 规则文件中无任何可用性规则（grep `up{` 零命中） | 同上 |
| §7.1 承诺「p95 超过 500ms 警告、2s 严重」「5xx 超过 1% 警告、5% 严重」 | 实际只有单级：`ApiLatencyCritical`（>2s）、`ApiServerErrorsHigh`（>1%）；500ms 与 5% 两级缺失（grep 阈值取值确认） | 同上 |
| §7.3 自述「评估 7 条告警规则」 | 实际 11 条（`- alert:` 计数 11） | 文档失真 → 同步 |
| 告警路由标签 | `alertmanager.yml` 只对 `severity="critical"` / `severity="warning"` 配了路由匹配器，未匹配的 severity 会落到默认接收器 `webhook`（critical 的邮件升级被静默跳过） | 需固化 |

修复动作：规则集由 11 → 15 条——新增 `ApiTargetDown`（`up{job="intelligent-resume-api"} == 0`，`for: 1m`，critical；`1m` ≈ 4 个抓取周期，满足「连续 3 次失败」并留 1 次抖动余量）、`ApiScrapeTargetMissing`（`absent(up{job=...})`，`for: 10m`，warning：目标被从抓取配置中移除时 `up` 序列整体消失，`== 0` 也不触发，这是「监控自身坏了」的唯一信号）、`ApiServerErrorsCritical`（5xx > 5%）、`ApiLatencyWarning`（p95 > 500ms）；`docs/08` §7.1 阈值清单改为与规则集一一对应（含可用性一条的存在理由）、§7.3 条数与门禁说明同步；新增静态门禁 `AlertRuleContractTest`（5 例：① 规则引用的指标必须在 `AppObservability` 注册——先按原名匹配、再剥离 Prometheus 后缀，兼容 `resume_ai_queue_oldest_pending_seconds` 这类以 `_seconds` 结尾的 gauge；② Grafana 面板同样校验；③ 可用性规则必须用**抓取配置里的 job_name** 且 `for ≥ 3 × scrape_interval`；④ 每条规则的 `severity` 必须能被 `alertmanager.yml` 的路由匹配器接住；⑤ §7.1 承诺的分级阈值必须在）。

验证：门禁对**修复前**的规则文件恰为 **2 红**（`availabilityAlertMatchesScrapeJobAndToleratesThreeFailures`、`documentedThresholdGradesAreImplemented`）、其余 3 例通过——红的是真实缺口，不是「规则数量不足」（该下限刻意设为 10，低于修复前的 11，避免红判定退化成计数失败）；修复后 **5/5** 绿。server 全量 **873 测试 0 失败**（本批 +5，5 skipped 为环境门控）。本轮同时复核并**排除**了一个曾于 `docs/reviews/2026-09-25-full-functional-verification.md` 被撤回的疑点：规则里的 `_total` 后缀（Counter）本身没有问题，Micrometer 零样本时不导出序列属取样假象——门禁因此按「注册名 + 后缀归一」判定，而不是按运行期抓取样例判定。

### 2.35 登出/删号不清理浏览器本地留存的简历数据（2026-10-01 第四十二批扫描，已随本批修复）

方法：把「浏览器本地存储里到底存了什么」列成清单（`grep localStorage|sessionStorage` 全量核对键名与写入点），再逐条对照 `docs/08` §9.6 的数据删除口径；用 Playwright 真实驱动编辑器**产生**草稿，然后走「退出登录」「删除账号」两条路径观察存储。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 编辑器把**完整简历 JSON**（姓名/联系方式/工作经历）写入 localStorage | `useResumeEditorDraft`：键 `intelligent-resume.editor-draft.<userId>.<resumeId>`，值含 `content`（简历 JSON 文本）+ `summary`；既有用例 `offers to restore a locally saved resume editor draft` 证明它确实落盘并用于崩溃恢复 | — |
| **退出登录不清理任何本地数据** | `auth.signOut()` 只 `setAccessToken(null)`；全仓无任何登出清理调用 | **存在缺陷 → 修复** |
| **删除账号同样不清理** | `AccountView.confirmDeleteAccount()` 只清会话并跳登录；而 `docs/08` §9.6 承诺「账户删除…完成清理」——用户删号后浏览器里仍留有一份完整简历副本 | 同上 |
| 同一清单内的其余用户数据 | `intelligent-resume.active-ai-task.<userId>`（待恢复任务 id）、sessionStorage 的 `resume-import-text`（**解析出的简历原文**）、`application-draft`（沟通文案草稿）、`interview-session-id` | 同上 |
| 用户无关的键 | `intelligent-resume.locale`（界面语言）、`resume-editor-sidebar-collapsed` 等偏好键 | 刻意保留 |

修复动作：新增 `web/src/utils/localUserData.ts`（`clearUserLocalData(userId)`——按用户前缀清理 `editor-draft.*` / `active-ai-task.*`，并清理 4 个标签页级用户数据键；storage 不可用时静默跳过；模块注释登记完整键清单，新增用户级键时必须登记）；`auth.signOut()` 与 `AccountView.confirmDeleteAccount()` 成功后调用；`docs/08` §9.6 补「浏览器本地残留同样属于用户数据」的口径。

验证：新增 `web/e2e/privacy-local-data.spec.ts`（2 例，登出 / 删号各一）——两条用例都先**真实产生**草稿（编辑器改姓名 → 等防抖落盘 → 断言草稿含 `Alice Chen`），再断言 4 个键全部清空；红判定已做：临时停用两处调用 → **2 例全红**（`expect(received).toBeNull()`，草稿仍在）→ 恢复后 **2 例绿**；web 全量 Playwright **146 passed / 0 failed**（本批 +2）；`npm run build`（含 `check:i18n`、`check:draft-fields`、`vue-tsc`）通过。

### 2.36 面试状态轮询的读放大：每次调用都全量读取本会话所有轮次（2026-10-01 第四十三批扫描，已随本批修复）

方法：`GET /api/interviews/{id}` 是前端在 `EVALUATING_ANSWER` 状态下 1/2/4/5s 轮询的热路径（评估一次 1~3 分钟 ⇒ 单轮可调用数十次）。用**真实 MySQL 5.7 探针**（本机库、真实 JWT）造两个同构会话——A 9 轮、B 1 轮，每轮 `answer_text` ≈8.4KB（与 8000 字符回答上限同量级）+ `feedback_json` ≈2KB——再用 `SHOW GLOBAL STATUS` 的 `Bytes_sent` / `Innodb_rows_read` 取每轮 20 次调用的增量。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 单次轮询读取量随已完成轮数**线性增长** | 修复前：A（9 轮）**99,636 字节/次**、19 行/次；B（1 轮）13,592 字节/次、3 行/次 | **存在缺陷 → 修复** |
| 读的是什么 | SQL 日志：`select ... from interview_record where session_id=? order by round_no, id`（**无 limit**）——把本会话所有轮次连同 `answer_text`(MEDIUMTEXT) 与 `feedback_json`(JSON) 全取回，只为用最后一条渲染 `lastEvaluation` | 同上 |
| 为何代价不止带宽 | 该端点还运行在**持有会话行写锁的事务内**（`getState` 用 `PESSIMISTIC_WRITE`，且可能把陈旧 attempt 标失败）：读取量越大持锁越久，与异步评估的落库事务（同样要拿该行锁）相互排队 | 同上 |
| 一处 O(n) 放大到 O(n²) | 第 n 轮评估期间的每次轮询都要读回 n 轮内容 ⇒ 单个会话累计读取量随轮数平方增长 | 同上 |

修复动作：`InterviewRecordRepository` 新增 `findFirstBySessionIdOrderByRoundNoDescIdAsc`（双键降序取 1 条，与「`round_no ASC, id ASC` 的末条」等价），`InterviewStateAssembler.buildStateResponse` 改用它；`findBySessionIdOrderByRoundNoAscIdAsc` 保留给报告、提示词上下文与账号导出（它们确实需要全部轮次）。

验证：**同一会话、同一探针**——修复后 A **13,636 字节/次**、B 13,592 字节/次，与已完成轮数**无关**（9 轮会话单次取数 99.6KB → 13.6KB，约 7.3×）；SQL 变为 `... order by round_no desc, id limit ?`。测试：`InterviewStateAssemblerTest` 两条用例改为断言末轮查询，并新增 `buildStateResponse_neverLoadsAllRecords`（`verify never` 禁止全量读取；红判定已做——把实现改回全量读取时该断言失败，失败点是 `never()` 本身而非桩未命中）；`InterviewRecordOrderingIT` 新增倒序写入下取末轮的用例；server 全量 **875 测试 0 失败**（本批 +2，5 skipped 为环境门控）；`docs/05` §9.4 补该端点读取契约；探针数据（会话/轮次/JD 行）用完即删。**留观**：同一端点每次轮询仍执行 `count(*)` 统计已完成轮数（`Innodb_rows_read` 仍随轮数增长：20 行/次 vs 4 行/次），但它是**索引范围计数**、不传输行内容（`Bytes_sent` 已与轮数无关），若后续需要彻底常数化，需在会话行上加 `completed_round_count` 派生列（含迁移与写路径同步），收益小于本批故未做。

### 2.37 面试引用已归档简历版本时报「不存在」而非可恢复的归档提示（2026-10-01 第四十四批扫描，已随本批修复）

方法：核对「归档版本消费契约」在面试模块的落点——`InterviewPromptContextAssembler.findOwnedResumeVersion` 被 `/start` 的来源校验与**进行中每轮评估**的简历上下文组装（`appendResumeContext`）共同调用；逐项对照 ATS（`AtsService.ownedVersion`）/ 评分（`ScoringService.score`）/ 导出 / 投递的同族实现（均在归属校验后按 `deletedAt` 抛 409 + 可操作文案）。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 归档版本被折叠成「不存在」 | `findOwnedResumeVersion` 用 `findById(versionId).filter(deletedAt == null).orElseThrow(notFound("简历版本不存在"))`——归档状态与「查无此版本」走同一 404 分支 | **存在缺陷 → 修复** |
| 影响面不止发起 | `appendResumeContext` 被 `buildEvaluationContext`/`buildFirstQuestionContext` 调用 ⇒ 会话进行中每轮评估都重新解析简历版本；而 `ResumeVersionService.archive` 仅禁止归档**当前**版本，用户先新建/恢复使旧版本不再是 current 即可归档。此后 `/answer` 的评估以 `markEvaluationFailed(..., "NOT_FOUND", "简历版本不存在", retryable=true)` 落到会话 | 用户在面试中途看到**原因错误、且恢复版本也无法消除**的 AI 失败提示 |
| 跨模块口径不一致 | ATS / 评分 / 导出 / 投递均为 `409` +「该简历版本已归档，请先恢复后再发起…」 | 违反 batch 15「同一版本的消费结论在各模块一致且可操作」 |
| 现有测试固化了旧语义 | `InterviewPromptContextAssemblerTest.findOwnedResumeVersion_deleted` 断言 `NOT_FOUND` +「简历版本不存在」 | 需随语义同步修改 |

修复动作：`findOwnedResumeVersion` 改为「`findById` 找不到 → `40401`（简历版本不存在）；**归属校验前置于归档判定**（`resumeRepository.findByIdAndUserId` 不通过仍以 `40401` 收口，避免用归档状态反推他人版本是否存在）；其后若 `deletedAt != null` → `40901` +「该简历版本已归档，请先恢复后再继续」」。`BusinessException` 不属于 `AiInvocationException` ⇒ `operationSupport.isRetryable` 仍返回真，恢复版本后重试即可。

验证：`findOwnedResumeVersion_deleted` 改断言 `40901` + 新文案；新增「归档且简历属他人 → `40401`（不泄露归档状态）」「`appendResumeContext` 遇归档版本 → `40901`」两例；**红判定已做**——改后用例在修复前恰为 **2 红**（`expected: <CONFLICT> but was: <NOT_FOUND>`），修复后定向 **15/15 绿**；server 全量 **877 测试 0 失败**（本批 +2，5 skipped 为环境门控）；`docs/05` §5.4 归档消费方列表补入「AI 面试」、§9.4 补该口径；CI + Functional Regression 双绿（workflow run 36788640318 / 36788640285，head 22c48f0，复核结论 success）。

### 2.38 AI 重试仍在请求线程内同步等待评估：重试按钮必然超时（2026-10-01 第四十五批扫描，已随本批修复）

方法：核对「AI 异步改造」的覆盖面——`InterviewEvaluationConfig` 的类注释正是为 `/answer` 的同步等待缺陷而写（实测 4 轮 102.3 / 76.4 / 110.0 / 145.9s，平均 108.7s，而前端该接口超时 60s ⇒ 4 轮全部超时），并自述「后端已有 attempt 的 PROCESSING 状态、陈旧超时判定与 `/ai/retry` 重试入口，即『异步评估』本就是半成品设计」；据此逐条核对仍走 AI 的入口是否都在请求线程外执行。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 重试路径同步等待同一次评估 | `InterviewRetryService.retryAi` 的 Phase 2 在请求线程内直接 `interviewAiService.evaluateAnswer(...)` / `operationSupport.callAiForFirstQuestion(...)`，与 `/answer` 修复前的形态逐行同源 | **存在缺陷 → 修复** |
| 前端超时不变 | `web/src/api/interview.ts`：`/ai/retry` 超时 `60_000`，与实测 108.7s 的评估耗时矛盾（`/answer` 的 60s 之所以无害，是因为它已秒回） | 同上 |
| 用户可见后果 | 点「重试」→ 前端 60s 超时报错；服务端继续评估并在约 108s 后把结果落库（会话离开 `AI_ACTION_REQUIRED`）——重试按钮形同虚设，且用户看到的失败与服务端事实相反 | 同上 |
| IT 固化了同步语义 | `InterviewControllerIT.retryConsumesTheSixtiethCallAndRejectsTheSixtyFirst` 断言首次重试即返回 `AI_ACTION_REQUIRED`（同步终态） | 需随语义修改 |

修复动作：`retryAi` 在 TX1（校验 / 计数 / 置 PROCESSING）后把 AI 段提交到与 `/answer` 相同的 `interviewEvaluationExecutor`，立即返回 PROCESSING（首题重试 `GENERATING_QUESTION`、评估重试 `EVALUATING_ANSWER`）；前端在 AI 加载态本就会轮询 `GET /api/interviews/{id}`（`InterviewView.scheduleStatePoll`）⇒ **前端零改动**；执行器队列满时以 `200` + `aiFailure`（`QUEUE_REJECTED`，可重试）快速失败；`generation`/`isCurrentRetry` 的 stale 丢弃判定与「任何异常都落 attempt」的兜底整体保留（抽出 `runRetry` / `markRetryFailed`）。

验证：新增 `InterviewRetryServiceTest`（4 例，用可控执行器断言「AI 被调用之前就返回 `EVALUATING_ANSWER`」「后台落库并置 attempt 成功」「首题重试后台推进到 `AWAITING_ANSWER`」「队列满标记 `QUEUE_REJECTED` 可重试」）；**红判定已做**——修复前 4 例全红（返回的是同步终态 `AWAITING_ANSWER`、执行器 `pending` 为空）；`InterviewControllerIT` 第 17 例改为异步契约（首次重试断言 `GENERATING_QUESTION` 且已消耗第 60 次调用，有界等待后台失败后断言第 61 次 `RATE_LIMITED`）；server 全量 **881 测试 0 失败**（本批 +4，5 skipped 为环境门控）；`docs/05` §9.4 补该端点异步契约、ADR-010 的「同步交互」表述更正为后台执行器 + 轮询；CI + Functional Regression 双绿（workflow run 36789967338 / 36789967155，head 661227d，复核结论 success）。

### 2.39 决策登记册把已交付的 worker 分组领取标为 OPEN，且该保护本身无门禁可被静默撤销（2026-10-01 第四十六批扫描，已随本批修复）

方法：把 `docs/decisions/OPEN-DECISIONS.md`（自述「只追加 + 就地关闭」的唯一跟踪口径）里**每条 OPEN** 逐条与现状对账——条目 8（2026-09-23 plans/003 I2「AI worker 按 id 串行领取，长任务会饿死短任务」）标 `OPEN · waiting-on-external-condition`，而 `docs/reviews/2026-09-26-optimization-opportunities.md` ④ 已把 PA-1「worker 分组领取」记为「✅ **完成**（HEAVY/LIGHT 分组 + 各自独立线程池与并发额度；……回归 708 测试 0 失败）」。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 登记册与事实不符 | 条目 8 仍为 `OPEN`，`Blocked By` 仍写「需先确认容量预期……选定 A/B 后」，而方案 A 早已选定并上线 | **存在缺陷 → 修复** |
| 分组领取确已落地 | `AiTaskCapabilityRegistry.Group{HEAVY,LIGHT}` 单点登记每个任务类型的分组；`DatabaseTaskWorker` 按分组各领各执行，两组持有独立线程池与独立并发额度（`app.ai.worker.heavy-concurrency` / `light-concurrency`，默认各 2）；领取过滤真正下推 SQL（`AiTaskRepository.claimableTasksByTypes` 的 `AND task_type IN (:taskTypes)`，不是截断后再过滤） | 条目应关闭 |
| 该保护本身无有效门禁 | 把 `INLINE_OPTIMIZE`（正是条目实测被饿死的「内联润色」）改回 HEAVY 后，`DatabaseTaskWorkerTest` 三条**仍全绿**——桩以 `typesIn(group)` 匹配，分组为空也照旧通过 ⇒ 饿死可被静默撤销 | 补门禁 |

修复动作：按登记册自身规则**就地关闭**条目 8（保留原文，Status 改 `RESOLVED (2026-10-01)`，Resolution 记录落地形态、守护测试与残留）；新增 `AiTaskCapabilityRegistryTest.secondScaleTasksStayInTheLightGroup`，把实测被饿死的秒级类型（`INLINE_OPTIMIZE` / `RESUME_OPTIMIZE` / `INTERVIEW_COACH`）钉在 LIGHT 分组，并要求 LIGHT 分组非空。

验证：**红判定已做**——临时把 `INLINE_OPTIMIZE` 翻回 HEAVY 时，新用例失败（`expected: <LIGHT> but was: <HEAVY>`）而 `DatabaseTaskWorkerTest` **3/3 仍通过**，正是「无门禁」的实证；恢复后定向 7/7 绿、server 全量 **882 测试 0 失败**（本批 +1，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36790808953 / 36790808967，head bb8eec5，复核结论 success）。

### 2.40 归档版本消费口径在导出/投递/沟通三处仍为 404「不存在」（2026-10-01 第四十七批扫描，已随本批修复）

方法：以 `docs/05` §5.4 的**书面契约**为准（「ATS 体检 / PDF 导出 / 投递关联 / 沟通 / 规则评分 / AI 面试会拒绝（`40901`，需先恢复）」），逐条核对每个消费方的归档分支**实际返回什么错误码**——即第四十四批（AI 面试）之外的剩余部分。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 契约与实现分裂 | §5.4 声明六类消费方均 `40901`，实测只有 ATS（`AtsService.ownedVersion`）与规则评分（`ScoringService.score`）如此 | **存在缺陷 → 修复** |
| PDF 导出 | `ExportService.create` 的归档分支 `throw NOT_FOUND「简历版本不存在」` | 同上 |
| 投递关联 | `ApplicationService.validateReferences` 把归属与归档合并成一个 `if` → `NOT_FOUND`，无法区分「他人的」与「已归档的」 | 同上 |
| 沟通 | `CommunicationService.ownedResumeVersion` 用 `findByIdAndCreatedByAndDeletedAtIsNull`（归档即查空）→ `NOT_FOUND`；`CommunicationTemplateService.preview` 同形 | 同上 |
| 现有测试固化旧语义 | `ExportServiceTest.create_deletedVersion_notFound`、`ApplicationServiceTest.create_deletedResumeVersion_throwsNotFound` 均断言 `NOT_FOUND`；沟通侧无归档用例 | 需随语义修改 |

修复动作：四处统一为「`findById` 找不到 → `40401`；**归属校验先于归档判定**（他人版本仍 `40401`，不泄露归档状态）→ `deletedAt != null` → `40901` + 可操作文案」，文案与同族一致：导出「该简历版本已归档，请先恢复后再发起导出」、投递「……再发起投递」、沟通「……再发起沟通」（同步草稿 / AI 任务 / 模板预览共用同一 helper）。事务边界与其余逻辑不变。

验证：**红判定已做**——修复前三处分别 `expected: <CONFLICT> but was: <NOT_FOUND>`、`expected: <409> but was: <404>`；导出/投递各改一条并新增「归档且属他人 → `404`（不泄露）」，`CommunicationControllerIT` 新增 `@Order(8)`（建两版 → 切当前版 → 归档首版 → 断言 `/generate`、`/ai-generate`、`/templates/{id}/preview` 三处 `40901` + 文案），`CommunicationTemplateServiceTest` 同步桩并新增归档/他人两例；定向 **60/60** 绿，server 全量 **887 测试 0 失败**（本批 +5，5 skipped 为环境门控）；`docs/05` §5.4 补「可操作文案 + 归属先于归档判定」；CI + Functional Regression 双绿（workflow run 36791728644 / 36791728613，head 9bcdfa8，复核结论 success）。

### 2.41 logout-all 的「其它设备立即失效」与实测不符，且该端点无 HTTP 层门禁（2026-10-01 第四十八批扫描，已随本批修复）

方法：以 `docs/05` 的**书面安全承诺**为准逐条核对，重点是同文档中语气相同而对象不同的两处「立即失效」——§2.7 删号 vs §2.9 全端登出。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 措辞与实测不符 | §2.9 写「撤销当前用户全部刷新会话（**其它设备立即失效**）」；`AuthControllerIT` 临时探针实测：三台设备刷新全部 `40101`，而设备 B 的 access token 仍可 `GET /api/auth/me` → **HTTP 200** | **存在缺陷 → 修复** |
| 机制上不可能「立即」 | `AuthService.logoutAll` 只置 `auth_session.revoked_at`；`TokenService.issueAccessToken` 只写 sub/username/iat/exp（**access token 不含会话标识**）；`JwtAuthenticationFilter` 仅校验签名/有效期 + `ActiveUserCache.isActive(userId)`，`AuthSessionRepository` **不在请求链路上** ⇒ 撤销会话无法影响已签发 JWT，其存活上限即 `app.jwt.access-token-ttl-seconds`（默认 **3600s**） | 同上 |
| 同文档「一真一假」 | §2.7 删号写「已签发的 access token 立即失效」——**为真**：删号把用户置 `DISABLED`，鉴权按用户状态即时拒绝（`AuthControllerIT.deleteAccount_invalidatesExistingAccessToken` 守护）。两处语气相同、结论相反 | 需按实测分别措辞 |
| 该端点此前零 HTTP 层覆盖 | `AuthControllerIT` 无 logout-all 用例；`AuthServiceTest.logout_allSessions_revokesAll` 只断言行字段 `revokeReason`，未验证刷新真的被拒 | 补门禁 |

修复动作：① §2.9 更正为「刷新会话立即撤销（`refresh` → `40101`）、access token ≤ TTL 内仍有效」，并写明与删号的差别、指向登记项；② `AuthControllerIT` 新增 `@Order(11)`——三台设备各登录，logout-all 后逐个断言 refresh `40101` 并校验失效 cookie；③ `OPEN-DECISIONS` 新增登记项（A 维持现状 / B 令牌水位 + 缓存比较〔受 30s 缓存 TTL 与时钟偏移约束，需宽限窗口，否则可能误踢新签发 token〕/ C 以删号兜底），待产品口径决定丢失设备场景的要求。

验证：临时探针给出实测边界（三设备 refresh 全 `40101` vs access token `200`），**取证后即删**，未把该边界固化为断言（避免把缺陷固化进测试）；恢复后定向 `AuthControllerIT` **11/11** 绿、server 全量 **888 测试 0 失败**（本批 +1，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36792721390 / 36792721141，head 99af773，复核结论 success）。

### 2.42 留存期在规范文档与实现之间漂移（30 天 vs 90 天），且一组生命周期承诺根本没有实现（2026-10-01 第四十九批扫描，已随本批修复）

方法：新增静态门禁 `RetentionPolicyContractTest`（**文档声明 vs `application.yml` 默认值**）——此前的 `ConfigFallbackContractTest` 只覆盖「代码兜底 vs yml 默认」，不覆盖「规范文档 vs yml」；再用一次全仓「留存/清理作业」普查核对每条生命周期承诺是否有实现。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| AI 任务留存：三份规范写 30 天，实现是 90 天 | 门禁首跑即红：`docs/04` §7.1 生命周期表、`docs/07` §5.10、`docs/08` §9.6 均声明 **30 天**，而 `app.ai.task.retention-days: ${AI_TASK_RETENTION_DAYS:90}` 为 **90 天**（第十三批经用户确认「90 天压缩快照」并落地，`docs/05` §7.5 亦写 90）⇒ 同一事实呈三种数值：报告记「已确认 90」、规范写 30、实现是 90 | **存在缺陷 → 修复**（规范留存期是面向用户/合规的承诺，漂移即虚假承诺） |
| 一组承诺无任何实现 | 全仓 `@Scheduled` **仅三处**（AI 任务留存、PDF 文件过期、导出过期）；无 `deleteByDeletedAt*` / 硬删仓储方法；`V1~V34` 全部迁移中**无** `account_deletion_job`（`docs/04` §7.2 的字段表实为设计草案）；`AuthService.deleteAccount` 为**同步**路径 ⇒「职业资料/JD/简历主记录与投递/面试数据：软删 30 天后 7 天内硬删」「账户 7 天撤销窗口 + 30 天内清理」均未实现 | 按现状标注 + 登记决策 |
| 既有测试无法覆盖 | 全部契约测试均不读规范文档，故此类「文档 vs 实现」漂移此前无任何守卫 | 补门禁 |

修复动作：① 三处 30 → **90 天**并注明依据；② `docs/04` §7.1 生命周期表逐行标注「已实现 / 计划中，尚未实现」并为账户行按下实际行为（立即停用、无撤销窗口）更正、§7.2 标注「设计草案，尚未建表」，`docs/07` §5.10、`docs/08` §9.6 同步；③ `OPEN-DECISIONS` 登记 A/B/C（A 分级清扫含跨表级联 / B 只清无引用者 / C 维持现状并按现状改承诺），说明硬删裁剪口径依赖 §7.1 例外条款「被历史记录引用的部分只保留最小不可编辑快照」的定义，属产品/合规决策——**不擅自实施破坏性级联删除**。

验证：门禁**红判定已做**（首次运行 2/2 红：三处「声明 30 天，而 application.yml 默认 90 天」+ 硬删行缺「计划中」标注），修复后定向 **2/2** 绿；门禁设计为「每个（文档，正则）对必须命中并捕获天数」，文档改写/删除该句即报红，迫使承诺被显式维护而非悄悄消失；server 全量 **890 测试 0 失败**（本批 +2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36793605842 / 36793605938，head 54aa057，复核结论 success）。

### 2.43 归档版本的可操作文案对用户不可见：前端按码映射文案，40901 给出的是「刷新重试」（2026-10-01 第五十批扫描，已随本批修复）

方法：顺着第四十七批的修复方向往**闭环处**再走一步——服务端已返回「该简历版本已归档，请先恢复」这类可操作文案，但**前端按设计不透传服务端 message**（双语界面走错误码映射，源自 2026-09-26 盘点报告 TC-3），因此要验证「文案是否真能到达用户」。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 可操作文案被前端丢弃 | `errorMessage.ts` 的 `resolveApiError` 只查 `ERROR_CODE_KEYS`，注释明写「有意**不透传服务端 message**——服务端 message 仅中文，直接透传会破坏 en-US 界面」。第四十七批把归档拒绝统一为 `40901` 后，`40901 → errors.conflict →`「内容已更新或状态已变化，**请刷新后重试**」——对归档场景是**错误的处置动作**：刷新不会让归档版本可用，用户须先去「版本历史」恢复该版本 | **存在缺陷 → 修复** |
| 并非本批新引入，ATS/评分自 batch 15 起同样如此 | ATS/评分最早用 `40901` 承载归档拒绝，其专属文案同样从未展示给用户；第四十七批只是把同一问题带到了导出/投递/沟通 | 一并收口 |
| 面试路径另有盲区 | `InterviewView` 只渲染固定 i18n 文案（`aiFailedTitle`/`aiFailedDescription`）与按 `retryable`/`reauthorizationRequired` 的按钮，**完全不读 `aiFailure.messageCode`** ⇒ 第四十四批的可操作文案同样不可见 | 补按码分支 |
| 新码漏登记会静默变 500 | 加入 `VERSION_ARCHIVED` 后 `ScoringControllerIT`/`CommunicationControllerIT` 实测**由 409 变 500**：`GlobalExceptionHandler.statusFor` 的 switch 带 `default -> HttpStatus.INTERNAL_SERVER_ERROR`，新码未登记即静默降级 | 去掉 `default` 做编译期穷尽检查 + 补行为用例 |

修复动作：① 新增业务码 `VERSION_ARCHIVED(40902)`，七处消费方（ATS / 规则评分 / PDF 导出 / 投递 / 沟通同步草稿与 AI 任务 / 沟通模板预览 / AI 面试上下文）统一改抛该码——语义分工为「**消费类拒绝**用 40902（处置=先恢复再重试）／**版本管理类冲突**（归档版本设为当前版本、乐观锁、状态机）仍用 40901（处置=刷新或换版本）」；② 前端登记 `40902 → errors.versionArchived` 并给出 zh/en 双语可操作文案（「请先在『版本历史』中恢复后再试」）；③ `InterviewView` 依 `aiFailure.messageCode === 'VERSION_ARCHIVED'` 在失败面板给出「先恢复该版本再重试」专属指引；④ `statusFor` 去 `default` + 新增遍历全部业务码的非 5xx 用例。**注**：②对导出/投递/沟通三页当时**尚未生效**（那三处用固定兜底串，见 §2.44 第五十一批收口）。

验证：**红判定已做**——服务端改前测试 **7 红**（`expected: <VERSION_ARCHIVED> but was: <CONFLICT>`、`expected:<40902> but was:<40901>`，且落地过程中真实撞到新码未映射导致的 **500**）；web 单测红（`40902` 未登记，`actual: undefined`）；e2e 首例红（临时把 `v-if` 置 false 后 `.ai-failure-archive-hint` 不可见，而「非归档失败不得出现该指引」第二例仍绿）。修复后：server 定向 26/26、全量 **891 测试 0 失败**（本批 +1，5 skipped 为环境门控）；web 单测 **6/6**、`npm run build` 通过、Playwright **148 passed / 6 skipped / 0 failed**（本批 +2）；`docs/05` §1.3 码表补 `40902`、§5.4/§8.3 区分 40902 与 40901、§9.4 同步；CI + Functional Regression 双绿（workflow run 36795230998 / 36795230994，head 90fdf8a，复核结论 success）。

### 2.44 第五十批的 40902 指引未到达三个真实消费入口：三处视图用固定兜底串吞掉了业务码（2026-10-01 第五十一批扫描，已随本批修复）

方法：对第五十批的结论做**闭环复核**——「前端登记了 `40902 → errors.versionArchived`」不等于「用户能看到它」；登记表只在调用 `resolveApiError` 的视图生效，因此逐个核对**归档拒绝真正发生在哪些页面**，看这些页面的错误处理是否走登记表。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 三处入口用固定串吞掉 40902 | ① `ResumeDetailView.exportPdf`（PDF 导出创建）→ `t('resumeDetail.exportError')`；② `ApplicationsView.save`（新建/编辑投递，服务端 `validateReferences` → 40902）→ `t('applications.saveError')`；③ `CommunicationView` 三处（`generateTemplate` 同步草稿、`handleCreateError` AI 任务〔原只区分 40302/42901，其余一律 `aiGenerateError`〕、`previewTemplate` 模板预览）→ 固定串 | **存在缺陷 → 修复**（第五十批对这些入口实际无效） |
| 影响面 | 归档版本被导出/投递/沟通拒绝时，用户看到的仍是「导出失败/保存失败/生成失败」，看不到唯一正确动作「先去版本历史恢复该版本」 | 同上 |
| 只有部分视图走登记表 | 走 `resolveApiError` 的是 Account / Interview / GenerationWorkbench / GenerationConfirm / MaterialSelectionConfirm / MaterialResumeGeneration / ResumeEditor / AiConsent；导出/投递/沟通三页此前均不在列 | 需逐页对齐 |

修复动作：四处 `catch` 改为 `resolveApiError(cause, <原兜底键>)`——**已登记业务码优先、无码回落原兜底串**，故既有文案不回归，仅新增 `40902`（及未来新码）的双语可操作指引；`CommunicationView` 刻意保留 40302（授权）/42901（配额）的**页内专属**文案（比通用 `errors.*` 更贴合该页语境）。

验证：**红判定已做**——`archive-guidance.spec.ts`（3 例，逐入口路由 mock `40902`）修复前 **3 例全红**，失败信息直接给出被吞掉的原串（`Template generation failed. Check the selected resources.` / `Unable to save this application. Review the selected job and resume version.`），证明缺陷是「文案被吞」而非选择器问题；修复后 3/3 绿；web 单测 **6/6**、`npm run build`（含 `check:i18n`/`check:draft-fields`/`vue-tsc`）通过、Playwright **151 passed / 6 skipped / 0 failed**（本批 +3）；CI **success**（workflow run 36796583208，head cfcfd5b，含 Server tests / Web build and browser tests / PDF service checks；Functional Regression 因本次仅改动 `web/**`、不命中其 `paths` 过滤器而未触发）。

### 2.45 PDF 输出上限「声明即虚构」：`app.pdf.max-output-bytes` 四处声明却零消费点（2026-10-01 第五十二批扫描，已随本批修复）

方法：对 §2.8「资源边界与交付链」与 `docs/03` §9.7「每个任务设置渲染超时、最大输入和输出大小」逐项核对——把「**声明的**上限」与「**真正执行的**判据」分开看，检查每个体积/时限旋钮是否真有人读、真有人比。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 四处声明、零消费 | `application.yml:330`、`application-local-h2.yml:46`、`application-test.yml`、`server/.env.example:44` 均声明 `app.pdf.max-output-bytes`（默认 10MB）；而 `PdfServiceClient` 只消费 `max-input-bytes`（`:105`「导出数据超出最大允许大小」），**`maxOutputBytes` 在任何 `main/` 路径下零命中** | **存在缺陷 → 修复**（声明即虚构：渲染返回多大就落盘多大、可下载） |
| 被当作既有能力引用 | `UploadPathContractTest` 与 `web/src/api/export.ts` 的注释都把它当「导出下载的响应体上限」；pdf-service 侧亦无输出大小检查（只有请求体 ≤1MB 与页面并发上限） | 同一旋钮被三处引用却无人执行 |
| 输入/输出判据不对称 | 输入侧 `max-input-bytes` 有消费点（`:105` 按序列化字节判定）；输出侧对称位置**空**，而 `docs/03` §9.7 明文要求两侧都要 | 不对称 |

修复动作：
- `PdfServiceClient` 在渲染结果**落盘前**按 `max-output-bytes` 判定，超限记 `PdfFailureCategory.OUTPUT_TOO_LARGE` 观测并抛 `PDF_FAILURE`「导出文件超出最大允许大小（n > limit bytes）」——文件不落私有存储、不可下载；
- pdf-service 新增 `PDF_SERVICE_MAX_OUTPUT_BYTES`（默认 10MB）**第二道闸**：超限不把结果经 HTTP 全量回传（省内存与带宽），也覆盖「调用方把 API 上限调高」的情况；超限返回 `500` + `50003` 且不写回结果；非法取值启动即失败（与既有容量/超时配置同纪律）；
- `PdfFailureCategory` 新增 `OUTPUT_TOO_LARGE`；分类器补两条**中文**文案规则——顺带修正：中文输入超限文案此前会落入 `RENDER`（分类器只认英文 `input`/`too large`）；
- 新增跨运行时门禁 `PdfOutputBoundContractTest`（4 断言：yml 必须声明默认值、应用侧消费点存在且 `@Value` 兜底与 yml 一致、pdf-service 真正用于比较、**service 上限 ≥ API 上限**——API 上限是用户可见契约，service 更小会让合法导出在服务端被拒）；新增单测 `PdfServiceClientOutputBoundTest`（JDK `HttpServer` 作桩：超限拒绝 + 恰好等于上限放行，边界含等于）；
- 文档同步：`docs/03` §9.7、`docs/05` §11.1、`docs/08` §8、`pdf-service/README.md`、`server/.env.example`。

验证：**红判定已做**——临时移除 `PdfServiceClient` 的输出判定块后，`PdfOutputBoundContractTest.apiDeclaresAndConsumesOutputBound` 与 `PdfServiceClientOutputBoundTest.oversizedRenderIsRejected` **各 1 红**（`Expected BusinessException to be thrown, but nothing was thrown`），随后按 md5 校验完整恢复；修复后定向 **10/10 绿**（日志实证消费点执行：`PDF render output too large: 1025 bytes > limit 1024`）；server 全量 **896 测试 0 失败**（本批 +6，5 skipped 为环境门控）；pdf-service `npm test` 本地 **27/34**——7 项 `spawnSync` 用例受本机执行环境限制（`spawnSync node.exe`/`cmd` 均报 `EBUSY`，与代码无关）无法运行，已用**直接执行**验证同一命令的退出码与 stderr（`PDF_SERVICE_MAX_OUTPUT_BYTES=0` → `EXIT=1` + `PDF_SERVICE_MAX_OUTPUT_BYTES must be an integer >= 1`），**CI 侧这 7 项全部通过**；CI + Functional Regression 双绿（workflow run 36842479281 / 36842479307，head 114a1fb，复核结论 success）。

### 2.46 配置的第三种失效形态：`@ConfigurationProperties` 兜底漂移 + 声明却无消费点的死键（2026-10-01 第五十三批扫描，已随本批修复）

方法：把 `application.yml` 的**全部 `app.*` 叶子键**（97 个）与代码消费点做全量对账——把「**声明**」与「**消费**」分开看。既有门禁体系只覆盖前两种形态（`@Value` 兜底漂移、文档承诺漂移），**第三种完全无守护**。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 绑定类字段默认值漂移 | `AiTaskWorkerProperties.leaseSeconds` 字段默认 **180**，yml 为 `${AI_WORKER_LEASE_S:660}`——第一批 #60 把租约 180→660 时**只改了 yml、漏改绑定类**。该键在 yml 存在时以 yml 为准，仅 yml 缺键时才用字段默认值 | **存在缺陷 → 修复**（配置缺省时租约减半 → 长任务心跳失联后被接管重跑、重复调用 provider） |
| 门禁盲区 | `ConfigFallbackContractTest` 只扫 `@Value("${k:f}")`，`@ConfigurationProperties` 绑定路径**零覆盖** —— 该漂移正是从此漏出 | 需扩展门禁 |
| 死键（声明零消费） | ① `app.ai.quota.JOB_MATERIAL_SELECTION: 30` —— `AiQuotaService` 把选材与生成**都映射到** `JOB_GENERATION` 的值（共用额度），该键改它不生效；② `app.job.parser.rule-version: "v1.0.0"` —— 解析结果不含版本、`JdParserProperties` 无该属性（Spring 静默忽略未知键，故它一直"看着像生效"） | **存在缺陷 → 移除**（改它不生效却让运维以为可配） |
| 尚未实现（登记） | `app.job.jd-text.min-length`（T05 设计为「太短视为空」，解析器未实现）、`app.ai.confirmation.*` 三项（T08 未接入；其中 `max-confirmed-items` 的 200 已由 `ConfirmRequest` 的 `@Size(max = 200)` 硬编码承载，**非**「条数无上限」） | 登记 `OPEN-DECISIONS`，不擅自改行为 |

修复动作：
- `AiTaskWorkerProperties.leaseSeconds` 默认 180 → **660**（附注释：必须 > 链总预算 600s，并说明与 yml 的关系）；
- 移除上述两个死键，在 yml 留注释说明原委（尤其「选材与生成共用额度」的语义）；
- `ConfigFallbackContractTest` 新增用例 `configurationPropertiesDefaultsMatchYaml`：扫描全部 `@ConfigurationProperties` 类的**数值/布尔**字段默认值，与 yml 同路径默认值比对（String/集合跳过——转义与字面差异会误报）；
- **新增 `ConfigConsumerContractTest`**：`app.*` 标量键必须有消费点（main 代码出现点号路径字面量，或落在某个绑定前缀的字段下、含 Map 元素按首段 camel 化匹配）；未实现项须显式登记 `KNOWN_UNCONSUMED` 白名单并附理由，且白名单项一旦获得消费点即要求移出（防白名单腐化）；含扫描规模自检（`app.*` ≥70、已消费 ≥65），防解析失效后空转；
- `docs/08` 配置原则新增「配置的三种失效形态都必须有门禁」条目；两项未实现键登记进 `OPEN-DECISIONS.md`。

验证：**红判定已做**——恢复 `leaseSeconds=180` 并加回 `JOB_MATERIAL_SELECTION` 后，`configurationPropertiesDefaultsMatchYaml` 报「`app.ai.worker.lease-seconds` → 字段默认 [180] vs yml 默认 [660]」、`everyAppKeyHasConsumer` 报「`app.ai.quota.JOB_MATERIAL_SELECTION` 无消费点」，**两处各 1 红且失败信息点明键名**，随后按 md5 校验完整恢复；修复后定向 3/3 绿；server 全量 **898 测试 0 失败**（本批 +2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36844601848 / 36844601794，head 16edcb6，复核结论 success）。

### 2.47 环境变量样例（配置的第三个来源）既有死键又有值漂移（2026-10-01 第五十四批扫描，已随本批修复）

方法：把「声明的配置」再扩一个来源 —— `server/.env.example`。前两批覆盖了代码 `@Value` 兜底与 yml 默认值，**样例本身从未被核对**，而它恰恰是「照抄即生效」的入口。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 样例值漂移 | `server/.env.example`（原 `:30`）`AI_WORKER_LEASE_S=60`，而 yml 是 `${AI_WORKER_LEASE_S:660}` —— 照抄样例即把租约压到链总预算（600s）之下，长任务心跳失联后被接管重跑（第一批 #60 修掉的问题，当时只改了 yml） | **存在缺陷 → 修复** |
| 样例死键 | `AI_MOCK_FAIL_RATE` / `AI_MOCK_LATENCY_MS`（原 `:33-34`）：全仓**零消费点**（Mock 模型早已不是正常功能路径），保留会让运维误以为可开启故障模拟 | **存在缺陷 → 移除** |
| 反向覆盖 | 精确对账 56 个声明键（含 `deploy/production*.env.example` 与 systemd `Environment=`），其余 54 个均有消费点（`SMTP_*` 由 alertmanager 入口脚本消费、`IMAGE_*`/`PUBLIC_HOST`/`TEST_TLS_DIR`/`DEPLOY_ENV_FILE` 由 compose 与 nginx 消费） | 仅上述 3 处需处置 |

修复动作：
- 样例 `AI_WORKER_LEASE_S` 60 → **660**，并注明须与 yml 一致；
- 移除两个 `AI_MOCK_*` 死键，留注释说明原委；
- **新增 `EnvExampleContractTest`**（两个用例）：① 样例每个键必须在 `application.yml` 有 `${}` 占位符（否则照抄即被**静默忽略**）；② 标量值必须与该键的 yml 默认值一致（列表型默认值含逗号则跳过——样例给出最小可用子集是合理的，如 `CORS_ALLOWED_ORIGINS` 只列 `localhost:5173`）；含扫描规模自检（键 ≥25、可比对标量 ≥20）；
- `docs/08` 配置原则补「第三个来源（样例）」条目。

验证：**红判定已做**——恢复 `AI_WORKER_LEASE_S=60` 并加回 `AI_MOCK_FAIL_RATE` 后，两用例**各 1 红**且失败信息点明键名（`AI_WORKER_LEASE_S → 样例 [60] vs yml 默认 [660]`、`AI_MOCK_FAIL_RATE 无占位符`），随后按 md5 校验完整恢复；定向 2/2 绿；server 全量 **900 测试 0 失败**（本批 +2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36846635376 / 36846635360，head 04cecb6，复核结论 success）。

### 2.48 数据字典（`docs/04` §3）与 Flyway 迁移双向漂移（2026-10-01 第五十五批扫描，已随本批修复）

方法：把「schema 的**声明**」与「schema 的**实现**」分开看 —— `docs/04` §3「表结构设计」是面向评审/答辩的契约，此前**没有任何门禁**保证它与 `V1~V34` 的实际 DDL 一致。

| 项 | 取证 | 结论 |
| --- | --- | --- |
| 文档漏写已实现的表（6） | `personal_profile`（V13+V15）、`communication_draft`（V17）、`communication_template`（V23+V29）、`inline_optimization_record`（V3）、`interview_ai_attempt`（V20）、`interview_asset_section`（V23+V25）在迁移中均有 `CREATE TABLE`，而 §3 **无章节** | **文档缺口 → 补章节** |
| 文档虚构不存在的表 | §3.12 `application_status_history`（§2.2 与 §8 亦引用）：`V1~V34` 无 `CREATE TABLE`，**全仓零代码引用**；投递状态迁移实际由 `application_record.status` + `stage_entered_at`（V26）承载 | **文档缺口 → 标注未实现** |
| 命名漂移 | §3.9 章节名 `material_resume_task`，迁移里实为 `material_resume_generation`（V6） | **文档缺口 → 更正** |

修复动作：新增 §3.17~§3.22 六个表章节（字段/类型/索引/唯一约束按 DDL 提取，含 V15/V25/V29 的 ALTER 结果）；§3.9 更名；§3.12 标题标注「（设计草案，未实现）」并说明承载方式；§2.2 路线图表补「状态」列（已实现/未实现 + 迁移版本）；§8 建表顺序按迁移更正。新增 `SchemaDocContractTest`（两用例、双向断言）：① 迁移的每张表必须在 §3 有章节；② §3 的每个表章节必须对应迁移表，或标题显式标注「未实现/设计草案」。

验证：**红判定已做**（两方向）—— 把 §3.9 标题改回 `material_resume_task` 并去掉 §3.12 的未实现标注后，两用例**各 1 红**且分别点出 `material_resume_generation`（文档漏写）与 `application_status_history` + `material_resume_task`（文档虚构），随后按 md5 校验完整恢复；修复后门禁 2/2 绿；server 全量 **902 测试 0 失败**（本批 +2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36847869352 / 36847869373，head e444dcc，复核结论 success）。

### 2.49 前端 API 契约对账：93 调用 × 95 端点，**双向一致、零缺陷**（2026-10-01 第五十六批扫描，结论固化）

方法：把「前端实际调用的端点」与「后端控制器端点」做全量对账 —— 不只比路径，还比 **HTTP 动词**（路径对但动词错同样返回 405，对用户一样不可用）。

| 项 | 结果 |
| --- | --- |
| 前端 `(method, path)` | **93** 个（`web/src/api/*.ts`；路径变量 `${id}` 归一为 `{}`） |
| 后端 `(method, path)` | **95** 个（19 个控制器，含 `@RequestMapping(method=…)` 与多路径映射） |
| **前端 → 后端** | **零缺失** —— 没有调用不存在的端点，也没有动词不匹配 |
| 后端 → 前端 | 2 个无 UI 调用，均有据：`GET /api/system/health/detail`（运维端点）、`POST /api/auth/logout-all`（第四十八批已确认无前端入口） |
| 字段级抽样 | `AiTask`（前端 TS 接口 12 字段）与 `AiTaskStatusResponse`（后端 record 12 字段）**逐字段一致**；前端未误用 `taskId`（仅 `AiTaskTimeoutError` 用作参数名） |

**这是正面结论**：该面此前无缺陷（本仓已有 151 个 e2e + 各契约门禁，前后端一致性维持得很好）。为避免「以为有问题」的重复投入、并把结论固化为**防漂移**能力，新增静态门禁 `FrontendApiContractTest`：

- 断言前端每个 `(method, path)` 在后端存在，失败信息**区分**「路径不存在 → 404」与「路径存在但动词不同 → 405」；
- 只做**单向**断言：后端有、前端未调用属合理（运维端点、分批实现），不断言，避免误报；
- 含扫描规模自检（后端端点 ≥80、前端调用 ≥80）。

解析要点（本次踩到**两次同源误报**，已固化进门禁注释）：泛型可能嵌套（`ApiResponse<Paginated<X>>`）甚至含引号（`ApiResponse<import('./ai').AiTask>`），因此**不能**用「动词与路径之间的字符」去匹配动词 —— 否则 `web/src/api/interview.ts` 的 `follow-up` 调用会被漏掉、进而误判为「后端端点无人调用」。正确做法：**先定位路径字面量，再向前回溯最近的动词**。

验证：**红判定已做** —— 在 `system.ts` 临时加入 `GET /api/does-not-exist` 与 `PATCH /api/system/health` 后，门禁 1 红并**逐条标注** 404 / 405，随后按 md5 校验完整恢复；修复后门禁绿；server 全量 **903 测试 0 失败**（本批 +1，5 skipped 为环境门控）；`docs/05` §15 补「前端契约一致性」约定；CI + Functional Regression 双绿（workflow run 36849142363 / 36849142250，head c503f9f，复核结论 success）。

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
  5. **第三十二~三十八批新增留观**：a) ~~账号导出整份缓冲为 String~~ → **第三十八批已完成**（改流式写出并把序列化移出事务：`-Xmx128m` 上 12.2MB 响应由 OOM 变为可服务 18.41MB，见 §2.31）；b) 导出文档本身无体积上限（是否给账号数据/导出文档设上限或分片，属产品口径）；c) ~~静态资源压缩~~ → **第三十五批已完成**（三份 nginx 配置开启 gzip 并补哈希产物长缓存，见 §2.28）；d) 镜像内 `web/nginx.conf` 不含 `security-headers.conf` 片段（构建上下文为 `web/`），其与部署侧「同步副本」多出 4 处 include —— 公网响应由 `edge` 统一下发安全头，故无影响，但片段的单一来源需一次设计取舍（见 §2.28 末段）。
- 持续留观：`web/e2e/ats-ai.spec.ts` 在第十三批出现过 1 次偶发失败（尚无第二次复现，继续留观）。
- ~~`web/e2e/applications-edit.spec.ts`（「编辑投递时只发 1 次版本列表请求」）偶发失败~~ → **第二十九批已按登记口径排查并修复**（第二次复现于纯文档提交的 CI run 36768442619，head 900f5c1）：根因是真实前端竞态（非测试问题），详见 §2.22；同时把该用例的竞态窗口用「延迟选项响应」固化，修复前稳定失败、修复后稳定通过。
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
| 2026-10-01 | **第二十九批（上传体积口径修正与下载死线：nginx 按整请求对齐 6m；§2.21 扫描新增项 • §2.22 竞态排查）执行完成**：第二十八批补上体积上限后暴露的**口径错误**——`client_max_body_size` 限制的是**整个请求体**（含 multipart 边界与头），而应用有两个不同口径的上限不可混同：`max-file-size` = 5MB（文件部分，业务判据 `ResumeImportService.maxBytes = 5242880`）、`max-request-size` = 6MB（整个请求，yml 注释写明「预留请求头等余量」）。nginx 各层按**单文件**口径写成 `5m` → 恰好 5MB 的文件（体约 5MB + 数百字节）在 nginx 层被 413 拒绝：接口文档承诺的 5MB 边界不可达，6MB 余量与统一信封在 5MB~6MB 区间完全用不上；第二十八批的门禁同样按单文件口径断言，**对本缺陷不报错**。同批发现导出下载走 axios 全局 10s，而响应体上限 10MB（移动网络常需数十秒），合法下载被判超时。修复：4 份 nginx 配置（`edge.conf` / `host.conf` / `web/nginx.conf` / 镜像同步副本 `deploy/nginx/web.conf`）`client_max_body_size` 5m → **6m**（= 整请求上限）；门禁口径改为「各层 ≥ `max-request-size`」并固化「`max-request-size` > `max-file-size`」前提；导出下载显式 `timeout: 60_000`。验证：`UploadPathContractTest` 修复前 **2/6 红**（`web/nginx.conf` 5242880 < 6291456、导出下载无显式超时）、修复后 6/6 绿；web `npm run build` 通过；server 全量 **844 测试 0 失败**（5 skipped 为环境门控）；`docs/05` §13 与 `docs/08` 更新体积契约口径；CI + Functional Regression 双绿（workflow run 36768082086 / 36768082338，head fcf626e，复核结论 success）。**同批跟进 §2.22 竞态**：第二十九批的纯文档补记提交（900f5c1）CI 失败于 `applications-edit` 偶发（**第二次复现**，run 36768442619）→ 按登记口径做竞态排查：仅给 e2e 的选项响应加 400ms 延迟即**稳定复现**（期望 1 实际 2），根因是 `useResumeJobOptions.load()` 在选项返回后无条件 `loadVersions()`，而编辑流可能已在此期间选定目标简历并自行加载版本 → 同一简历被重复请求（5 个消费方共用该组合式函数）；修复为「加载期间选择已变更则不重复加载」，e2e 保留延迟作为确定性守卫 → 该用例修复前稳定失败、修复后 3 次重复全通过；Playwright 全量 **144 passed / 6 skipped / 0 failed**；CI 双绿（workflow run 36769056149，head 9ea4ef8，复核结论 success） |
| 2026-10-01 | **第三十批（配置兜底一致性门禁 + 429 退避契约；§2.23 扫描新增项）执行完成**：① 同一配置项会在三处出现（代码 `@Value` 兜底、`application.yml` 默认值、环境变量默认），任一漂移都是「配置缺省时静默采用不同行为」的陷阱，而此前仅 `PdfDeadlineContractTest` 覆盖 PDF 两个键 → 新增 `ConfigFallbackContractTest`（SnakeYAML 解析 `application.yml` + 扫描全部 `@Value`，让门禁自己枚举漂移；含「扫描数/可比对数不得低于阈值」的自检，防解析失效后空转）：扫描 **52** 条兜底、**26** 条可比对，首跑报出**唯一**漂移 `app.ai.bailian.read-timeout-seconds`（代码兜底 **60** vs yml 默认 **300**——yml 注释已说明推理型模型单轮 40~477s，60s 兜底会让合法慢响应被判超时）→ 兜底改为 300；② `RateLimitFilter` 的 429 只有状态码与信封、**无 `Retry-After`**（RFC 6585 建议携带），而同仓 pdf-service 503 已带该头 → 补齐 `Retry-After` = 固定窗口（自然分钟）剩余秒数 1~60。验证：门禁首跑 1 条漂移、修复后全绿；单测断言 `Retry-After` 存在且落在 1~60、IT 断言响应头存在；真实 HTTP 探针（本机 H2，登录限流 2/分钟）第 3 次请求实测 `HTTP/1.1 429` + `Retry-After: 25` + 统一信封；server 全量 **845 测试 0 失败**（新增 1 门禁，5 skipped 为环境门控）；`docs/05` §1.3 补 429 退避契约（含「AI 日配额超限不带该头」的口径）；CI + Functional Regression 双绿（workflow run 36770148917 / 36770149028，head a7bf74b，复核结论 success） |
| 2026-10-01 | **第三十一批（匿名健康探针放大面：pdf 健康检查 TTL 缓存；§2.24 扫描新增项）执行完成**：核对「匿名开放端点是否含重活」发现——`GET /api/system/health` 在 `SecurityConfig` 中 `permitAll`，而其响应由 `checks()` 计算、`checks()` 调用 `PdfServiceClient.checkHealth()`：**一次真实出站 HTTP GET**（连接/读超时各 1s）。故「1 个公开请求 = 1 次出站探测」：外部可把公开探针放大成对 pdf-service 的持续探测；下游不可达时每个请求阻塞约 1s，持续请求即占满 API 请求线程（Tomcat 默认 200 线程下约百 req/s 饱和），且该端点不在限流清单内。修复：`checkHealth()` 加 TTL 缓存（`app.pdf.health-cache-ttl-ms` 默认 5000ms；正负结果同样缓存；冷启动/过期后锁内单次刷新避免惊群），把入站请求速率与出站探测速率解耦；运维语义保持（容器健康检查走 `/actuator/health/readiness`，20s 间隔）。验证：新增 `PdfServiceClientHealthCacheTest` —— 用 JDK `HttpServer` 作桩**直接统计出站探测次数**（走完整 RestClient 调用链）：TTL 内 20 次检查只探测 **1** 次、TTL 过后重新探测、5xx 负结果同样缓存（10 次只探测 1 次）；端到端真实 HTTP 实测（慢桩 pdf-service 固定延迟 1s）：冷启动 **1.64s**、随后 4 次匿名请求各约 **4~5ms**；server 全量 **848 测试 0 失败**（新增 3，5 skipped 为环境门控）；`docs/05` §14 补缓存口径；新配置项受 `ConfigFallbackContractTest` 守护；CI + Functional Regression 双绿（workflow run 36771319910 / 36771320348，head 70ac25e，复核结论 success） |
| 2026-10-01 | **第三十二批（限流窗口语义：固定窗口 → 滑动窗口；§2.25 扫描新增项）执行完成**：`RateLimitFilter` 此前按**自然分钟**计数（`Bucket` 仅在分钟变化时清零），注释与 `docs/05` §1.3 都自述「边界处可达 2 倍阈值」，2026-09-26 模块核对亦登记「注释称滑动窗口、实为固定窗口」。定向测试（新增包私有 `nowMs()` 时钟接缝）实证：10/min 的登录桶在「距边界 100ms」用满 10 次后把时钟推进 200ms 跨过边界，旧实现**再放行 10 次**（首跑红：`expected <1> but was <10>`）——攻击者只需对齐边界即可把阈值稳定跑成 2 倍，而该面覆盖 login（口令爆破）/register/refresh/凭证变更（「当前密码」验证面）。修复：`Bucket` 改为滑动窗口计数（当前窗口 + 上一窗口按剩余比例加权，`allow` 判「估计值 ≥ limit」，边界突发被压到最多 1 次）；`retryAfterSeconds()` 解算下一次配额可用时刻（旧的「距下一自然分钟」在窗口滚动瞬间重试必然再被拒，是错的提示）；`evictStaleBuckets` 回收两个窗口以前的桶。验证：修复前红 → 修复后 14/14 绿；真实 HTTP 探针（本机 H2 :8089、`login-per-minute=10`）对齐自然分钟边界实测：边界前约 1.2s 内 10 次登录全 `401`（放行），边界后 250ms 的 10 次只有 **1** 次放行、其余 9 次 `429 + 42901`，`Retry-After: 6` 与解算值一致；server 全量 **849 测试 0 失败**（新增 1，5 skipped 为环境门控）；`docs/05` §1.3 补滑动窗口口径；CI + Functional Regression 双绿（workflow run 36773283007 / 36773283009，head 2402862，复核结论 success） |
| 2026-10-01 | **第三十三批（账号导出的放大面：#7 导出纳入限流 + 下载死线对齐；§2.26 扫描新增项）执行完成**：`GET /api/auth/export` 用 13 个仓储查询把本人全账号数据整体重读并序列化为缩进 JSON（无分页、无体积上限），却不在限流清单内。真实 HTTP 探针（本机 H2）取证：播种 200 条 62KB 职业资料（库内约 12MB 文本）后单次响应 **11.92MB / 217~288ms**；同一账号连续 10 次导出全部 `200`、累计出站 **119MB**；前端下载超时 30s 只够 ~3.2Mbps 持续带宽（同类 PDF 导出下载已在第二十九批对齐 60s）。修复：`limitFor` 增加 `/api/auth/export` 分支 + 新配置 `app.security.rate-limit.account-export-per-minute`（默认 3/min；yml / `@Value` 兜底 / `.env.example` 三处对齐，受 `ConfigFallbackContractTest` 守护）；`web/src/api/auth.ts` 导出下载 30s → 60s；`UploadPathContractTest` 增「账号导出下载超时 ≥ 60s」断言；`docs/05` §1.3 受限端点清单补录。验证：单测修复前红（第 4 次导出 `200` vs 期望 `429`）、门禁修复前红（30000 < 60000）→ 定向 **22/22** 绿；真实 HTTP 探针第 1~3 次 `200`、第 4/5 次 `429 + 42901 + Retry-After: 48`；server 全量 **851 测试 0 失败**（新增 2，5 skipped 为环境门控）；web `npm run build` 通过；CI + Functional Regression 双绿（workflow run 36774368557 / 36774368572，head a04ee30，复核结论 success） |
| 2026-10-01 | **第三十四批（响应压缩：应用层开启 gzip；§2.27 扫描新增项）执行完成**：全仓（nginx conf / `application.yml` / 前端）扫描 `gzip|compression|Content-Encoding` **零命中**——真实 HTTP 探针（本机 H2、200 条 62KB 职业资料）带 `Accept-Encoding: gzip` 请求账号导出，响应既无 `Content-Encoding` 也无 `Vary: accept-encoding`，`Content-Length` 12.5MB 原样传输。修复：`application.yml` 的 `server` 段新增 `compression`（`enabled: true` + 文本类 mime 含 `application/json` + `min-response-size: 2048`）；新增静态门禁 `ResponseCompressionContractTest`（契约只由 yml 承载，且 MockMvc 不经过 Tomcat、既有测试不会因它缺失而变红，故必须显式固化）；`docs/08` 补「响应压缩契约」；yml 注释写明 BREACH/CRIME 口径（响应体只含调用方自己的数据、令牌在 `Authorization` 头，应用侧压缩先于反代 TLS 终止）。验证：门禁修复前红（临时置 `enabled: false`）→ 恢复后绿；**收益用散文式语料复测**（首轮「同一句话重复 2300 次」的合成文本把压缩比虚高到 175×，故改用条内无重复的 200 条 63KB）：**12,773,702 → 2,144,823 字节（≈6×）**，响应头 `Content-Encoding: gzip` + `Transfer-Encoding: chunked`；server 全量 **852 测试 0 失败**（新增 1，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36775072205 / 36775072144，head 4a80783，复核结论 success） |
| 2026-10-01 | **第三十五批（静态资源交付：三份 nginx 开启 gzip + 容器版补哈希产物长缓存；§2.28 扫描新增项）执行完成**：构建产物 64 个 js/css/svg 合计 **1001.1KB → gzip 285.5KB（3.51×）**、首屏入口 5 个文件 **457.4KB → 139.8KB**，而三份服务静态资源的配置（`web/nginx.conf`＝`web/Dockerfile` 打进镜像、容器拓扑真正生效的一份；`deploy/nginx/web.conf`＝部署侧同步副本；`deploy/nginx/host.conf`＝宿主机直连版）**都没有任何 gzip 指令**；同时发现拓扑漂移——`host.conf` 早有「带内容哈希的构建产物可长期缓存」块（`expires 30d` + `public, immutable`），而容器版两份**都缺该块**：60+ 个哈希产物落进 `location /` 的 `no-cache`，每次导航逐个回源校验（既有 `UploadPathContractTest.mirroredWebConfStaysInSync` 只比对 `client_max_body_size`，**对本漂移不报错**）。修复：三份配置补 gzip 块（`gzip_vary on`、`min_length 1024`、`types` 含 js/css/json/svg——nginx 默认只压 text/html）；`web/nginx.conf` 与 `deploy/nginx/web.conf` 补哈希产物缓存块（与 `host.conf` 同形），`location /` 保持 `no-cache`；新增静态门禁 `StaticAssetDeliveryContractTest`（对三份配置分别断言 gzip 覆盖 js/css、哈希产物块含 `immutable` 且有效期 ≥30 天、SPA 回退入口仍 `no-cache`）；`docs/08` 补「静态资源交付契约」；`gzip_proxied` 保持默认 `off`（本层不给 `/api/` 反代响应二次压缩，接口压缩在应用层，见 §2.27）。验证：门禁修复前红（2/2：三份无 gzip；容器版两份无哈希产物块）→ 修复后 **10/10** 绿（含既有体积与压缩门禁）；压缩比为本机对同一份 `web/dist` 实测；server 全量 **854 测试 0 失败**（新增 2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36776318144 / 36776318135，head 1bdfefc，复核结论 success） |
| 2026-10-01 | **第三十六批（AI 任务留存清理续批；§2.29 扫描新增项）执行完成**：`AiTaskRetentionService.purgeExpiredSnapshots()` 每轮只取一个 `PageRequest.of(0, batchSize)`（默认 200）且**没有续批**，而 `@Scheduled` 间隔是 24h ⇒ 清理上限恒为 **200 行/天**：一旦「每天新超期的终态任务数 > 200」，积压单调增长、90 天保留期对超出部分永不生效（快照含内联 JD/资料/简历，可达数百 KB/行）——**正是 #26 要消除的问题**；既有 `AiTaskRetentionIT` 只播 6 行（小于一批），该路径无任何覆盖。修复：改为**续批循环**（每批一批一事务 `TransactionTemplate`，避免单事务持久化上下文与持锁时间随积压线性增长）+ 新增单轮上限 `app.ai.task.cleanup-max-rows-per-run`（默认 20000，触达时记 WARN、剩余积压下一轮继续）；`docs/05` §7.5 同步「续批 + 单轮上限」口径。同族对照：`ExportExpiryService` 间隔 60s × 批量 100 = 14.4 万行/天，远超 PDF 导出任务产生速率，**不改**（仅记录）。验证：新增两个 IT 用例**修复前红**（批量 5 播 12 行只压缩前 5 行；未清理的 7 行还把相邻用例的全局计数断言污染为 `expected <2> but was <9>`）→ 修复后 **3/3** 绿（含「单轮上限 7 行生效、剩余 5 行下一轮清完」）；新键受 `ConfigFallbackContractTest` 守护；server 全量 **856 测试 0 失败**（新增 2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36777729108 / 36777729215，head e2de94c，复核结论 success） |
| 2026-10-01 | **第三十七批（数值型运维配置启动校验；§2.30 扫描新增项）执行完成**：这些键由环境变量注入，`@Value` 只保证「能解析成整数」，0/负值被**静默接受**并表现为难查的运行时故障——一次性探针 + 真实 HTTP 探针实证四种：① `AI_TASK_CLEANUP_BATCH_SIZE=0` → `PageRequest.of(0, 0)` 抛 `IllegalArgumentException`（间隔 24h ⇒ 一天一条错误日志、清理从未生效）；② `AI_TASK_CLEANUP_MAX_ROWS_PER_RUN=0` → 续批条件 `total < 0` 不成立、**静默不清理**（探针：3 行合格超期行一轮清 **0** 行、无任何日志）；③ `AI_TASK_RETENTION_DAYS=0` → cutoff = now、**保留期语义反转**（刚创建的终态任务立刻被压缩）；④ `RATE_LIMIT_LOGIN=0` → 应用启动正常 + `/api/system/health` 200，而**第 1 次**登录即 `429 + 42901`（登录入口静默砖化，健康检查毫无察觉）。既有 `ProductionConfigurationValidator` 只覆盖密钥与两个布尔开关。修复：新增 `NumericConfigurationValidator`（所有 profile 生效，`@PostConstruct`）把 14 个「必须为正」的键集中成一张表（限流 7 个阈值 + `max-buckets`、AI 留存 4 个、PDF 过期清理 2 个），取 0/负值即拒绝启动，并在表内自检「键是否都解析到」防门禁空转；`docs/08`「配置原则」补该口径。验证：单测**逐键**取「最小值 − 1」断言 fail-closed 且点明键名、取最小值本身放行、键缺省时门禁自身失败（3/3 绿）；真实探针对照——修复前启动正常 + 首次登录 429 → 修复后**启动即失败**（`login-per-minute=0 < 1` 由 `NumericConfigurationValidator.validate` 抛出、BUILD FAILURE、8089 不可达）；server 全量 **859 测试 0 失败**（新增 3，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36778714339 / 36778714752，head ef62cfc，复核结论 success） |
| 2026-10-01 | **第三十八批（账号导出改流式写出、序列化移出事务；§2.31 扫描新增项，闭环 §2.26 留观 ①）执行完成**：把该留观从「推测」做成**实测**——修复前导出把整份文档序列化为 `String`，控制器再由 `StringHttpMessageConverter` 编码出等长 `byte[]`（峰值堆 ≈ 2× 响应体，且响应体无上限）。受限堆实测（`-Xmx128m` + 本机 MySQL 承载数据以避免数据本身占堆；账号逐轮补 100 条 × 63KB 职业资料）：**6.14MB 响应 → 200（304ms）**、**12.2MB 响应 → HTTP 500 + `java.lang.OutOfMemoryError: Java heap space`**（`GlobalExceptionHandler` 记于 `http-nio-8089-exec-*`）、18.3MB 同样 500。同时 `exportAsJson` 标着 `@Transactional(readOnly = true)` 且**序列化在方法体内** ⇒ DB 连接在整个 JSON 生成期间被占用（限流按 IP，多用户各自的配额互不约束，并发导出会同时占连接并叠加堆）。修复：`AccountExportService` 拆为 `loadExportPayload`（只读事务，只做 DB 读取、返回即提交）+ `writePayloadAsJson`（`ObjectMapper.writeValue(OutputStream)` 直接写流）；`AuthController.exportData` 改 `void` + `HttpServletResponse`，显式设 `Content-Type: application/json;charset=UTF-8` 与 `Content-Disposition` 后写 `getOutputStream()`（刻意不用 `StreamingResponseBody`：无状态安全链在 ASYNC 派发会重跑过滤器，存在 401 风险；同步写流同时让 MockMvc 断言保持同步）；新增静态防复发门禁 `ExportStreamingContractTest`（导出链路不得出现 `writeValueAsString`、写流方法不得被 `@Transactional` 覆盖、`loadExportPayload` 保持只读事务）；`docs/05` §2.7 补「流式写出、无 Content-Length」口径。验证：同一 `-Xmx128m` 堆、同一账号——修复前 12.2MB 即 OOM → 修复后 **18.41MB 连续 3 次 200**（399~566ms，`Transfer-Encoding: chunked`、无 `Content-Length`）；`curl` 落盘校验**文档内容不变**（合法 JSON、`formatVersion=1`、`careerMaterials=300`、`sourceText` 长度 63953），首字节 **0.113s** / 总 **0.216s**（不再等整份序列化完成才出首字节）；门禁临时改回整份缓冲即红、恢复后绿；`AuthControllerIT` 导出两用例（401、聚合内容）无需改动即通过（13/13）；server 全量 **861 测试 0 失败**（新增 2 门禁用例，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36780584626 / 36780584743，head b56b1d3，复核结论 success） |
| 2026-10-01 | **第三十九批（容器交付路径补资源边界：mem_limit + 堆上限 + 日志轮转；§2.32 扫描新增项）执行完成**：同一宿主上三条交付路径的资源边界不一致——systemd 单元显式定 `-Xms128m -Xmx768m -XX:MaxMetaspaceSize=224m`（文档记载正是为适配 3.6GiB 宿主而把堆从 1024m 降下来），`docker-compose.ip-test.yml`（叠加层）给 4 个服务写了 `mem_limit` 且 API 有 `-Xmx512m`，而**生产 `docker-compose.prod.yml` 此前没有任何 `mem_limit`、没有 `JAVA_TOOL_OPTIONS`、也没有日志上限**。后果：未指定 `-Xmx` 时 JVM 按可用内存的 25% 默认（实测本机 15.71GiB → `MaxHeapSize=4219469824` 字节 ⇒ 3.6GiB 宿主上等价 **≈922MB**，高于刻意压到的 768m）；8 个服务（含 Chromium 的 pdf-service）无容器内存上限；容器日志（docker 默认 json-file 驱动）**不轮转**，与 systemd 路径「日志走 journald（自带轮转，两个单元均注明）」不对称（本地实测：正常请求不写日志——200 次匿名探针增量 0 字节，增长来自异常全栈与 worker 日志）。修复：`docker-compose.prod.yml` 顶部新增 `x-logging` 锚点（json-file + `max-size 10m` × `max-file 5` = 每容器 ≤50MB）供 8 个服务引用；8 个服务补 `mem_limit`（edge/web 128m、api **1200m**、pdf-service 768m、mysql 700m、prometheus 512m、grafana 384m、alertmanager 128m，口径对齐同宿主已实测的 ip-test 拓扑）；api 补 `JAVA_TOOL_OPTIONS: -Xms128m -Xmx768m -XX:MaxMetaspaceSize=224m`（与 systemd 同值）；新增静态门禁 `ComposeResourceBoundsContractTest`（生产 compose 每服务必须有内存上限 + 有界日志、叠加层不得造出无界覆盖、api 堆上限须等于 systemd 单元且 `mem_limit ≥ 堆 + 256m`）；`docs/08` 补「容器资源边界契约」。验证：门禁对修复前 compose **2/3 红** → 修复后 **3/3** 绿；`docker compose --env-file production.env.example -f docker-compose.prod.yml config --quiet` **exit 0**，叠加层 `-f prod -f ip-test` 合并后同样 exit 0 并解析出 api `-Xmx512m` / `mem_limit=838860800`（800m）且继承 `max-size 10m`；server 全量 **864 测试 0 失败**（新增 3 门禁用例，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36782229944 / 36782230015，head f44df87，复核结论 success） |
| 2026-10-01 | **第四十批（PDF 服务监听范围收敛：直连路径绑回环、容器路径保持全接口；§2.33 扫描新增项）执行完成**：`docs/08` §3.3 要求「PDF 服务只监听私有网络接口（127.0.0.1 或内网 IP），不直接暴露给浏览器」、§10.1 要求「PDF 服务不得暴露公网」，而 `pdf-service/src/server.js` 用 `app.listen(port)` **不带 host** ⇒ Node 绑定所有接口。本机真实探针（`PDF_SERVICE_PORT=3011`）：`Get-NetTCPConnection -LocalPort 3011` → `LocalAddress=::`（所有接口），`curl --noproxy '*' http://192.168.5.4:3011/render` → **HTTP 401**（确实在对外服务）。该暴露面此前只由云安全组兜底，且已被两份文档登记为残留风险（`docs/DEPLOYMENT_DIRECT.md` §1 表格「`0.0.0.0`（应用未提供绑定参数）」与 §8 表「依赖安全组封闭该端口；如需彻底收敛需改代码加 `server.address` 类参数」；`docs/reviews/2026-09-25-project-completeness-assessment.md` 亦因该项把安全边界评为 B）。同交付链路的 **api 单元早已因同类问题**（`.env` 的 `SERVER_ADDRESS` 无占位符被静默忽略 → 监听 `*:8080`，实测内网与公网均可访问）改为单元内注入 `Environment=SERVER_ADDRESS=127.0.0.1`，pdf 单元却无对应项——两条路径处理不对称。修复：`server.js` 支持 `PDF_SERVICE_HOST`（缺省仍**不指定 host**＝绑定所有接口，容器路径依赖该缺省；含空白取值 fail-closed 启动失败），把 host 真正传给 `app.listen`，启动日志改报**实际绑定地址**（原固定打印 `localhost`，无法用于核对「改了没生效」）；`deploy/systemd/intelligent-resume-pdf.service` 注入 `Environment=PDF_SERVICE_HOST=127.0.0.1`（与 api 单元同口径；必须真实环境变量而非只写 `.env`）；**容器路径刻意不设该变量**——API 容器在私有网络内经 `PDF_SERVICE_BASE_URL=http://pdf-service:3001` 访问，收敛回环会让导出整体不可用。验证：**修复前后同一探针**——修复前 `LocalAddress=::`／非回环 → 401；修复后（端口 3012、`PDF_SERVICE_HOST=127.0.0.1`）`LocalAddress=127.0.0.1`、回环 → 401、**非回环 → 连接被拒（HTTP 000 / exit 7）**，启动日志 `http://127.0.0.1:3012`；门禁红判定已做（把单元值改成 `0.0.0.0` → `systemdPdfUnitBindsLoopback` 失败）→ 改回后 4/4 绿；新增 `pdf-service/test/bind-host.test.js`（2 例）与静态门禁 `PdfServiceBindScopeContractTest`（4 例，含「容器 compose 不得收敛回环」的反向约束）；server 全量 **868 测试 0 失败**（本批 +4，5 skipped 为环境门控）、pdf-service **33 测试 0 失败**（本批 +2）；`docs/08` §3.3/§10.1、`docs/DEPLOYMENT_DIRECT.md` §1/§4.4/§8、`pdf-service/README.md`、`.env.example` 同步。CI + Functional Regression 双绿（workflow run 36784082150 / 36784082137，head 4091af0，复核结论 success） |
| 2026-10-01 | **第四十一批（告警链路补可用性告警并补回分级阈值；§2.34 扫描新增项）执行完成**：把 `docs/08` §7.1「建议阈值」/§7.3 描述与 `monitoring/prometheus/rules/intelligent-resume-alerts.yml` 的**每条**规则逐项对照，发现两类问题——① 原有 **11 条规则全部建立在应用自身导出的指标上**，而应用不可用时这些序列随抓取失败一起消失（`rate`/`histogram_quantile` 在空区间返回**空向量**而非 0、gauge 走 staleness，`for` 也覆盖不到「没有数据」），于是「整机不可用」这一最该告警的事件**零告警**，规则文件里 `up{` **零命中**；② §7.1 承诺的「连续 3 次失败触发告警」「p95 500ms 警告 / 2s 严重」「5xx 1% 警告 / 5% 严重」只有后半级实现，§7.3 还自述「7 条」（实际 11 条）。修复：规则集 11 → **15 条**——新增 `ApiTargetDown`（`up{job="intelligent-resume-api"} == 0`，`for: 1m` ≈ 4 个抓取周期，critical）、`ApiScrapeTargetMissing`（`absent(up{job=...})`，`for: 10m`，warning；目标被从抓取配置移除时 `up` 整体消失、`== 0` 同样不触发，这是「监控自身坏了」的唯一信号）、`ApiServerErrorsCritical`（5xx > 5%）、`ApiLatencyWarning`（p95 > 500ms）；`docs/08` §7.1 改为与规则集一一对应（含可用性一条的存在理由），§7.3 条数与门禁说明同步；新增静态门禁 `AlertRuleContractTest`（5 例：规则/面板引用的指标必须在 `AppObservability` 注册〔先按原名匹配再剥离 Prometheus 后缀，兼容 `resume_ai_queue_oldest_pending_seconds` 这类以 `_seconds` 结尾的 gauge〕、可用性规则必须用**抓取配置里的 job_name** 且 `for ≥ 3 × scrape_interval`、`severity` 必须能被 `alertmanager.yml` 的路由匹配器接住、§7.1 的分级阈值必须都在）。验证：门禁对**修复前**规则文件恰为 **2 红**（缺可用性规则、缺分级阈值）而其余 3 例通过——红的是真实缺口而非计数下限（该下限刻意设为 10 < 修复前的 11）；修复后 **5/5** 绿；同时复核并**排除**了一个曾于 `docs/reviews/2026-09-25-full-functional-verification.md` 撤回的疑点（Counter 的 `_total` 后缀与 Micrometer 零样本不导出序列属取样假象）；server 全量 **873 测试 0 失败**（本批 +5，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36785061765 / 36785061661，head 6a99f49，复核结论 success） |
| 2026-10-01 | **第四十二批（登出/删号清理浏览器本地用户数据；§2.35 扫描新增项）执行完成**：把「浏览器本地存储里到底存了什么」列成清单（全量核对 `localStorage|sessionStorage` 键名与写入点）并对照 `docs/08` §9.6 的删除口径——编辑器把**完整简历 JSON**（姓名/联系方式/工作经历）写入 `localStorage['intelligent-resume.editor-draft.<userId>.<resumeId>']` 做崩溃恢复（既有「恢复未保存的草稿？」用例即由此驱动），而 `auth.signOut()` **只清内存 access token**、`AccountView.confirmDeleteAccount()` **只清会话**，两条路径都不碰本地存储：共享设备上换人使用即可读到上一账号的简历原文，用户删号后浏览器里仍留有完整副本（与 §9.6「账户删除…完成清理」矛盾）。同一清单还有 `active-ai-task.<userId>`、导入页的 `resume-import-text`（**解析出的简历原文**）、`application-draft`（沟通文案草稿）、`interview-session-id`。修复：新增 `web/src/utils/localUserData.ts`（`clearUserLocalData(userId)` 按用户前缀清理 `editor-draft.*`/`active-ai-task.*` 并清 4 个标签页级键；storage 不可用静默跳过；注释登记完整键清单），在 `auth.signOut()` 与 `AccountView.confirmDeleteAccount()` 成功后调用，**刻意保留**界面语言/侧栏折叠等用户无关偏好；`docs/08` §9.6 补该口径。验证：新增 `web/e2e/privacy-local-data.spec.ts`（2 例，登出/删号各一，均先**真实产生**草稿——编辑器改姓名→等防抖落盘→断言草稿含 `Alice Chen`——再断言 4 个键全清）；红判定：临时停用两处调用 → **2 例全红**（`expect(received).toBeNull()`，草稿仍在）→ 恢复后 **2 例绿**；web 全量 Playwright **146 passed / 0 failed**（本批 +2）、`npm run build`（含 `check:i18n`/`check:draft-fields`/`vue-tsc`）通过；CI **success**（workflow run 36786065147，head 604810d，含 Server tests / Web build and browser tests / PDF service checks 三个作业；Functional Regression 因本次仅改动 `web/**` 与 `docs/**`、不命中其 `paths` 过滤器而未触发） |
| 2026-10-01 | **第四十三批（面试状态轮询只读末轮记录，消除随轮数增长的读放大；§2.36 扫描新增项）执行完成**：`GET /api/interviews/{id}` 是前端在 `EVALUATING_ANSWER` 下 1/2/4/5s 轮询的热路径，而 `InterviewStateAssembler.buildStateResponse` 用 `findBySessionIdOrderByRoundNoAscIdAsc` **全量读出本会话所有轮次**再取 `size()-1` 渲染 `lastEvaluation`。真实 MySQL 5.7 探针（本机库 + 真实 JWT，造 9 轮/1 轮两个同构会话，每轮 `answer_text`≈8.4KB + `feedback_json`≈2KB，取 20 次调用的 `Bytes_sent`/`Innodb_rows_read` 增量）：修复前 9 轮会话 **99,636 字节/次、19 行/次** vs 1 轮会话 13,592 字节/次、3 行/次 ⇒ 单次读取量随已完成轮数线性增长，累计呈 O(n²)；SQL 日志证据为 `select ... where session_id=? order by round_no, id`（**无 limit**，连 `answer_text`(MEDIUMTEXT) 与 `feedback_json`(JSON) 全取回）；且该端点运行在**持有会话行写锁的事务内**（`getState` 用 `PESSIMISTIC_WRITE` 且可能标失败陈旧 attempt），读取越久持锁越久。修复：新增 `findFirstBySessionIdOrderByRoundNoDescIdAsc`（双键降序取 1 条，与「升序取末条」等价）并改用它，全量读取留给报告/提示词上下文/账号导出。验证：同一会话同一探针 **13,636 字节/次 / 13,592 字节/次**——与轮数无关（99.6KB → 13.6KB，约 7.3×），SQL 变为 `order by round_no desc, id limit ?`；`InterviewStateAssemblerTest` 改为断言末轮查询并新增 `buildStateResponse_neverLoadsAllRecords`（`verify never`；红判定已做：改回全量读取时该断言失败）；`InterviewRecordOrderingIT` 增倒序写入取末轮用例；server 全量 **875 测试 0 失败**（本批 +2，5 skipped 为环境门控）；`docs/05` §9.4 补读取契约；探针数据用完即删。留观：每次轮询仍有一次 `count(*)` 统计轮数（索引范围计数、不传行内容，`Bytes_sent` 已与轮数无关），彻底常数化需会话行派生列，收益小于成本故未做。CI + Functional Regression 双绿（workflow run 36787699476 / 36787699443，head 383c5b4，复核结论 success） |
| 2026-10-01 | **第四十四批（面试消费归档简历版本改按 409 可恢复口径；§2.37 扫描新增项）执行完成**：`InterviewPromptContextAssembler.findOwnedResumeVersion` 此前把归档版本折叠进 404（`findById(...).filter(deletedAt == null)` →「简历版本不存在」）；该方法被 `/start` 来源校验与**进行中每轮评估**的 `appendResumeContext` 共用，而 `ResumeVersionService.archive` 只禁止归档当前版本——用户新建/恢复版本后归档旧版本，后续 `/answer` 的评估会以 `NOT_FOUND`/「简历版本不存在」/`retryable=true` 落到会话上，用户看到**原因错误且重试无效**的 AI 失败提示（与 ATS/评分/导出/投递的 409「请先恢复」口径不一致，违反 batch 15 的跨模块契约）。修复：改为「找不到 → `40401`；**归属校验先于归档判定**（他人版本仍以 `40401` 收口、不泄露归档状态）→ 归档 → `40901` +「该简历版本已归档，请先恢复后再继续」」，恢复版本后重试即可。测试：改 `findOwnedResumeVersion_deleted` 断言 409，新增「归档且属他人 → 404 不泄露」「`appendResumeContext` 遇归档 → 409」两例；红判定已做（修复前 2 红：`expected: <CONFLICT> but was: <NOT_FOUND>`）；server 全量 **877 测试 0 失败**（本批 +2，5 skipped 为环境门控）；`docs/05` §5.4/§9.4 契约同步；CI + Functional Regression 双绿（workflow run 36788640318 / 36788640285，head 22c48f0，复核结论 success） |
| 2026-10-01 | **第四十五批（AI 重试改后台异步：消除「重试必然超时」；§2.38 扫描新增项）执行完成**：`POST /api/interviews/{id}/ai/retry` 此前在请求线程内同步等待同一次 AI 评估，与 `/answer` 修复前逐行同源（实测 4 轮 102.3/76.4/110.0/145.9s、平均 108.7s，而前端该接口超时 60s）→ 点「重试」必然超时、服务端仍在评估并于约 108s 后落库，重试按钮形同虚设（`/answer` 已异步化，重试漏改；`InterviewEvaluationConfig` 类注释本就自述 `/ai/retry` 属半成品异步设计）。修复：TX1 后把 AI 段提交到与 `/answer` 相同的 `interviewEvaluationExecutor`，请求秒回 PROCESSING（首题重试 `GENERATING_QUESTION`、评估重试 `EVALUATING_ANSWER`），前端在 AI 加载态本就轮询 `GET /interviews/{id}`（**零前端改动**）；队列满以 `200` + `aiFailure`（`QUEUE_REJECTED`，可重试）快速失败；`generation`/`isCurrentRetry` stale 丢弃与异常兜底整体保留（抽出 `runRetry`/`markRetryFailed`）。测试：新增 `InterviewRetryServiceTest`（4 例，红判定已做：修复前 4 红）；`InterviewControllerIT` 第 17 例改为异步契约（有界等待后台失败后验证第 61 次 `RATE_LIMITED`）；server 全量 **881 测试 0 失败**（本批 +4，5 skipped 为环境门控）；`docs/05` §9.4 与 ADR-010 同步；CI + Functional Regression 双绿（workflow run 36789967338 / 36789967155，head 661227d，复核结论 success） |
| 2026-10-01 | **第四十六批（补 LIGHT 分组门禁并就地关闭已实现的登记册条目；§2.39 扫描新增项）执行完成**：把 `docs/decisions/OPEN-DECISIONS.md` 的每条 OPEN 与现状逐条对账，发现条目 8（2026-09-23 plans/003 I2「AI worker 按 id 串行领取，长任务会饿死短任务」）仍标 `OPEN · waiting-on-external-condition`，而 `docs/reviews/2026-09-26-optimization-opportunities.md` ④ 已把方案 A 记为「✅ 完成」——登记册（自述唯一跟踪口径）漏关，会把已交付能力继续当待办、诱发重复投入。核对落地形态：`AiTaskCapabilityRegistry.Group{HEAVY,LIGHT}` 单点登记分组、`DatabaseTaskWorker` 按分组执行且两组独立线程池/并发额度、领取过滤下推 SQL（`task_type IN (:taskTypes)`）。按规则**就地关闭**该条目（保留原文 + `RESOLVED` + Resolution + 残留登记）。同批补门禁 `AiTaskCapabilityRegistryTest.secondScaleTasksStayInTheLightGroup`：把实测被饿死的秒级类型钉在 LIGHT 并要求其非空——此前把 `INLINE_OPTIMIZE` 改回 HEAVY 时 `DatabaseTaskWorkerTest` 三条**仍全绿**（桩按 `typesIn(group)` 匹配），饿死可被静默撤销；红判定已做（`expected: <LIGHT> but was: <HEAVY>`，worker 三条仍绿）。回归：server 全量 **882 测试 0 失败**（本批 +1，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36790808953 / 36790808967，head bb8eec5，复核结论 success） |
| 2026-10-01 | **第四十七批（导出/投递/沟通的归档版本改按 409 可恢复口径；§2.40 扫描新增项）执行完成**：`docs/05` §5.4 早已声明「ATS 体检 / PDF 导出 / 投递关联 / 沟通 / 规则评分 / AI 面试拒绝归档版本（`40901`，需先恢复）」，但实测只有 ATS/评分（及第四十四批修好的 AI 面试）如此——`ExportService.create`、`ApplicationService.validateReferences`、`CommunicationService.ownedResumeVersion`（`findByIdAndCreatedByAndDeletedAtIsNull`，归档即查空）与 `CommunicationTemplateService.preview` 都把「已归档」与「查无此版本」合并成 `404`「简历版本不存在」，与第四十四批同源（用户看到原因错误、恢复后也判断不了）。四处统一为「找不到 → `40401`；**归属校验先于归档判定**（他人版本仍 `40401`，不泄露归档状态）→ 归档 → `40901` + 可操作文案」，导出/投递/沟通文案与同族一致（同步草稿 / AI 任务 / 模板预览共用 helper）。测试：导出、投递各改一条断言 409 并新增「归档且属他人 → 404」；`CommunicationControllerIT` 新增 `@Order(8)` 覆盖 `/generate`、`/ai-generate`、`/templates/{id}/preview` 三处；`CommunicationTemplateServiceTest` 同步桩并新增归档/他人两例；红判定已做（修复前 `expected: <CONFLICT> but was: <NOT_FOUND>`、`expected: <409> but was: <404>`）；定向 60/60、server 全量 **887 测试 0 失败**（本批 +5，5 skipped 为环境门控）；`docs/05` §5.4 同步；CI + Functional Regression 双绿（workflow run 36791728644 / 36791728613，head 9bcdfa8，复核结论 success） |
| 2026-10-01 | **第四十八批（更正 logout-all「立即失效」措辞并补全端登出门禁；§2.41 扫描新增项）执行完成**：`docs/05` §2.9 称 `POST /api/auth/logout-all`「撤销全部刷新会话（**其它设备立即失效**）」，实测不符——撤销后其它设备已签发的 access token 仍可 `GET /api/auth/me`（**HTTP 200**），直至自身 `exp`（默认 `JWT_ACCESS_TTL=3600s`）。机制证据：`AuthService.logoutAll` 只置 `auth_session.revoked_at`；`TokenService.issueAccessToken` 只写 sub/username/iat/exp（**access token 不含会话标识**）；`JwtAuthenticationFilter` 仅校验签名/有效期 + `ActiveUserCache.isActive(userId)`，`AuthSessionRepository` **不在请求链路上**。同文档 §2.7 删号的「access token 立即失效」则为真（置 `DISABLED` + 用户态即时拒绝，Order 7 已守护）——两处语气相同、结论相反。修复：§2.9 按实测更正并写明与删号差别；`AuthControllerIT` 新增 `@Order(11)`（三设备各登录 → logout-all → 逐个断言 refresh `40101` + 失效 cookie；该端点此前**零 HTTP 层覆盖**，`AuthServiceTest` 只查行字段）；`OPEN-DECISIONS` 新增登记项（A 维持现状 / B 令牌水位 + 缓存比较〔受 30s 缓存 TTL 与时钟偏移约束，需宽限窗口〕/ C 以删号兜底）。验证：临时探针实测边界后**即删**（不把缺陷固化为断言）；定向 11/11、server 全量 **888 测试 0 失败**（本批 +1，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36792721390 / 36792721141，head 99af773，复核结论 success） |
| 2026-10-01 | **第四十九批（留存口径对账：修正 30/90 天漂移 + 标注未实现承诺；§2.42 扫描新增项）执行完成**：新增静态门禁 `RetentionPolicyContractTest`（**规范文档声明 vs `application.yml` 默认值**；此前的 `ConfigFallbackContractTest` 只覆盖「代码兜底 vs yml 默认」）。首跑即红，暴露两类问题：① **AI 任务留存三份规范写 30 天、实现是 90 天**——`docs/04` §7.1 生命周期表、`docs/07` §5.10、`docs/08` §9.6 均声明 30 天，而 `app.ai.task.retention-days: ${AI_TASK_RETENTION_DAYS:90}`（第十三批经用户确认「90 天压缩快照」并落地，`docs/05` §7.5 亦写 90）⇒ 同一事实三种数值，规范的留存期是面向用户/合规的承诺、漂移即虚假承诺；② **一组生命周期承诺无任何实现**——全仓 `@Scheduled` 仅三处（AI 留存/PDF 过期/导出过期），无硬删仓储方法与清扫作业，`V1~V34` 中无 `account_deletion_job`（§7.2 字段表实为设计草案），`deleteAccount` 为同步路径 ⇒「软删 30 天后 7 天内硬删」「账户 7 天撤销窗口 + 30 天内清理」均未实现。修复：三处改 90 天并注明依据；`docs/04` §7.1 逐行标注「已实现/计划中」、账户行按实际行为更正、§7.2 标注设计草案，`docs/07`/`docs/08` 同步；`OPEN-DECISIONS` 登记 A/B/C（并说明硬删裁剪口径依赖 §7.1 例外条款定义，属产品/合规决策，**不擅自实施破坏性级联删除**）。门禁设计为「每个（文档，正则）对必须命中并捕获天数」——文档改写或删除该句即报红，迫使承诺被显式维护。验证：红判定已做（2/2 红）→ 定向 2/2 绿；server 全量 **890 测试 0 失败**（本批 +2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36793605842 / 36793605938，head 54aa057，复核结论 success） |
| 2026-10-01 | **第五十批（归档不可消费改用专属码 40902 + 前端可操作指引；§2.43 扫描新增项）执行完成**：第四十七批把归档拒绝统一为「`40901` + 可操作中文文案」，但前端 `errorMessage.ts` **按设计不透传服务端 message**（双语界面走错误码映射），`40901 →`「内容已更新或状态已变化，**请刷新后重试**」——对归档场景是**错误处置**（刷新不会让归档版本可用，用户须先去版本历史恢复）；ATS/评分自 batch 15 起同样如此，`InterviewView` 更是**完全不读 `aiFailure.messageCode`**。修复：新增 `VERSION_ARCHIVED(40902)`，七处消费方统一改抛（语义分工：消费类拒绝=40902 处置「先恢复再重试」／版本管理类冲突=40901 处置「刷新或换版本」）；前端登记 `40902 → errors.versionArchived` + zh/en 可操作文案；`InterviewView` 按 `messageCode === 'VERSION_ARCHIVED'` 显示专属指引。落地中还真实撞到一个陷阱：新码漏登记时 `GlobalExceptionHandler.statusFor` 的 `default -> 500` 让接口**由 409 变 500** ⇒ 去掉 `default` 改编译期穷尽检查，并新增遍历全部业务码断言非 5xx 的用例。测试：服务端红判定 7 红 → 定向 26/26、全量 **891 测试 0 失败**（本批 +1）；web 单测 6/6（扩到 11 码并断言 40902 文案不同于 40901）、`npm run build` 通过、Playwright **148 passed / 6 skipped / 0 failed**（新增 `interview-archive-failure.spec.ts` 2 例，含「非归档失败不得出现该指引」的反向断言，红判定已做）；`docs/05` §1.3/§5.4/§8.3/§9.4 同步；CI + Functional Regression 双绿（workflow run 36795230998 / 36795230994，head 90fdf8a，复核结论 success） |
| 2026-10-01 | **第五十一批（让归档指引真正到达三个消费入口页面；§2.44 扫描新增项）执行完成**：对第五十批结论做**闭环复核**——「前端登记了 `40902 → errors.versionArchived`」不等于用户能看到它：登记表只在调用 `resolveApiError` 的视图生效，而**导出/投递/沟通**三页的错误处理此前全用固定 i18n 兜底串，把 `40902` 吞成「导出失败/保存失败/生成失败」，用户看不到唯一正确动作「先去版本历史恢复该版本」（第五十批对这三个入口实际无效）。修复：`ResumeDetailView.exportPdf`、`ApplicationsView.save`、`CommunicationView` 三处（同步草稿 / AI 任务 / 模板预览）改走 `resolveApiError(cause, <原兜底键>)`——**已登记码优先、无码回落原串**，既有文案不回归；`CommunicationView` 刻意保留 40302/42901 的页内专属文案。验证：新增 `archive-guidance.spec.ts`（3 例逐入口 mock 40902），**红判定 3 例全红**且失败信息直接给出被吞掉的原串（证明是「文案被吞」而非选择器问题）→ 修复后 3/3 绿；web 单测 6/6、`npm run build` 通过、Playwright **151 passed / 6 skipped / 0 failed**（本批 +3）；CI **success**（workflow run 36796583208，head cfcfd5b；Functional Regression 因仅改 `web/**` 未触发） |
| 2026-10-01 | **第五十二批（PDF 输出上限落地：声明即虚构的 max-output-bytes 补消费点 + pdf-service 第二道闸；§2.45 扫描新增项）执行完成**：`app.pdf.max-output-bytes`（默认 10MB）在 `application.yml` / `application-local-h2.yml` / `application-test.yml` / `server/.env.example` 四处声明，`UploadPathContractTest` 与 `web/src/api/export.ts` 也把它当作「导出下载的响应体上限」引用，但服务端**没有任何消费点**（`maxOutputBytes` 在 `main/` 零命中）、pdf-service 侧也没有输出大小检查——声明即虚构：超出上限的渲染被原样落盘并允许下载，与 `docs/03` §9.7 不符。修复：`PdfServiceClient` 在渲染结果**落盘前**按该上限判定（超限记 `OUTPUT_TOO_LARGE` 观测 + `PDF_FAILURE`「导出文件超出最大允许大小」，不落盘、不可下载）；pdf-service 新增 `PDF_SERVICE_MAX_OUTPUT_BYTES`（默认 10MB）**第二道闸**（`500` + `50003`、不写回结果；覆盖「API 上限被调高」；非法取值启动即失败）；`PdfFailureCategory` 新增 `OUTPUT_TOO_LARGE`、分类器补两条中文文案规则（**顺带修正**：中文输入超限文案此前被误标为 `RENDER`——分类器只认英文 `input`/`too large`）；新增门禁 `PdfOutputBoundContractTest`（4 断言，含 **service 上限 ≥ API 上限** 的反向约束）+ 单测 `PdfServiceClientOutputBoundTest`（超限拒绝 / 恰好等于上限放行，JDK `HttpServer` 作桩）；文档同步 `docs/03` §9.7（并如实登记「最大页面数未做逐页计数」，由单版本 256KB 与输入上限间接约束）/`docs/05` §11.1/`docs/08` §8/`pdf-service/README.md`/`.env.example`。验证：**红判定已做**（移除消费点 → 门禁 1 红 + 单测 1 红，随后 md5 校验完整恢复）；定向 **10/10** 绿（日志实证 `1025 > 1024` 被拒）；server 全量 **896 测试 0 失败**（本批 +6，5 skipped 为环境门控）；pdf-service 本地 **27/34**——7 项 `spawnSync` 用例受本机执行环境限制（`EBUSY`，连 `cmd` 都无法 spawn）未跑，同命令**直接执行**已验证退出码 1 与 stderr 正确，**CI 侧全部通过**；CI + Functional Regression 双绿（workflow run 36842479281 / 36842479307，head 114a1fb，复核结论 success） |
| 2026-10-01 | **第五十三批（配置声明↔消费点对账：修 @ConfigurationProperties 兜底漂移与死键，并立消费点门禁；§2.46 扫描新增项）执行完成**：把 `application.yml` 全部 **97 个** `app.*` 叶子键与代码消费点做全量对账，发现三类问题——① **绑定类字段默认值漂移**：`AiTaskWorkerProperties.leaseSeconds` 字段默认 **180** 而 yml 是 `${AI_WORKER_LEASE_S:660}`（第一批 #60 只改 yml、漏改绑定类；该键 yml 缺省时用字段默认值 ⇒ 租约减半 → 长任务被接管重跑、重复调用 provider）；② **死键**：`app.ai.quota.JOB_MATERIAL_SELECTION`（选材与生成共用 `JOB_GENERATION` 额度）、`app.job.parser.rule-version`（解析结果不含版本、绑定类无该属性）；③ **门禁盲区**：既有 `ConfigFallbackContractTest` 只扫 `@Value`，绑定路径零覆盖。修复：`leaseSeconds` 字段默认 180 → **660**（附「必须 > 链总预算 600s」注释）；移除两个死键并在 yml 留注释；`ConfigFallbackContractTest` 新增 `configurationPropertiesDefaultsMatchYaml`（扫 `@ConfigurationProperties` 数值/布尔字段 vs yml 默认值）；**新增 `ConfigConsumerContractTest`**（`app.*` 标量键必须有消费点：点号路径字面量 或 绑定前缀字段〔含 Map 元素按首段 camel 化〕；未实现项须显式登记 `KNOWN_UNCONSUMED` 并附理由，获得消费点后须移出白名单防腐化；含扫描规模自检）；`docs/08` 配置原则补「三种失效形态」条目；`app.job.jd-text.min-length` 与 `app.ai.confirmation.*`（3 项，其中 `max-confirmed-items` 的 200 已由 DTO `@Size` 承载）登记 `OPEN-DECISIONS`。验证：**红判定已做**（恢复 180 + 加回死键 → 两门禁各 1 红并点明键名，md5 校验恢复）；定向 3/3 绿；server 全量 **898 测试 0 失败**（本批 +2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36844601848 / 36844601794，head 16edcb6，复核结论 success） |
| 2026-10-01 | **第五十四批（环境变量样例对账：修样例值漂移与死键，并立样例门禁；§2.47 扫描新增项）执行完成**：`server/.env.example` 是配置的**第三个来源**（前两个是代码 `@Value` 兜底与 yml 默认值），此前不在任何门禁覆盖内。① **值漂移**：`AI_WORKER_LEASE_S=60` 而 yml 是 `${AI_WORKER_LEASE_S:660}` —— 照抄样例即把租约压到链总预算 600s 之下、长任务被接管重跑（第一批 #60 只改了 yml）；② **死键**：`AI_MOCK_FAIL_RATE` / `AI_MOCK_LATENCY_MS` 全仓零消费点（Mock 模型已非正常功能路径）。修复：样例 60→660（注明须与 yml 一致）、移除两个 Mock 键；**新增 `EnvExampleContractTest`**（① 样例键必须在 yml 有 `${}` 占位符，② 标量值须与 yml 默认一致、列表型含逗号则跳过；含规模自检）；`docs/08` 补「第三个来源」条目。验证：**红判定已做**（恢复 60 + 加回 Mock 键 → 两用例各 1 红并点明键名，md5 校验恢复）；定向 2/2 绿；server 全量 **900 测试 0 失败**（本批 +2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36846635376 / 36846635360，head 04cecb6，复核结论 success） |
| 2026-10-01 | **第五十五批（数据字典 ↔ Flyway 迁移双向对账：补 6 张表章节、更正命名漂移、标注未实现表，并立双向门禁；§2.48 扫描新增项）执行完成**：`docs/04` §3「表结构设计」是评审/答辩用的 schema 契约，此前无任何门禁保证它与 `V1~V34` 一致。① **文档漏写 6 张已实现表**：`personal_profile`(V13+V15)、`communication_draft`(V17)、`communication_template`(V23+V29)、`inline_optimization_record`(V3)、`interview_ai_attempt`(V20)、`interview_asset_section`(V23+V25)；② **文档虚构 1 张表**：§3.12 `application_status_history` 无 `CREATE TABLE`、零代码引用（状态迁移由 `application_record.status` + `stage_entered_at`(V26) 承载）；③ **命名漂移**：§3.9 `material_resume_task` → 迁移真名 `material_resume_generation`(V6)。修复：新增 §3.17~§3.22 六章节（按 DDL 提取，含 V15/V25/V29 ALTER）、§3.9 更名、§3.12 标注「设计草案，未实现」、§2.2 补「状态」列、§8 建表顺序更正；**新增 `SchemaDocContractTest`**（双向断言：迁移表必须在 §3 有章节；§3 章节须对应迁移表或显式标注未实现）。验证：**红判定已做**（改回旧名 + 去掉标注 → 两用例各 1 红并分别点出漏写与虚构，md5 校验恢复）；门禁 2/2 绿；server 全量 **902 测试 0 失败**（本批 +2，5 skipped 为环境门控）；CI + Functional Regression 双绿（workflow run 36847869352 / 36847869373，head e444dcc，复核结论 success） |
| 2026-10-01 | **第五十六批（前端 API 契约对账：93 调用 × 95 端点双向零缺陷，结论固化为门禁；§2.49 扫描新增项）执行完成**：把前端 `web/src/api/*.ts` 的 **93** 个 `(method, path)` 与后端 19 个控制器的 **95** 个端点做全量对账（路径变量归一 `{}`，**含 HTTP 动词** —— 路径对但动词错同样是 405）。结果：**前端 → 后端零缺失**；后端 → 前端仅 2 个无 UI 调用且有据（`GET /api/system/health/detail` 运维端点、`POST /api/auth/logout-all` 无前端入口）；字段级抽样 `AiTask` 前后端 12 字段**逐字段一致**。该面为**正面结论**（无缺陷），故新增静态门禁 `FrontendApiContractTest` 防漂移：断言前端每个 `(method, path)` 在后端存在，失败信息区分 **404**（路径不存在）/ **405**（动词不同）；反向**不断言**（后端有前端无属合理）；含规模自检。解析踩坑固化：泛型可能嵌套或含引号（`ApiResponse<import('./ai').AiTask>`），**不能**用「动词与路径之间的字符」匹配动词，否则漏掉 `interview.ts` 的 follow-up 调用 → 改为「先定位路径字面量、再向前回溯最近动词」。验证：**红判定已做**（加 `GET /api/does-not-exist` + `PATCH /api/system/health` → 门禁 1 红并逐条标注 404/405，md5 校验恢复）；server 全量 **903 测试 0 失败**（本批 +1，5 skipped 为环境门控）；`docs/05` §15 补前端契约一致性约定；CI + Functional Regression 双绿（workflow run 36849142363 / 36849142250，head c503f9f，复核结论 success） |