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

**第三批 · 功能与性能（中成本）**
10. #2 搜索覆盖 contentJson（修复错误零结果；需定匹配策略）
11. PA-2 JWT 短 TTL 缓存（30–60s，抵消 #6 带来的每请求查库）
12. #66 错误码映射接入收尾（~6 处视图）

**需产品/环境决策后再定**
- #26 ai_task 留存与清理策略（保留多久、是否提供用户删除入口）
- #7 账号数据导出/删除前端入口（隐私治理口径）
- #53/#54/#55 AI 上下文白名单是否扩展（哪些章节应进 AI 输入）
- #22 MySQL 5.7 门禁是否维持（有无 5.7 环境）

## 5. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-09-30 | 初版：4 个并行 agent 分区间核对 101 条 finding + Ranked Ideas + 新增核对表；人工抽查 5 处关键证据；产出「仍存在」聚类清单与三批推荐 |
| 2026-09-30 | **第一批（隐私与配置对齐）执行完成**：#68 日志 userId 移除 + `LogPrivacyGateTest` 静态门禁（扫描全部日志调用，防复发）；#71 consent 排序加 `id` tie-break；#60 租约 180→660s（> 链总预算 600s）+ 心跳池 2 线程；#49 四处入口幂等键契约统一（AiTask/JobMaterialSelection/Communication/Interview）。回归：全量 **740 测试 0 失败**（含新门禁），新增幂等键契约 IT 用例定向通过 |
| 2026-09-30 | **第二批（并发健壮性）执行完成**：#46 完整性/并发冲突统一 409（两个全局 handler，日志不记异常 message）；#45 refresh 轮换 CAS 原子化（不用行锁——避免与 REQUIRES_NEW 撤销服务自锁），并发刷新单赢家 + 败者撤族 401；#67 职业资料 `@Version` + V27 迁移；#48 AI 任务幂等并发回读（三分支）。新增 `AuthConcurrencyIT`（真并发双场景）与 5 个分支用例，回归：全量 **748 测试 0 失败** |