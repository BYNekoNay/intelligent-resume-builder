# 计划：AI 发送前预览（R32）与失败保留/降级（R33）

> 状态：**已批准执行**（2026-10-26）。本文是执行依据，验收标准逐条可测。
> 依据：`docs/plans/2026-10-20-001-feat-windows-offline-career-workspace-plan.md` 的 R32/R33，
> ADR-012 的 AE2 / AE6，以及 2026-10-26 的链路实测（见 §1 现状差距）。

## 1. 现状差距（实测，不是推测）

| 要求 | 现状 | 差距 |
| --- | --- | --- |
| R32 发送前展示**将要发送的确切内容** | 无 dry-run 能力。prompt 在 worker 里现场生成（`PromptTemplates` / `JobGenerationPromptBuilder`），任务创建时无法得到待发内容 | 缺「预览组装」与「预览 → 创建」的可校验绑定 |
| R32 默认**排除联系方式** | `JOB_GENERATION` / `JOB_MATERIAL_SELECTION` / `COMMUNICATION_GENERATE` / `ATS_ANALYSIS` / `INTERVIEW_COACH` **已排除**；`RESUME_OPTIMIZE` / `INLINE_OPTIMIZE` / `ACHIEVEMENT_GUIDANCE` / `MATERIAL_IMPORT` **原样 dump，无脱敏** | 4 类无脱敏 |
| R32 需**显式确认**；取消则一条不发 | 取消=不调 `create`，天然无写入（这条已满足，但缺少"看到的就是发出的"保证） | 需 token 绑定 |
| R33 **不自动重试** | `releaseFailed(retryable=true)` 在 `retryCount < 3` 时把任务打回 `PENDING`，worker 再领取并**再次调 provider** | **行为冲突，必须改** |
| R33 **不静默换供应商** | `callChain` 在模型链上逐个顺延，只写日志与指标 | 需把"换过模型"变成**可见事实** |
| R33 失败保留 prompt / 已选素材 / 草稿 | `input_snapshot_json` 本来就保留 prompt 与已选素材；失败不写 `result_json`、不建 `resume_version`、不落孤儿草稿 | **已满足**（本轮加断言钉住） |

## 2. 设计决策

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D-AI-1 | 预览与实际发送**共用同一个组装函数**（把 `BailianAiProvider.buildMessages` 抽成 `AiMessageAssembler`），不另写一份 | 两处各写一份必然漂移，"看到的"和"发出去的"会悄悄不一样 |
| D-AI-2 | 预览返回 `previewToken` = 载荷规范化后的 SHA-256（含 taskType + messages + 脱敏开关） | 预览后改了输入 → token 对不上 → 拒绝创建，这是"确切的"唯一可校验实现 |
| D-AI-3 | 创建任务时**必须**带 `previewToken`；缺失 → `AI_PREVIEW_REQUIRED`，不符 → `AI_PREVIEW_STALE`，两者都**不落库** | 不做"可选的确认"，否则等于没有 |
| D-AI-4 | 脱敏默认**排除邮箱与手机号**（`includeContact=false`），用户可显式勾选包含 | R32 原文要求；但**不改 URL/普通文本**，避免把简历里的项目链接也删掉影响质量 |
| D-AI-5 | 关闭自动重试：可重试失败也直接 `FAILED`，把 `retryable` 暴露给前端，重试只能由用户点 | R33 原文；且失败任务仍占配额，自动重试会让配额消耗对用户不可见 |
| D-AI-6 | 模型链顺延保留（同供应商内、配额驱动），但把每次尝试写进结果 `modelAttempts` | 直接禁用会让"某模型额度耗尽"时整条链路不可用；R33 要求的是**不静默**，不是不换 |

## 3. 实施批次与验收标准

### P1 · R32 后端（预览组装 + 脱敏 + token 绑定）

| AC | 验收标准（可测） |
| --- | --- |
| AC-R32-1 | `POST /api/ai/tasks/preview` 返回的 `messages` 与 worker 实际交给 provider 的 `messages` **逐字节相等**（同一 `AiMessageAssembler`，单测对同一 input 断言两次调用结果相等） |
| AC-R32-2 | 输入含 `alice@example.com` / `13800138000` 时，预览文本中**不出现**原始值，且响应 `redactions` 列出被替换的类别与次数；`includeContact=true` 时不脱敏 |
| AC-R32-3 | 不带 `previewToken` 创建任务 → 返回 `AI_PREVIEW_REQUIRED`，且 `ai_task` **零新增行** |
| AC-R32-4 | 预览后修改 input 再创建 → `AI_PREVIEW_STALE`，零新增行 |
| AC-R32-5 | 预览接口**不写库、不调 provider**（断言 repository 无新增、provider 零调用） |

### P2 · R32 前端（预览确认流）

| AC | 验收标准 |
| --- | --- |
| AC-R32-6 | 点击生成 → 先弹出预览（可看到完整待发文本与本轮脱敏摘要）→ 确认后才发起任务；取消则无任何请求发出（e2e 断言 `/api/ai/tasks` 未被调用） |
| AC-R32-7 | 预览层可勾选「包含联系方式」，勾选后重新预览，`redactions` 为空 |

### P3 · R33 后端（不自动重试 + 换模型可见 + 保留断言）

| AC | 验收标准 |
| --- | --- |
| AC-R33-1 | provider 返回可重试失败 → 任务**直接变 `FAILED`**，`retry_count` 不再增加（改写 `TaskLeaseServiceTest` 里与新要求冲突的两条用例） |
| AC-R33-2 | 失败任务的 `input_snapshot_json` 仍可读出 prompt 与已选素材；`result_json` 为空；`resume_version` 无新增；无孤儿草稿 |
| AC-R33-3 | 模型链顺延后，任务/结果里出现 `modelAttempts`（每次模型名与失败类别），前端可见 |
| AC-R33-4 | 用户手动重试仍可用（`POST /api/ai/tasks/{id}/retry`），且只在 `FAILED` 时允许 |

### P4 · 阶段六收尾（Electron 窗口与安装包）

| AC | 验收标准 |
| --- | --- |
| AC-SHELL-1 | `desktop-shell` 能在有图形会话的 Windows 上 `npm install && npm start` 打开窗口并加载工作区（本机无头，交由人工确认，**不算已验证**） |
| AC-SHELL-2 | `electron-builder` 能产出 NSIS 安装包，安装后启动能拉起 jar 并进入登录后的工作区 |
| AC-SHELL-3 | 安装包内**不含**任何密钥文件（`ai-credentials/`、`secrets.env`、`.env`） |

## 4. 明确不做

- R32 不覆盖"任务执行中再次确认"的场景（任务是一次性提交，不存在中途改内容）。
- R33 不实现"失败自动降级到其他供应商"（D7 明确不静默切换；本地规则降级只存在于 ATS，且已有）。
- `inline_optimization_record` 表仍无写入者 —— 本轮不新增写入路径（"失败保留当前草稿"由 `ai_task` 快照 + 前端编辑器状态满足，草稿历史是另一件事）。

## 5. 风险

| 风险 | 处置 |
| --- | --- |
| 强制 `previewToken` 会打破既有客户端/测试 | 一次性改完并同步测试；这本身就是"必须显式确认"的落地代价，不设后门开关 |
| 脱敏过度导致生成质量下降 | 只脱敏邮箱/手机号两类，不动 URL 与正文 |
| 关闭自动重试后瞬时抖动会变成用户可见失败 | 失败响应里给出 `retryable`，前端提供一键重试；这符合 R33「由人决定」的口径 |

---

## 6. 执行状态（2026-10-26）

| AC | 状态 | 守护用例 |
| --- | --- | --- |
| AC-R32-1 | ✅ | `AiPromptParityTest`（provider 路径 == 预览路径，含脱敏开关两态） |
| AC-R32-2 | ✅ | `ContactRedactorTest` + `AiPromptParityTest#defaultExcludesContactAndKeepsEverythingElse` |
| AC-R32-3 | ✅ | `AiTaskPreviewProtocolIT#createWithoutTokenRejected`（含"不落库"断言） |
| AC-R32-4 | ✅ | `AiTaskPreviewProtocolIT#staleTokenRejected`（含"不落库"断言） |
| AC-R32-5 | ✅ | `AiTaskPreviewProtocolIT#previewIsReadOnly` |
| AC-R32-6 | ✅ | `e2e/ai-preview.spec.ts`「取消预览则一条任务都不发」（断言 `/api/ai/tasks` 零调用） |
| AC-R32-7 | ✅ | `e2e/ai-preview.spec.ts`「勾选后重新预览并带新令牌提交」 |
| AC-R33-1 | ✅ | `TaskLeaseServiceTest#releaseFailed_retryable_stillMarksFailed`（断言 FAILED，改回 PENDING 即红） |
| AC-R33-2 | ✅ | 既有行为已满足，由 `AiTaskControllerIT`/`TaskLeaseServiceTest` 覆盖保留语义 |
| AC-R33-3 | ✅ | `BailianAiProviderChainTest#fallbackSuccessRecordsModelAttempts` + `AiTaskResults` 落结果 |
| AC-R33-4 | ✅ | 手动重试入口未改动（`InlineOptimizeController#retry`，仅 FAILED 可重试） |
| AC-SHELL-1 | ⚠️ **未验证** | 窗口本身在无头会话里起不来（进程即退、日志为空）—— 需人工在有图形会话的 Windows 上 `npm install && npm start` |
| AC-SHELL-2 | ✅ 产出 + ⚠️ 安装流程未点 | NSIS 安装包已产出（162MB）；打包后的编排用 `smoke --packaged` 验证（16s 就绪、SPA 200、`/api/desktop/ai-settings` 200）；**安装器点完能启动未验证** |
| AC-SHELL-3 | ✅ | 包内只有 0.4.2 一个 jar；扫描无 `.env`/`secrets.env`/`resume.mv.db`；asar 内无 `ai-credentials` 字样 |

**本轮范围说明**：R32 的强制校验落在**通用任务端点**（`POST /api/ai/tasks`）。
`JOB_GENERATION` / `JOB_MATERIAL_SELECTION` / `COMMUNICATION_GENERATE` / `ATS_ANALYSIS` / `INTERVIEW_COACH`
走各自的领域端点，且本来就已排除联系方式、本身带确认步骤 —— 把它们接进预览协议是下一批的事（不在本轮声称完成）。

---

## 7. P5：把预览协议覆盖到 5 个领域端点（2026-10-26 追加）

### 7.1 问题

上一批只在**通用端点**（`POST /api/ai/tasks`）上强制了"必须先预览"。
其余 5 类任务走各自领域端点，仍然可以**不带任何预览**直接发起：

| 任务类型 | 端点 | 现状 |
| --- | --- | --- |
| `JOB_MATERIAL_SELECTION` | `POST /api/ai/select-materials-for-job` | 无预览 |
| `JOB_GENERATION` | `POST /api/ai/tasks/{id}/confirm-materials` | 无预览（"确认选材"页展示的是素材，不是发给模型的确切内容） |
| `COMMUNICATION_GENERATE` | `POST /api/communications/ai-generate` | 无预览 |
| `ATS_ANALYSIS` | `POST /api/ats/check`、`/api/ats/checks/{id}/ai-retry` | 无预览 |
| `INTERVIEW_COACH` | `POST /api/interviews/{id}/follow-up` | 无预览 |

这 5 类**本来就已经排除联系方式**，所以缺的不是脱敏，而是"用户看得到将要发送的确切内容"。

### 7.2 设计

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D-AI-7 | 预览令牌走**请求头** `X-AI-Preview-Token`，不加进各自 DTO | 5 个 DTO 都改会连带改 20+ 处构造点；请求头不侵入领域模型，且语义上它本来就是"传输层协商" |
| D-AI-8 | 服务端用它**自己组装出的 input** 重算令牌，与客户端回传的比对 | 客户端无法伪造成"预览过"——它拿不到服务端组装结果的哈希 |
| D-AI-9 | 每个领域加一个 `/preview` 端点，复用**同一个** input 组装函数（dry-run，不落库） | 预览与实际必须同源，否则又是"两份实现" |
| D-AI-10 | 令牌进 `AiPreviewService` 的统一入口，5 个领域共用一个 `AiPreviewGuard` | 避免 5 份校验逻辑各自漂移 |

### 7.3 验收标准

| AC | 验收标准（可测） |
| --- | --- |
| AC-R32-8 | 5 个领域的 `/preview` 端点返回的 `messages` 与 worker 实际交给 provider 的 `messages` **逐字节相等**（同一组装函数） |
| AC-R32-9 | 5 个领域的创建端点不带 `X-AI-Preview-Token` → `40001`，且**不落库**（`ai_task` 零新增） |
| AC-R32-10 | 令牌与当前请求不符（预览后改了参数）→ `40901`，不落库 |
| AC-R32-11 | 5 个前端入口在发起前都展示预览弹窗；取消则**零请求**（e2e 断言创建端点未调用） |
| AC-R32-12 | 预览端点本身只读：不落库、不调 provider |

### 7.4 实施顺序（每片都必须独立可交付、可验证）

1. `JOB_MATERIAL_SELECTION` + `JOB_GENERATION`（主链路，两段相连）
2. `COMMUNICATION_GENERATE`
3. `ATS_ANALYSIS`
4. `INTERVIEW_COACH`

每片做完就跑一次后端全量 + e2e；任一片未完成时，**不能**单独启用该片的强制校验（否则前端会直接坏掉）。

### 7.5 第 1 片完成情况（选材）

- 抽 `MaterialSelectionContextBuilder`（候选筛选 + prompt 组装），worker 与预览共用；
  `JobMaterialSelectionService` 298 → 199 行。
- `POST /api/ai/select-materials-for-job/preview`（只读）+ create 端 `X-AI-Preview-Token` 强制
  （缺令牌 40001 / 令牌失效 40901，均不落库）。
- 前端 `GenerationWorkbenchView` 接入预览确认；`materialSelectionBody` 由预览与创建共用。
- 测试：`JobGenerationControllerIT` 3 条（happy path 走真实预览 + 两条拒绝用例）+ e2e 83 passed。

### 7.6 第 2 片执行要点（生成 / JOB_GENERATION）—— 已勘察，未实施

勘察结论（省下一半力气）：

1. **`confirm-materials` 走的是快照路径，不读库**：`JobGenerationService.executeTask` 开头判断
   `snapshot.containsKey("jobSnapshot")`，命中后由 `jobFromSnapshot` / `materialsFromSnapshot` /
   `personalProfileSnapshot` 重建实体（`:88-97`），再调 `promptBuilder.build(...)`（`:199`）。
   → **预览不需要查库，只要拿到同一份 snapshot 即可**，同源天然成立。
2. 现成入口：`MaterialSelectionConfirmationService.confirm`（`:52-138`）里在创建 JOB_GENERATION 前
   已把 snapshot 拼好（`snapshot(job)` / `snapshot(material)` / `snapshot(profile)` 三个私有方法在
   `:208/:218/:229`）。**要做的第一件事是把它抽成 `buildGenerationInput(selection, request, userId)`**，
   confirm 与预览都调这一个方法。

执行步骤：

1. 抽 `buildGenerationInput(...)`（250 行文件，还有余量）；confirm 改为调用它。
2. `JobGenerationService` 加包级 `providerInputFromSnapshot(Map snapshot)`：把 `:87-131` 的
   "重建实体 + promptBuilder.build + 装 `_systemPrompt/_taskPrompt/_dataPrompt`" 抽出来，
   `executeTask` 与预览共用（这条是 AC-R32-8 的关键）。
3. 端点 `POST /api/ai/tasks/{selectionTaskId}/confirm-materials/preview`：
   校验归属 → `buildGenerationInput` 干跑 → `providerInputFromSnapshot` → `AiPreviewService.preview`。
4. create 端（`confirm` 入口）加 `X-AI-Preview-Token` 头校验，令牌由预览返回。
5. 前端 `MaterialSelectionConfirmView`：确认选材后先弹预览。
6. 测试：镜像第 1 片的四条（happy path 走真实预览 + 缺令牌 + 令牌失效）+ docs/05 补端点
   （`ApiDocCoverageGateTest` 会卡）。

第 3-5 片同理：沟通（`CommunicationAiPromptBuilder` 已是独立 builder，最省力）、
ATS（按 `checkId` 预览）、面试（接 `InterviewContextSanitizer`）。
