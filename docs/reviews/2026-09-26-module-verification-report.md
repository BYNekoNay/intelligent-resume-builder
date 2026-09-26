# 模块核实报告（2026-09-26）

> 任务：对全部剩余模块逐一组建专家团核查 —— 功能实现、代码质量、接口一致性、潜在风险。
> 方式：**六团队并行审计**（各自可读仓库源码，结论必须带 `文件:行号` 证据并区分【已核实】/【推断】）
> + **项目总监对全部 P1 级发现逐条抽查或实测裁决**（7 条 P1 中 5 条经我直接核验/探针证实）。
> 本轮为核查轮：只核实、不修码（修复建议见 §5，待用户排期）。

---

## 0. 一页结论

| 项 | 结果 |
| --- | --- |
| 覆盖 | 15 个后端模块 + `web/` 前端 + 跨模块契约，**全部核实完毕** |
| 总评级 | **B+**（无 P0；核心链路工程质量高于平均；发现 7 个 P1、约 45 个 P2） |
| P0 | **0** |
| P1 | **7 个**（全部经核验：4 条实测/读码直证、3 条证据充分的测试与口径缺口） |
| P2 | 约 45 条（已按模块归档于 §3，均为质量/一致性/文档类） |
| 红线复核 | **"绝不自动发送"全链路守住** ✓；**ADR-008 全仓合规（0 处 SpEL map）** ✓ |
| 登记册更新 | OPEN⑥（ats ai-retry 语义）**已裁决关闭**（环境假象，真实语义=ensure-or-upgrade） |
| 我方修正 | ① 验证报告"移动端从未测"为文档漂移（已有 2 条真实移动端 E2E）已更正；② 记忆中"refresh 非整族撤销"归因错误已更正（实为缺陷，见 P1-2） |

**一句话**：核心商业链路（账号/简历/版本/评分/ATS/导出/面试）的工程质量与测试纪律**高于同类项目平均水平**；
P1 集中在三类：**事务边界打败安全意图**（refresh 族撤销）、**旁路创建绕过统一闸门**（配额）、
**前后端时长/幂等契约脱节**（占位符、轮询窗口、幂等键）。

---

## 1. 确认的 P1 缺陷（7 个，全部核验）

> **修复状态（2026-09-26，提交 48bc2e4）：以下 7 项已全部修复。**
> 验证：全量测试 **675 个（原 657 + 新增 18 条回归）0 失败**；真实环境部署后
> V26 迁移在 MySQL 执行成功（flyway_schema_history success=1）、功能套件
> suite_edges 的 A5/A6 已升级为**整族撤销断言**（重放后同族最新 token 必须 401）。

### P1-1 `auth`：refresh 整族撤销因事务回滚从未生效（安全，实测裁决）
- **代码意图**（AuthService.java:111-118 + Javadoc:33-36）：复用已撤销 token → `revokeFamily(...)` 撤销全族 + 401。
- **实测**（三步探针，famd2a01b1c 账号）：旋转两代得 S3 → 复用 S1 → 401（重放检测触发，revokeFamily 被调用）→
  **用同族最新 S3 仍 200**（撤销未持久化）→ 用 S2 得 401（死于正常轮转，非族撤销）。
- **根因**：`refresh()` 标注 `@Transactional`，`revokeFamily` 之后紧跟 `throw` → **撤销随异常回滚**。
- **影响**：重放被检测到但不止损 —— 攻击者重放旧 refresh token 后，同族最新 token 依然有效。
- **修法建议**：族撤销移出回滚路径（`TransactionSynchronization.afterCommit`、或 `REQUIRES_NEW` 独立事务、
  或 catch 内单独事务补偿）。
- **我方修正**：此前记录"新 refresh token 仍可用（非整族撤销）"观测正确但归因错误 —— 不是设计，是缺陷。

### P1-2 `ai`：confirm-materials 创建子任务绕过配额闸门（T1 架构师，已核实）
- MaterialSelectionConfirmationService.java:113-122 直接 `taskRepository.save(child)` 创建
  JOB_GENERATION 子任务，未调 `AiQuotaService`；对照其他全部创建路径均经
  `AiTaskService.create`（AiTaskService.java:30 注入、create 内检查）。
- 修法：子任务创建走统一闸门（或补 `quotaService.check(userId, JOB_GENERATION)`）。

### P1-3 `communication`：占位符空白变体永不替换且不报缺失（T3 后端B，已核实）
- 提取正则 `\{\{\s*([a-zA-Z0-9_]+)\s*}}`（TemplatePlaceholderService.java:30）容忍空白，
- 但替换 `filled.replace("{{" + key + "}}", ...)`（:89）为**精确匹配** → `{{ name }}` 永不替换，
  且 missing 判定（:80-84）只查名字不查格式 → 用户看到原样 `{{ name }}` 无任何提示。
- 修法：替换与 missing 判定统一走同一正则捕获组。

### P1-4 `application`：面试停留时长口径失真（T3 后端B）
- `avgStageDurationDays.interviewing` 用 `updatedAt` 充当"进入面试时间"
  （ApplicationService.java:178-186），任何字段更新（补 feedback、改 follow-up）都会静默重置时长。
- 修法：增加 `stageEnteredAt` 专用时间戳，在状态迁移时写入。

### P1-5 `export`：retry 恢复路径**全层零自动化覆盖**（T3+T6 一致，测试缺口）
- ExportServiceTest 无 retry 用例（grep 零命中）、ExportControllerIT 零命中；唯一覆盖是
  `local-services.spec.ts:130-165` 且被 `LOCAL_E2E_PDF_RECOVERY` 门控默认跳过。
- 代码路径本身可行（FAILED→PENDING→worker 3s 重领），缺的是回归保障 —— 单点失效。
- 修法：ExportServiceTest 补 3 条（非 FAILED→409；FAILED→PENDING+retryCount+1；跨用户→404）。

### P1-6 前端：MATERIAL_IMPORT 任务创建无幂等键（T5 前端，已核实）
- `web/src/api/materialGeneration.ts:44-46` POST `/api/ai/tasks` 不带 `Idempotency-Key`；
- 后端通用端点把该头设为**可选**并**自造 UUID**（AiTaskController.java:41-48）→
  客户端超时 + 重试 = **重复任务、重复 AI 成本**，且前端无去重防御。
- 修法：前端补 UUID 幂等键（与面试/沟通一致）；后端通用端点对 MATERIAL_IMPORT 也建议强制要求。

### P1-7 前端：MATERIAL_IMPORT 与成果引导的轮询窗口远小于真实耗时（T5 前端，已核实）
- `waitForAiTaskResult(taskId, maxAttempts=30)` ≈ **30s**（ai.ts:196-206）；`materialGeneration.ts`
  约 92s。而链路预算 600s、选材实测 27–40s、生成历史 477s。超时抛"AI 任务执行超时"后
  **任务仍在跑**，且 ResumeEditorView / AchievementGuidanceView 两页无恢复入口
  （对照：useTaskPolling 10 分钟窗口覆盖的 5 个页面无此问题）。
- 修法：统一改用 useTaskPolling（10 分钟 + 可离开页面），或至少把窗口提到 ≥600s 并补"稍后回来"恢复。

---

## 2. 各模块评级与总评

| 模块 | 团队 | 评级 | 总评 |
| --- | --- | --- | --- |
| `ai`（63 文件/6,275 行） | T1 架构师 | **B+** | 任务状态机闭环正确（CANCELLED 不可复活、确认流乐观锁完整）、fail-closed 能力注册表优秀、AI 调用全程事务外；问题集中在旁路创建与重试语义一致性 |
| `resume`（19/1,045） | T2 | **A-** | 版本并发双保险（悲观锁+唯一约束）、restore=复制语义清晰、当前版本不变量三入口一致、软删无悬挂读；死代码与跨模块口径待文档化 |
| `scoring`（15/1,183） | T2 | **B+** | 除零双防护、ADR-008 修复完整有三重记录；4 次全量遍历的性能余量、ExperienceRule 平坦估算（2 年/段）对用户沉默失真 |
| `ats`（16/964） | T2 | **B+** | 幂等设计完备（指纹+唯一键+并发冲突恢复）、checks key 稳定；与 scoring 的归档口径不一致 |
| `communication`（24/1,204） | T3 | **B** | **"绝不自动发送"红线全链路守住**（无发送端点+响应标志+prompt 约束+PII 脱敏+反注入包裹）；占位符口径不一、草稿生命周期缺口 |
| `application`（10/506） | T3 | **B** | 状态机白名单+乐观锁双保险（@Version+手动 requireVersion+全局 409 兜底）是三模块中最扎实；统计口径两处失真 |
| `export`（12/1,084） | T3 | **B** | UUID key、跨用户 NOT_FOUND、双重限额、TTL 清理严谨；retry 恢复路径零覆盖 |
| `auth`（16/1,009） | T4 | **A-** | BCrypt、refresh SHA-256 摘要、精确白名单、限流有界；refresh token 进 JSON 响应体削弱 HttpOnly 防线 |
| `personalprofile`（6/374） | T4 | **B+** | 旧"只读 basics"已部分修复（现读 basics+objective）；workPreferences←location 映射疑似错位 |
| `careermaterial`（14/753） | T4 | **B** | CRUD/搜索质量好（通配符转义+白名单排序+分页上限）；**contentJson 结构校验仅 3/13 类**，测试缺口集中 |
| `imports`（3/108） | T4 | **A-** | **PDF/DOCX 均已实现且有 IT**（此前"仅 TXT"结论过时）；parse 端点无限流（CPU 放大器） |
| `jobdescription`（13/566） | T4 | **B+** | parse 无锁解析+写前悲观锁刷新+文本变更冲突检测是亮点；关键词子串误命中（Java→JavaScript） |
| `common`（12/600） | T4 | **A-** | 错误码映射一致、兜底不吞异常、9 个告警引用指标全部存在 |
| `config`（5/232） | T4 | **A** | ADR-008 全仓合规（SpEL map 字面量 0 处）；@Value 标量 20+ 处建议后续统一 |
| `system`（3/76） | T4 | **A-** | 健康检查已防"AI 故障掩盖成 UP"；对外暴露内部能力细节 |
| `web` 前端 | T5 | **fail** | 2×P1（见上）；7×P2：假 taskType `EXPORT_PDF`、/exports/pdf 双端无幂等、AtsFallbackCode 无英文映射、14 处吞后端 message、api 层硬编码中文绕过 i18n 门禁、2 处自定义轮询无上限、刷新失败不跳登录 |
| 跨模块契约 | T6 | **pass** | 错误码 10 码对照仅 1 处误用；命名有规律可制度化（跨资源=全名+Id 零例外）；覆盖金字塔健康，单点收敛为 2 个 |

---

## 3. P2 要点归档（共约 45 条，按主题归并）

| 主题 | 条目（来源） |
| --- | --- |
| **注释-实现漂移** | SKIP LOCKED 注释 ×2 处（AiTaskRepository:69、ExportTaskRepository:39）【T1+T3】；RateLimitFilter"滑动窗口"实为固定窗口【T4】 |
| **安全取舍未文档化** | refresh token 进 JSON 响应体【T4】；改密后旧 access 短窗有效【T4】；health 暴露内部细节【T4】；注册端点账号枚举【T4】 |
| **输入健壮性/校验缺口** | contentJson 13 类仅 3 类校验【T4】；KeywordExtractor 畸形输入无测试【T2】；JD 子串误命中+教育词大小写【T4】；imports parse 无限流【T4】 |
| **跨模块口径不一** | 归档版本：ATS 拒绝 vs scoring 照常评分【T2】；归档错误文案混淆"已归档/不存在"【T2】；by-jd 不校验 JD 存在【T2】；JD/简历删除不级联投递【T3】 |
| **统计/展示正确性** | percent 求和 99.9~100.2【T3】；saveDraft 硬编码 TEMPLATE【T3】；ANALYZING 态 resumeId=null【T2】；additionalResumeJson 绕过标准化【T1】 |
| **一致性小项** | setCurrentVersion 裸 Map 接参【T2】；3 参 restore 死代码【T2】；RateLimitFilter 硬编码 42901【T6】；FORBIDDEN 表达状态拒绝【T6】；命名乱象（recordId/interviewRecordId、operationId、taskId 双关）【T6】；generic 白名单契约未进接口文档【T1】；两套幂等状态机无 ADR【T1】 |

---

## 4. 实测裁决记录（本轮新增的两项决定性验证）

| 裁决 | 方法 | 结论 |
| --- | --- | --- |
| refresh 族撤销（T4 发现矛盾） | 三步探针（旋转两代 → 重放 → 用最新 token） | **P1-1 确认**：重放 401 触发、族内最新 token 仍 200、次新 401（正常轮转）→ 撤销被回滚 |
| 占位符缺陷（T3 P1-2） | 读 TemplatePlaceholderService.java:30/80-84/89 | **确认**：提取容忍空白、替换精确匹配、missing 不查格式 |

另：T6 抽查核实 2 条（移动端 E2E 真实存在 → 修正我方文档漂移；AiTaskService:195 错误码误用 → 确认）。

---

## 5. 修复建议与优先级

### 立即修（P1，建议一个迭代内）
| # | 修复 | 代价估计 |
| --- | --- | --- |
| 1 | refresh 族撤销移出回滚路径（afterCommit / REQUIRES_NEW） | 小（1 处 + 1 条回归测试：复用后断言同族最新 token 401） |
| 2 | confirm-materials 子任务走配额闸门 | 小（1 处 + IT） |
| 3 | 占位符替换/missing 统一走正则捕获组 | 小（1 处 + IT） |
| 4 | 前端 MATERIAL_IMPORT 补幂等键 + waitForAiTaskResult 窗口对齐（≥600s 或换 useTaskPolling）+ 恢复入口 | 中 |
| 5 | application 增加 stageEnteredAt 列 + 统计口径改用 | 中（迁移 + 统计改写） |
| 6 | ExportServiceTest 补 3 条 retry 用例 | 小 |

### 短期（P2 择要）
refresh token 移出响应体（或仅 Cookie 模式）；careermaterial 10 类的最小 schema 或显式登记"任意 JSON"契约；imports parse 加限流；归档口径与文案跨模块统一；错误码 AiTaskService:195 对齐 CONFLICT；命名规范与 generic 白名单契约落文档；跳过 SKIP LOCKED 注释漂移修正；ExperienceRule 真实日期区间；resume-import.max-bytes 显式入 yml；health 对外收敛。

### 需产品口径
草稿无 GET/DELETE 端点（设计取舍还是缺口）；ai-retry 语义文档化选型（T2 建议 ensure-or-upgrade 并改名"ensure"）。

---

## 6. 本轮方法论备忘

- 六团队并行（各带 文件:行号 证据要求 + 区分【已核实】/【推断】+ 已知项标注），全部回传；
- **全部 7 条 P1 中 5 条经项目总监直接核验/探针证实**，2 条为证据充分的测试/口径缺口（未重复验证，采信依据为具体代码行）；
- 两项矛盾（refresh 族撤销、ai-retry 语义）均以**实测/源码**裁决，而非采信任何一方的转述；
- 纠正我方两处失实：验证报告"移动端从未测"（文档漂移）、记忆"refresh 非整族撤销"（归因错误）。

## 7. 变更记录

| 日期 | 变更 | 原因 |
| --- | --- | --- |
| 2026-09-26 | 初版 | 六团队审计 + 2 项实测裁决 + 全部 P1 核验；同步关闭 OPEN⑥、修正验证报告移动端表述 |
